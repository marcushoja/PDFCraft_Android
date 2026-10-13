package de.pdfcraft.android

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** A small, offline-first smartphone UI. All documents are private imported copies. */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // Preserve the original Activity-backed service context (especially PrintManager).
        // A replacement ConfigurationContext would bind system services to a non-Activity.
        super.attachBaseContext(newBase)
        val tag = LanguageContext.selected(newBase)
        if (tag != "system") applyOverrideConfiguration(Configuration(newBase.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(tag))
        })
    }
    private lateinit var store: DocumentStore
    private lateinit var thumbnails: ThumbnailLoader
    private lateinit var textService: PdfTextService
    private lateinit var tools: PdfTools
    private lateinit var markup: PdfMarkup
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("reader_settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var reader: PdfReaderView? = null
    private var selectionMode:android.view.ActionMode?=null
    private var directSelection:Pair<Int,PdfTextSelection>?=null
    private var reflowView: ReflowView? = null
    private var reflow = false
    private var reflowOffset = 0f
    private var reflowLoading = false
    private var activeQuery = ""
    private var textPage = -1
    private var fullscreenExit: View? = null
    private var pendingExport: File? = null
    private var unlocked: UnlockedPdf? = null
    private var exportName = "PDFCraft.pdf"
    private var documentId: String? = null
    private var chromeVisible = true
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var pageLabel: TextView? = null
    private var bookmarkButton: ImageView? = null
    private var loadingView: PdfLoadingView? = null
    private var outlinePanel: OutlinePanel? = null
    private var searchProgress: AlertDialog? = null
    private var importGeneration = 0
    private var pageSeek: SeekBar? = null
    private var importing = false
    private var lastChromePage = -1
    private var dark = false
    private val ink get() = ZenColors.of(dark).ink
    private val muted get() = ZenColors.of(dark).muted
    private val paper get() = ZenColors.of(dark).paper
    private val card get() = ZenColors.of(dark).card
    private val accent get() = ZenColors.of(dark).accent
    private val toolbarColor get() = ZenColors.of(dark).tint
    private val savePosition = Runnable { persistPosition() }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pendingExport
        if (uri != null && file != null) io.execute {
            val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
            handler.post { if (!isDestroyed) result.fold({ Toast.makeText(this, l("PDF gespeichert"), Toast.LENGTH_SHORT).show() }, { error("Speichern fehlgeschlagen: ${it.message}") }) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(selectionMode!=null) selectionMode?.finish()
                else if (outlinePanel != null) closeOutline() else if (reader != null) showLibrary() else finish()
            }
        })
        store = DocumentStore(this)
        File(cacheDir, "tools").listFiles()?.filter { it.name.startsWith("unlocked-") }?.forEach { it.delete() }
        thumbnails = ThumbnailLoader(this)
        PDFBoxResourceLoader.init(applicationContext)
        textService = PdfTextService(this)
        tools = PdfTools(this)
        markup = PdfMarkup(this)
        dark = prefs.getBoolean("dark", false)
        showLibrary()
        val restoredId = savedInstanceState?.getString("document")
        if (restoredId != null && store.get(restoredId) != null) {
            openDocument(restoredId)
        } else handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) importPdf(uri)
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun l(value: String) = UiText.t(this,value)
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false, translate: Boolean = true): TextView =
        TextView(this).apply {
            text = if (translate) l(value) else value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun button(label: String, action: () -> Unit): TextView = text(label, 14f, accent, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = shape(card, 12); isClickable = true; isFocusable = true
        contentDescription = l(label); setOnClickListener { action() }
    }
    private fun iconButton(symbol: String, label: String, action: () -> Unit): ImageView = ImageView(this).apply {
        setImageDrawable(MaterialSymbols.drawable(this@MainActivity, symbol, accent))
        scaleType = ImageView.ScaleType.FIT_CENTER
        minimumHeight = dp(48); minimumWidth = dp(48); setPadding(dp(12),dp(12),dp(12),dp(12))
        contentDescription = l(label); isFocusable = true; isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22496650), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), android.graphics.drawable.ColorDrawable(Color.WHITE))
        setOnClickListener { action() }
    }
    private fun gap(size: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }
    private fun newRoot() {
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        root = column().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        window.statusBarColor = paper
        window.navigationBarColor = paper
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun showLibrary() {
        textService.cancelSearch(); searchProgress?.dismiss(); searchProgress = null
        persistPosition()
        reader?.close(); reader = null; reflowView = null; reflow = false; unlocked?.file?.delete(); unlocked = null; documentId = null
        handler.removeCallbacks(savePosition)
        loadingView = null; outlinePanel = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        newRoot()
        root.addView(LibraryView(this, store, thumbnails, dark, { openDocument(it.id) }, { documentMenu(it) }, { pickPdf() }, { settings() }), LinearLayout.LayoutParams(-1, -1))
    }

    private fun documentMenu(doc: PdfDocument) {
        LocalizedDialogBuilder(this).setRawTitle(doc.name).setItems(arrayOf("Öffnen", "Umbenennen", "Teilen", "Aus Bibliothek entfernen")) { _, which ->
            if (which == 0) openDocument(doc.id)
            else if (which == 1) textInput("Dokument umbenennen", doc.name, rawHint = true) { name -> store.rename(doc.id, name); showLibrary() }
            else if (which == 2) share(store.file(doc))
            else LocalizedDialogBuilder(this).setTitle("Lokale Kopie entfernen?")
                .setMessage("Lesefortschritt und Lesezeichen werden gelöscht. Die ursprüngliche PDF bleibt erhalten.")
                .setNegativeButton("Abbrechen", null).setPositiveButton("Entfernen") { _, _ -> store.remove(doc.id); showLibrary() }.show()
        }.show()
    }

    private fun pickPdf() {
        try {
            filePicker.launch(arrayOf("application/pdf"))
        } catch (_: Exception) { error("Keine App zur Dateiauswahl verfügbar.") }
    }

    private fun importPdf(uri: Uri) {
        if (importing) return
        importing = true
        val token = ++importGeneration
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        showLibrary(); newRoot()
        val progress = PdfLoadingView(this, dark, l("PDF übernehmen")) { cancelled.set(true); importGeneration++; importing = false; showLibrary() }
        root.addView(progress, LinearLayout.LayoutParams(-1,-1))
        io.execute {
            val result = runCatching { store.importDocument(uri, { copied, total -> handler.post { if (token == importGeneration && !isDestroyed) progress.updateCopy(copied,total) } }, { cancelled.get() }) }
            handler.post {
                if (token != importGeneration) return@post
                importing = false
                if (isFinishing || isDestroyed) return@post
                result.fold({ openDocument(it.id) }, { showLibrary(); if (!cancelled.get()) error(it.message ?: "Die PDF konnte nicht geöffnet werden.") })
            }
        }
    }

    private fun openDocument(id: String) {
        val doc = store.get(id) ?: return
        persistPosition(); reader?.close(); unlocked?.file?.delete(); handler.removeCallbacks(savePosition)
        documentId = id; store.markOpened(id); chromeVisible = true
        unlocked = null
        lastChromePage = -1
        positionPage = doc.page; positionOffset = doc.offset; positionZoom = doc.zoom
        newRoot()
        val top = row().apply { setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(toolbarColor) }
        top.addView(iconButton("back", "Zur Bibliothek") { showLibrary() })
        top.addView(text(doc.name, 16f, ink, true, translate = false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        bookmarkButton = iconButton("bookmark_outline", "Lesezeichen setzen oder entfernen") { reader?.let { store.toggleBookmark(id, it.currentPage); lastChromePage = -1; updateReaderChrome() } }.apply { this.id = R.id.bookmark_button }
        top.addView(bookmarkButton)
        top.addView(iconButton("fullscreen", "Vollbild") { toggleChrome() })
        top.addView(iconButton("menu", "Leseoptionen") { readerMenu() }.apply { this.id = R.id.menu_button })
        topBar = top; root.addView(top)
        val view = PdfReaderView(this)
        view.onSelectionChanged = { page,selection ->
            if(selection==null) { directSelection=null;selectionMode?.finish();selectionMode=null }
            else {
                directSelection=page to selection
                if(selectionMode==null) selectionMode=startActionMode(object:android.view.ActionMode.Callback2() {
                    override fun onCreateActionMode(mode:android.view.ActionMode,menu:android.view.Menu):Boolean {
                        menu.add(0,R.id.pdf_selection_copy,0,getString(R.string.ui_selection_copy)).apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS) }
                        menu.add(0,R.id.mark_selection,1,getString(R.string.ui_highlight_text)).apply { setIcon(MaterialSymbols.drawable(this@MainActivity,"highlight",accent));setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS) }
                        menu.add(0,R.id.pdf_selection_all,2,getString(R.string.ui_selection_all)).apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM) }
                        return true
                    }
                    override fun onPrepareActionMode(mode:android.view.ActionMode,menu:android.view.Menu)=false
                    override fun onActionItemClicked(mode:android.view.ActionMode,item:android.view.MenuItem):Boolean {
                        val picked=directSelection ?: return false
                        when(item.itemId) {
                            R.id.pdf_selection_copy -> {
                                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PDF",picked.second.text))
                                mode.finish()
                            }
                            R.id.mark_selection -> { mode.finish();markupDialog(picked.second.text,picked) }
                            R.id.pdf_selection_all -> view.selectAllText()
                            else -> return false
                        }
                        return true
                    }
                    override fun onDestroyActionMode(mode:android.view.ActionMode) {
                        selectionMode=null;directSelection=null;view.clearTextSelection()
                    }
                    override fun onGetContentRect(mode:android.view.ActionMode,target:android.view.View,rect:android.graphics.Rect) {
                        rect.set(view.selectionContentRect())
                        val sourceLocation=IntArray(2);val targetLocation=IntArray(2)
                        view.getLocationOnScreen(sourceLocation);target.getLocationOnScreen(targetLocation)
                        rect.offset(sourceLocation[0]-targetLocation[0],sourceLocation[1]-targetLocation[1])
                    }
                },android.view.ActionMode.TYPE_FLOATING)
                else selectionMode?.invalidateContentRect()
            }
        }
        view.id = R.id.pdf_canvas
        reader = view
        val frame = FrameLayout(this)
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        val textView = ReflowView(this)
        textView.onMarkSelection = { markupDialog(it) }
        textView.id = R.id.reflow_canvas
        textView.textView.id = R.id.reflow_text
        reflowView = textView
        textView.visibility = View.GONE
        textView.onOffsetChanged = { offset -> if (reflow && !reflowLoading) { reflowOffset = offset; handler.removeCallbacks(savePosition); handler.postDelayed(savePosition, 350) } }
        frame.addView(textView, FrameLayout.LayoutParams(-1, -1))
        fullscreenExit = iconButton("fullscreen_exit", "Vollbild verlassen") { toggleChrome() }.apply { visibility = View.GONE; background = shape(card,24) }
        frame.addView(fullscreenExit, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.BOTTOM).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        loadingView = PdfLoadingView(this, dark, doc.name) { showLibrary() }
        frame.addView(loadingView, FrameLayout.LayoutParams(-1,-1))
        reflow = false; reflowLoading = false; activeQuery = ""; textPage = -1; reflowOffset = doc.reflowOffset
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = column().apply { setPadding(dp(12), dp(4), dp(12), dp(8)); setBackgroundColor(paper) }
        pageSeek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = l("Seite wählen")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel?.text = "${progress + 1} / ${view.pageCount}"
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) { view.goToPage(seek?.progress ?: 0) }
            })
        }
        bottom.addView(pageSeek, LinearLayout.LayoutParams(-1, dp(40)))
        val nav = row()
        nav.addView(iconButton("back", "Vorherige Seite") { if (reflow) view.goToPage(view.currentPage - 1) else view.turnToPage(view.currentPage - 1) })
        pageLabel = button("…") { jumpDialog() }.apply { contentDescription = l("Seitenzahl, zu Seite springen"); this.id = R.id.page_indicator }
        nav.addView(pageLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(iconButton("pages", "Seiten") { pagesDialog(false) })
        nav.addView(iconButton("next", "Nächste Seite") { if (reflow) view.goToPage(view.currentPage + 1) else view.turnToPage(view.currentPage + 1) })
        for (index in 0 until nav.childCount) nav.getChildAt(index).background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22496650), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), android.graphics.drawable.ColorDrawable(Color.WHITE))
        bottom.addView(nav); bottomBar = bottom; root.addView(bottom)
        view.onDocumentLoaded = { count ->
            loadingView?.setMessage("Deine erste Seite wird gerendert …")
            pageSeek?.max = (count - 1).coerceAtLeast(0)
            applyReaderSettings()
            updateReaderChrome()
            if (doc.lastView == "reflow") setReflow(true, doc.reflowOffset)
        }
        view.onLoadProgress = { loaded, total -> loadingView?.updateProgress(loaded,total) }
        view.onFirstPageRendered = { loadingView?.visibility = View.GONE }
        view.onPositionChanged = { page, offset, zoom ->
            positionPage = page; positionOffset = offset; positionZoom = zoom
            if (reflow && textPage != page) loadReflow(0f)
            updateReaderChrome(); handler.removeCallbacks(savePosition); handler.postDelayed(savePosition, 350)
        }
        view.onTap = { toggleChrome() }
        view.onError = { message -> if (documentId == doc.id) { loadingView?.visibility = View.GONE; handleOpenError(doc, message) } }
        view.open(store.file(doc), doc.page, doc.offset, doc.zoom)
        applyReaderSettings()
    }

    private var positionPage = 0
    private var positionOffset = 0f
    private var positionZoom = 1f

    private fun persistPosition() {
        val id = documentId ?: return
        val view = reader ?: return
        if (view.pageCount <= 0) return
        if (reflow && reflowLoading) return
        if (reflow) store.updateTextProgress(id, view.currentPage, reflowOffset, view.pageCount)
        else store.updateProgress(id, positionPage, positionOffset, positionZoom, view.pageCount)
    }

    private fun updateReaderChrome() {
        val view = reader ?: return
        if (lastChromePage == view.currentPage) return
        lastChromePage = view.currentPage
        pageLabel?.text = "${view.currentPage + 1} / ${view.pageCount}"
        if (pageSeek?.isPressed != true) pageSeek?.progress = view.currentPage
        val marked = store.get(documentId ?: "")?.bookmarks?.contains(view.currentPage) == true
        bookmarkButton?.isSelected = marked
        bookmarkButton?.setImageDrawable(MaterialSymbols.drawable(this, if (marked) "bookmark" else "bookmark_outline", accent))
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        topBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        bottomBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        fullscreenExit?.visibility = if (chromeVisible) View.GONE else View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun jumpDialog() {
        val view = reader ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = l("1 bis ${view.pageCount}"); setText("${view.currentPage + 1}"); selectAll()
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dialog = LocalizedDialogBuilder(this).setTitle("Zu Seite springen").setView(input)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Öffnen", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..view.pageCount) input.error = l("Bitte eine Seite zwischen 1 und ${view.pageCount} eingeben")
                else { view.goToPage(page - 1); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun readerMenu() {
        ReaderActionSheet(this, dark, listOf(
            ReaderAction("open", "PDF öffnen", "folder"), ReaderAction("dark", "Dunkler Modus", "moon"),
            ReaderAction("search", "Im Dokument suchen", "search"), ReaderAction("jump", "Zu Seite springen", "jump"),
            ReaderAction("bookmark", "Seite merken", "bookmark"), ReaderAction("full", "Vollbild", "fullscreen"),
            ReaderAction("text", if (reflow) "PDF-Ansicht" else "Textmodus / Reflow", "text"), ReaderAction("pages", "Seitenübersicht", "outline"),
            ReaderAction("share", "Teilen", "share"), ReaderAction("print", "Drucken", "print"),
            ReaderAction("signature", "Unterschrift", "signature"), ReaderAction("tools", "PDF-Werkzeuge", "tools"),
            ReaderAction("reload", "PDF neu laden", "reload"), ReaderAction("info", "Dokumentinformationen", "info"),
            ReaderAction("screenshot", "Screenshot", "camera"), ReaderAction("zoom", "Zoom zurücksetzen", "crop"),
            ReaderAction("outline", "Inhaltsverzeichnis", "outline"), ReaderAction("bookmarks", "Meine Lesezeichen", "bookmark"),
            ReaderAction("notes", "Notizen anzeigen", "notes"), ReaderAction("settings", "Einstellungen", "settings"),
            ReaderAction("about", "Über PDFCraft", "info")
        )) { action ->
            when (action) {
                "open" -> pickPdf(); "dark" -> { persistPosition(); dark = !dark; prefs.edit().putBoolean("dark",dark).apply(); documentId?.let { openDocument(it) } }
                "search" -> searchDialog(); "jump" -> jumpDialog(); "bookmark" -> bookmarkButton?.performClick()
                "full" -> toggleChrome(); "text" -> setReflow(!reflow); "pages" -> pagesDialog(false)
                "share" -> currentDocument()?.let { share(store.file(it)) }; "print" -> printDocument()
                "signature" -> currentDocument()?.let { doc -> if (unlocked != null && unlocked?.owner != true) error("Bearbeitung ist nur mit dem Eigentümerpasswort verfügbar.") else signatureDialog(viewingFile(doc),doc.name.removeSuffix(".pdf")) }
                "tools" -> toolsDialog(); "reload" -> { persistPosition(); documentId?.let { openDocument(it) } }
                "info" -> propertiesDialog(); "screenshot" -> screenshotDocument(); "zoom" -> reader?.resetZoom()
                "outline" -> outlineDialog(); "bookmarks" -> pagesDialog(true); "notes" -> notesDialog(); "settings" -> settings()
                "about" -> LocalizedDialogBuilder(this).setTitle("Über PDFCraft").setMessage("PDFCraft Android\nVon Marcus Hoja\nEin privater PDF-Reader. Offline und ohne Konto.").setPositiveButton("OK",null).show()
            }
        }.show()
    }

    private fun pagesDialog(bookmarksOnly: Boolean) {
        val doc = store.get(documentId ?: "") ?: return
        val view = reader ?: return
        val pages = if (bookmarksOnly) doc.bookmarks else (0 until view.pageCount).toList()
        if (pages.isEmpty()) {
            LocalizedDialogBuilder(this).setTitle("Meine Lesezeichen").setMessage("Tippe beim Lesen auf das Lesezeichen-Symbol, um eine Seite zu merken.").setPositiveButton("OK", null).show()
            return
        }
        val grid = GridView(this).apply { numColumns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3; horizontalSpacing = dp(8); verticalSpacing = dp(12); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = pages.size
            override fun getItem(position: Int) = pages[position]
            override fun getItemId(position: Int) = pages[position].toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val cell = (convertView as? LinearLayout) ?: column().apply {
                    addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(-1, dp(132)))
                    addView(text("", 14f, ink).apply { gravity = Gravity.CENTER; minHeight = dp(36) })
                }
                (cell.getChildAt(1) as TextView).text = "${pages[position] + 1}" + if (doc.bookmarks.contains(pages[position])) " ★" else ""
                cell.setBackgroundColor(if (pages[position] == view.currentPage) if (dark) 0xff304d3d.toInt() else 0xffe0ede3.toInt() else card)
                thumbnails.load(viewingFile(doc), pages[position], dp(120), cell.getChildAt(0) as ImageView)
                return cell
            }
        }
        val dialog = LocalizedDialogBuilder(this).setTitle(if (bookmarksOnly) "Meine Lesezeichen" else "Seitenübersicht")
            .setView(grid).setNegativeButton("Schließen", null).create()
        grid.setOnItemClickListener { _, _, index, _ -> view.goToPage(pages[index]); dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }

    private fun settings() {
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        content.addView(text("Sprache",14f,muted,true))
        val languageTags = listOf("system","de","en","fr","es")
        val language = Spinner(this).apply {
            id = R.id.language_spinner
            adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf(l("Systemsprache"),"Deutsch","English","Français","Español"))
            setSelection(languageTags.indexOf(LanguageContext.selected(this@MainActivity)).coerceAtLeast(0))
        }
        content.addView(language,LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(text("Seitenansicht", 14f, muted, true))
        val modes = RadioGroup(this)
        val continuous = RadioButton(this).apply { id = View.generateViewId(); text = l("Fortlaufend · vertikal scrollen"); setTextColor(ink) }
        val single = RadioButton(this).apply { id = View.generateViewId(); text = l("Einzelseiten · horizontal wischen"); setTextColor(ink) }
        modes.addView(continuous); modes.addView(single)
        modes.check(if (prefs.getBoolean("single", false)) single.id else continuous.id)
        content.addView(modes)
        fun toggle(label: String, key: String): Switch = Switch(this).apply {
            text = l(label); setTextColor(ink); minHeight = dp(52); isChecked = prefs.getBoolean(key, false); content.addView(this)
        }
        val fit = toggle("Ganze Seite einpassen", "fit")
        val theme = toggle("Dunkle Oberfläche", "dark")
        val pages = toggle("PDF-Farben für die Nacht invertieren", "dark_pages")
        val awake = toggle("Bildschirm beim Lesen wach halten", "awake")
        val serif = toggle("Serifenschrift im Textmodus", "serif")
        content.addView(text("Schriftgröße im Textmodus", 14f, muted))
        val fontLabel = text("${prefs.getInt("font", 20)} sp", 14f)
        content.addView(fontLabel)
        val font = SeekBar(this).apply {
            max = 20; progress = prefs.getInt("font", 20) - 14
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { fontLabel.text = "${p + 14} sp" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        content.addView(font)
        content.addView(text("Nachtansicht verändert auch Bildfarben.\nDokumente bleiben unverändert und auf diesem Gerät.", 12f, muted))
        val scroll = ScrollView(this).apply { addView(content) }
        LocalizedDialogBuilder(this).setTitle("Ansicht & Lesekomfort").setView(scroll)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Übernehmen") { _, _ ->
                persistPosition()
                val selectedLanguage = languageTags[language.selectedItemPosition]
                val languageChanged = selectedLanguage != LanguageContext.selected(this)
                LanguageContext.select(this,selectedLanguage)
                prefs.edit().putBoolean("single", modes.checkedRadioButtonId == single.id).putBoolean("fit", fit.isChecked)
                    .putBoolean("dark", theme.isChecked).putBoolean("dark_pages", pages.isChecked).putBoolean("awake", awake.isChecked)
                    .putBoolean("serif", serif.isChecked).putInt("font", font.progress + 14).apply()
                val themeChanged = dark != theme.isChecked
                dark = theme.isChecked
                val id = documentId
                if (languageChanged) recreate()
                else if (themeChanged) { if (id == null) showLibrary() else openDocument(id) }
                else applyReaderSettings()
            }.show()
    }

    private fun applyReaderSettings() {
        reader?.setReadingMode(if (prefs.getBoolean("single", false)) ReadingMode.SINGLE else ReadingMode.CONTINUOUS)
        reader?.setFitPage(prefs.getBoolean("fit", false))
        reader?.setDarkPages(prefs.getBoolean("dark_pages", false))
        reflowView?.setAppearance(dark, prefs.getInt("font", 20).toFloat(), prefs.getBoolean("serif", false))
        if (reader != null && prefs.getBoolean("awake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun currentDocument() = store.get(documentId ?: "")
    private fun viewingFile(doc: PdfDocument) = if (documentId == doc.id) unlocked?.file ?: store.file(doc) else store.file(doc)

    private fun handleOpenError(doc: PdfDocument, message: String) {
        if (unlocked != null) { error(message); showLibrary(); return }
        val progress = busy("PDF wird geprüft")
        io.execute {
            val passwordRequired = runCatching { tools.needsPassword(store.file(doc)) }.getOrDefault(false)
            handler.post {
                progress.dismiss()
                if (isDestroyed || documentId != doc.id) return@post
                if (passwordRequired) passwordDialog(doc) else { error(message); showLibrary() }
            }
        }
    }

    private fun passwordDialog(doc: PdfDocument) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = l("PDF-Passwort"); setPadding(dp(24),dp(12),dp(24),dp(12)) }
        val dialog = LocalizedDialogBuilder(this).setTitle("Passwortgeschützte PDF").setMessage("Das Passwort bleibt nur für diesen Vorgang im Arbeitsspeicher. Die Originalkopie bleibt verschlüsselt.").setView(input)
            .setNegativeButton("Abbrechen") { _, _ -> showLibrary() }.setPositiveButton("Entsperren", null).setCancelable(false).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text.toString()
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            io.execute {
                val result = runCatching { tools.unlockForViewing(store.file(doc), password) }
                handler.post {
                    button.isEnabled = true
                    if (isDestroyed || documentId != doc.id) { result.getOrNull()?.file?.delete(); return@post }
                    result.fold({ value ->
                        unlocked = value; dialog.dismiss(); input.text.clear()
                        reader?.textSelectionAllowed = value.canExtract
                        loadingView?.visibility = View.VISIBLE
                        reader?.open(value.file, doc.page, doc.offset, doc.zoom)
                    }, { input.error = l(it.message ?: "Passwort nicht akzeptiert") })
                }
            }
        } }
        dialog.show()
    }

    private fun setReflow(enabled: Boolean, offset: Float = 0f) {
        if (enabled && unlocked?.canExtract == false) { error("Der Autor erlaubt für diese PDF keine Textextraktion und keinen Reflow."); return }
        persistPosition()
        reflow = enabled
        reader?.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        reflowView?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) loadReflow(offset)
        else { textPage = -1; persistPosition() }
    }

    private fun loadReflow(offset: Float) {
        val doc = currentDocument() ?: return
        val page = reader?.currentPage ?: return
        textPage = page; reflowOffset = offset
        reflowLoading = true
        reflowView?.showText(l("Text wird geladen …"), page, reader?.pageCount ?: 0)
        textService.extract(viewingFile(doc), page, { extracted ->
            if (documentId == doc.id && reflow && textPage == page) {
                reflowView?.showText(extracted, page, reader?.pageCount ?: 0)
                reflowView?.restoreOffset(offset)
                reflowLoading = false
                loadingView?.visibility = View.GONE
                if (activeQuery.isNotEmpty()) {
                    val selectable = reflowView?.textView
                    val content = selectable?.text?.toString().orEmpty()
                    val highlighted = android.text.SpannableString(content)
                    Regex(Regex.escape(activeQuery), RegexOption.IGNORE_CASE).findAll(content).take(200).forEach { match ->
                        highlighted.setSpan(android.text.style.BackgroundColorSpan(0xffe7c94c.toInt()), match.range.first, match.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        highlighted.setSpan(android.text.style.ForegroundColorSpan(Color.BLACK), match.range.first, match.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    selectable?.text = highlighted
                }
            }
        }, { message -> if (documentId == doc.id && reflow) { reflowLoading = false; loadingView?.visibility = View.GONE; reflowView?.showText(l(message), page, reader?.pageCount ?: 0) } })
    }

    private fun searchDialog() {
        val doc = currentDocument() ?: return
        if (unlocked?.canExtract == false) { error("Der Autor erlaubt keine Textextraktion für die Suche."); return }
        val input = EditText(this).apply { hint = l("Suchbegriff"); setSingleLine(); setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val dialog = LocalizedDialogBuilder(this).setTitle("Im Dokument suchen").setView(input).setNegativeButton("Abbrechen", null).setPositiveButton("Suchen", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.length < 2) { input.error = l("Mindestens zwei Zeichen"); return@setOnClickListener }
                dialog.dismiss()
                val progress = LocalizedDialogBuilder(this).setTitle("Dokument wird durchsucht")
                    .setMessage("Groß-/Kleinschreibung wird ignoriert.")
                    .setNegativeButton("Abbrechen") { _, _ -> textService.cancelSearch(); searchProgress = null }
                    .setCancelable(true).create()
                searchProgress = progress
                progress.setOnCancelListener { textService.cancelSearch(); searchProgress = null }
                progress.show()
                var lastSearchUiUpdate = 0L
                textService.search(viewingFile(doc), query, { hits ->
                    progress.dismiss(); searchProgress = null
                    if (documentId != doc.id || isDestroyed) return@search
                    if (hits.isEmpty()) error("Keine Treffer. Gescannte PDFs benötigen zunächst OCR.")
                    else LocalizedDialogBuilder(this).setTitle("${hits.size} Treffer").setItems(hits.map { "Seite ${it.page + 1}\n${it.snippet}" }.toTypedArray()) { _, index -> activeQuery = query; reader?.goToPage(hits[index].page); if (!reflow) setReflow(true) else loadReflow(0f) }.setNegativeButton("Schließen", null).show()
                }, { message -> progress.dismiss(); searchProgress = null; error(message) }, onProgress = { page, total ->
                    val now = android.os.SystemClock.uptimeMillis()
                    // Keep progress readable and avoid flooding accessibility with text events.
                    if (searchProgress === progress && (now-lastSearchUiUpdate >= 1200 || page == total)) {
                        lastSearchUiUpdate = now
                        progress.setMessage(l("Groß-/Kleinschreibung wird ignoriert.\nSeite $page von $total"))
                    }
                })
            }
        }
        dialog.show()
    }

    private fun outlineDialog() {
        val doc = currentDocument() ?: return
        val progress = busy("Inhaltsverzeichnis wird geladen")
        textService.outline(viewingFile(doc), { entries ->
            progress.dismiss()
            if (documentId != doc.id || isDestroyed) return@outline
            if (entries.isEmpty()) error("Diese PDF enthält kein eingebettetes Inhaltsverzeichnis.")
            else {
                closeOutline()
                val panel = OutlinePanel(this,dark,entries,reader?.currentPage ?: 0,
                    { page -> closeOutline(); reader?.goToPage(page) }, { closeOutline() })
                for (index in 0 until root.childCount) root.getChildAt(index).visibility = View.GONE
                outlinePanel = panel
                root.addView(panel,LinearLayout.LayoutParams(-1,-1))
                WindowCompat.getInsetsController(window,window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }, { message -> progress.dismiss(); error(message) })
    }

    private fun busy(title: String): AlertDialog = LocalizedDialogBuilder(this).setTitle(title).setMessage("Bitte einen Moment warten …").setCancelable(false).create().apply { show() }

    private fun closeOutline() {
        val panel = outlinePanel ?: return
        root.removeView(panel); outlinePanel = null
        for(index in 0 until root.childCount) root.getChildAt(index).visibility = View.VISIBLE
        topBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        bottomBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        if (!chromeVisible) WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun screenshotDocument() {
        val source = (if (reflow) reflowView else reader) ?: return
        if(source.width <= 0 || source.height <= 0) return
        val bitmap = android.graphics.Bitmap.createBitmap(source.width,source.height,android.graphics.Bitmap.Config.ARGB_8888)
        source.draw(android.graphics.Canvas(bitmap))
        io.execute {
            val result = runCatching { File(cacheDir,"exports").apply { mkdirs() }.let { dir -> File.createTempFile("PDFCraft-Seite-",".png",dir).also { file -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } } }
            bitmap.recycle()
            handler.post { if (!isDestroyed) result.fold({ file -> share(file,"image/png") }, { error("Screenshot fehlgeschlagen") }) }
        }
    }

    private fun runTool(name: String, task: () -> File) {
        val progress = busy("PDF wird verarbeitet")
        io.execute {
            val result = runCatching(task)
            handler.post {
                progress.dismiss()
                if (!isDestroyed) result.fold({ showExport(it, name) }, { error(it.message ?: "Verarbeitung fehlgeschlagen") })
            }
        }
    }

    private fun showExport(file: File, name: String) {
        val warning = if (name.endsWith("-geschuetzt.pdf")) "Die Kopie ist passwortgeschützt; Textextraktion und Bearbeitung sind gesperrt." else "Die bearbeitete Kopie wird ohne ursprüngliche Verschlüsselung gespeichert. Vorhandene digitale Signaturen können ungültig werden. Seitenentnahme ist keine sichere Schwärzung."
        LocalizedDialogBuilder(this).setTitle("Neue PDF ist fertig").setMessage("$name\nDas Original bleibt unverändert.\n\n$warning")
            .setPositiveButton("Speichern") { _, _ -> pendingExport = file; exportName = name; exportPicker.launch(name) }
            .setNeutralButton("Öffnen") { _, _ ->
                val progress = busy("Kopie wird übernommen")
                io.execute {
                    val result = runCatching { store.importDocument(Uri.fromFile(file)).also { store.rename(it.id, name) } }
                    handler.post { progress.dismiss(); if (!isDestroyed) result.fold({ openDocument(it.id) }, { error(it.message ?: "Import fehlgeschlagen") }) }
                }
            }.setNegativeButton("Schließen", null).show()
    }

    private fun pageSelection(title: String, action: (List<Int>) -> Unit) {
        val count = reader?.pageCount ?: return
        val input = EditText(this).apply { hint = l("Zum Beispiel 1-3, 5"); setText("${(reader?.currentPage ?: 0) + 1}"); setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val dialog = LocalizedDialogBuilder(this).setTitle(title).setView(input).setNegativeButton("Abbrechen", null).setPositiveButton("Weiter", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val result = runCatching { PageSelection.parse(input.text.toString(), count) }
            result.fold({ dialog.dismiss(); action(it) }, { input.error = l(it.message.orEmpty()) })
        } }
        dialog.show()
    }

    private fun toolsDialog() {
        val doc = currentDocument() ?: return
        if (unlocked != null && unlocked?.owner != true) { error("Diese entsperrte PDF kann gelesen werden. Bearbeitung ist hier nur nach Entsperren mit dem Eigentümerpasswort verfügbar."); return }
        val source = viewingFile(doc)
        val base = doc.name.removeSuffix(".pdf")
        val labels=listOf("Seiten drehen", "Seiten extrahieren / neu ordnen", "Seiten löschen", "PDFs zusammenführen", "PDF aufteilen", "Notiz hinzufügen", "Formular ausfüllen", "Mit Passwort schützen", "Text markieren", "Handschriftlich unterschreiben", "Titel und Autor ändern")
        val icons=listOf("rotate","extract","delete","merge","split","notes","form","lock","highlight","signature","info")
        ReaderActionSheet(this,dark,labels.mapIndexed { index,label -> ReaderAction(index.toString(),label,icons[index],index==2) },title="PDF-Werkzeuge · sichere Kopien",subtitle=getString(R.string.ui_tools_copy_hint)) { action ->
            val index=action.toInt()
            when (index) {
                0 -> pageSelection("Welche Seiten drehen?") { pages ->
                    LocalizedDialogBuilder(this).setTitle("Drehung").setItems(arrayOf("90° nach rechts", "180°", "90° nach links")) { _, which -> runTool("$base-gedreht.pdf") { tools.rotate(source, pages, arrayOf(90,180,270)[which]) } }.show()
                }
                1 -> pageSelection("Seiten in gewünschter Reihenfolge") { pages -> runTool("$base-auszug.pdf") { tools.extract(source, pages) } }
                2 -> pageSelection("Seiten aus der Kopie löschen") { pages -> runTool("$base-gekuerzt.pdf") { tools.deletePages(source, pages) } }
                3 -> mergeDialog()
                4 -> splitDialog(doc)
                5 -> textInput("Notiz auf Seite ${(reader?.currentPage ?: 0) + 1}", "Notiztext") { value -> val page = reader?.currentPage ?: 0; runTool("$base-notiz.pdf") { tools.addNote(source, page, value) } }
                6 -> formsDialog(doc)
                7 -> textInput("Passwortschutz · Lesen/Drucken erlaubt, Textextraktion und Bearbeitung gesperrt", "Passwort", true) { password -> runTool("$base-geschuetzt.pdf") { tools.protect(source, password) } }
                8 -> markupDialog()
                9 -> signatureDialog(source, base)
                10 -> textInput("Dokumenttitel ändern", "Neuer Titel") { title -> textInput("Autor ändern", "Autor") { author -> runTool("$base-metadaten.pdf") { tools.setMetadata(source, title, author) } } }
            }
        }.show()
    }

    private fun markupDialog(selectedText:String="",exact:Pair<Int,PdfTextSelection>?=null) {
        val doc=currentDocument() ?: return
        if(unlocked!=null && unlocked?.owner!=true) { error("Bearbeitung ist nur mit dem Eigentümerpasswort verfügbar.");return }
        val source=viewingFile(doc)
        val page=exact?.first ?: reader?.currentPage ?: 0
        val savedStyle=runCatching { TextMarkupStyle.valueOf(prefs.getString("markup_style","HIGHLIGHT")!!) }.getOrDefault(TextMarkupStyle.HIGHLIGHT)
        TextMarkupDialog(this,dark,page,selectedText,prefs.getInt("markup_color",MarkupColors.values[0]),savedStyle,exactSelection=exact!=null) { query,color,style ->
            prefs.edit().putInt("markup_color",color).putString("markup_style",style.name).apply()
            runTool("${doc.name.removeSuffix(".pdf")}-markiert.pdf") {
                if(exact!=null) markup.markSelection(source,page,exact.second.text,exact.second.pdfQuads,color,style)
                else markup.markText(source,page,query,color,style)
            }
        }.show()
    }

    private fun textInput(title: String, hint: String, password: Boolean = false, rawHint: Boolean = false, action: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = if (rawHint) hint else l(hint); setPadding(dp(24), dp(12), dp(24), dp(12)); if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val dialog = LocalizedDialogBuilder(this).setTitle(title).setView(input).setNegativeButton("Abbrechen", null).setPositiveButton("Weiter", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val raw = input.text.toString(); val value = if (password) raw else raw.trim(); if (value.isEmpty()) input.error = l("Bitte einen Wert eingeben") else { dialog.dismiss(); action(value) } } }
        dialog.show()
    }

    private fun mergeDialog() {
        val docs = store.list()
        if (docs.size < 2) { error("Öffne zuerst mindestens zwei PDFs in der Bibliothek."); return }
        val selected = BooleanArray(docs.size)
        val dialog = AlertDialog.Builder(this).setTitle(l("PDFs in Bibliotheks-Reihenfolge kombinieren")).setMultiChoiceItems(docs.map { it.name }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton(l("Abbrechen"), null).setPositiveButton(l("Kombinieren"), null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val files = docs.filterIndexed { index, _ -> selected[index] }.map { store.file(it) }
            if (files.size < 2) Toast.makeText(this, l("Mindestens zwei PDFs auswählen"), Toast.LENGTH_SHORT).show()
            else { dialog.dismiss(); runTool("Kombiniert.pdf") { tools.merge(files) } }
        } }
        dialog.show()
    }

    private fun splitDialog(doc: PdfDocument) {
        textInput("Aufteilen: Seiten je Teil", "Zum Beispiel 10") { value ->
            val size = value.toIntOrNull()
            val count = reader?.pageCount ?: 0
            if (size == null || size !in 1..count) { error("Bitte eine Zahl von 1 bis $count eingeben."); return@textInput }
            if ((count + size - 1) / size > 100) { error("Maximal 100 Teile je Vorgang. Bitte größere Teile wählen."); return@textInput }
            val progress = busy("PDF wird in lokale Kopien aufgeteilt")
            io.execute {
                val result = runCatching {
                    (0 until count step size).forEachIndexed { index, start ->
                        val file = tools.extract(viewingFile(doc), (start until minOf(count, start + size)).toList())
                        val imported = store.importDocument(Uri.fromFile(file))
                        store.rename(imported.id, "${doc.name.removeSuffix(".pdf")}-Teil-${index + 1}.pdf")
                    }
                }
                handler.post { progress.dismiss(); if (!isDestroyed) result.fold({ showLibrary() }, { error(it.message ?: "Aufteilen fehlgeschlagen; bereits erstellte Teile bleiben in der Bibliothek.") }) }
            }
        }
    }

    private fun formsDialog(doc: PdfDocument) {
        val progress = busy("Formularfelder werden geladen")
        io.execute {
            val result = runCatching { tools.forms(viewingFile(doc)) }
            handler.post {
                progress.dismiss()
                if (isDestroyed) return@post
                result.fold({ fields ->
                    if (fields.isEmpty()) { error("Keine unterstützten Formularfelder in dieser PDF."); return@fold }
                    val inputs = linkedMapOf<String, EditText>()
                    val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
                    fields.forEach { field ->
                        body.addView(text("${field.name} · ${field.type}", 13f, muted))
                        val input = EditText(this).apply { setText(field.value); setTextColor(ink) }
                        inputs[field.name] = input; body.addView(input)
                    }
                    LocalizedDialogBuilder(this).setTitle("Formular in neuer Kopie ausfüllen").setView(ScrollView(this).apply { addView(body) }).setNegativeButton("Abbrechen", null).setPositiveButton("Kopie erstellen") { _, _ -> runTool("${doc.name.removeSuffix(".pdf")}-ausgefuellt.pdf") { tools.fillForms(viewingFile(doc), inputs.mapValues { it.value.text.toString() }) } }.show()
                }, { error(it.message ?: "Formular konnte nicht geladen werden") })
            }
        }
    }

    private fun propertiesDialog() {
        val doc = currentDocument() ?: return
        val progress = busy("Dokumentinformationen")
        io.execute {
            val result = runCatching { tools.properties(viewingFile(doc)) }
            handler.post { progress.dismiss(); if (!isDestroyed) result.fold({ p -> LocalizedDialogBuilder(this).setRawTitle(doc.name).setMessage("Titel: ${p.title.ifEmpty { "—" }}\nAutor: ${p.author.ifEmpty { "—" }}\nSeiten: ${p.pages}\nPDF-Version: ${p.version}\nVerschlüsselt: ${if (p.encrypted || unlocked != null) "Ja" else "Nein"}\nDateigröße: ${store.file(doc).length() / 1024} KiB\n\nPrivate Kopie auf diesem Gerät.").setPositiveButton("OK", null).show() }, { error(it.message ?: "Informationen nicht verfügbar") }) }
        }
    }

    private fun share(file: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = android.content.ClipData.newRawUri("PDF", uri) }, l("PDF teilen")))
    }

    private fun printDocument() {
        val doc = currentDocument() ?: return
        if (unlocked?.canPrint == false) { error("Der Autor erlaubt das Drucken dieser PDF nicht."); return }
        val manager = getSystemService(PRINT_SERVICE) as android.print.PrintManager
        manager.print(doc.name, PdfPrintAdapter(this, viewingFile(doc), doc.name, reader?.pageCount ?: doc.pageCount), null)
    }

    private fun notesDialog() {
        val doc = currentDocument() ?: return
        val file = viewingFile(doc)
        val progress = busy("Notizen werden geladen")
        io.execute {
            val result = runCatching { tools.notes(file) }
            handler.post {
                progress.dismiss()
                if (isDestroyed || documentId != doc.id) return@post
                result.fold({ notes ->
                    if (notes.isEmpty()) error("Diese PDF enthält keine lesbaren Notizen.")
                    else LocalizedDialogBuilder(this).setTitle("Notizen").setItems(notes.map { "Seite ${it.page + 1} · ${it.text}" }.toTypedArray()) { _, index ->
                        reader?.goToPage(notes[index].page)
                        LocalizedDialogBuilder(this).setRawMessage(notes[index].text).setTitle("Notiz · Seite ${notes[index].page + 1}").setPositiveButton("OK", null).show()
                    }.setNegativeButton("Schließen", null).show()
                }, { error(it.message ?: "Notizen nicht verfügbar") })
            }
        }
    }

    private fun signatureDialog(source: File, base: String) {
        val pad = SignaturePadView(this)
        val body = column().apply {
            setPadding(dp(16),dp(12),dp(16),dp(12))
            addView(text("Mit dem Finger unterschreiben. Die sichtbare Unterschrift wird unten rechts auf der aktuellen Seite eingefügt. Keine digitale/kryptografische Signatur.", 13f, muted))
            addView(pad, LinearLayout.LayoutParams(-1,dp(220)))
            addView(button("Neu zeichnen") { pad.clear() })
        }
        LocalizedDialogBuilder(this).setTitle("Handschriftliche Unterschrift").setView(body).setNegativeButton("Abbrechen", null).setPositiveButton("Kopie erstellen") { _, _ ->
            val strokes = pad.strokes(); val page = reader?.currentPage ?: 0
            runTool("$base-unterschrieben.pdf") { markup.addSignature(source, page, strokes) }
        }.show()
    }

    private fun error(message: String) {
        if (!isFinishing && !isDestroyed) LocalizedDialogBuilder(this).setTitle("PDFCraft").setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onPause() { persistPosition(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { persistPosition(); outState.putString("document", documentId); super.onSaveInstanceState(outState) }
    override fun onDestroy() { persistPosition(); importGeneration++; searchProgress?.dismiss(); handler.removeCallbacksAndMessages(null); reader?.close(); unlocked?.file?.delete(); thumbnails.close(); textService.close(); io.shutdownNow(); super.onDestroy() }
}
