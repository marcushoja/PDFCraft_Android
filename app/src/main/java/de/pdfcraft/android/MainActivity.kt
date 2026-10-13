package de.pdfcraft.android

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import java.util.concurrent.Executors

/** A small, offline-first smartphone UI. All documents are private imported copies. */
class MainActivity : ComponentActivity() {
    private lateinit var store: DocumentStore
    private lateinit var thumbnails: ThumbnailLoader
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("reader_settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var reader: PdfReaderView? = null
    private var documentId: String? = null
    private var chromeVisible = true
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var pageLabel: TextView? = null
    private var bookmarkButton: TextView? = null
    private var pageSeek: SeekBar? = null
    private var importing = false
    private var lastChromePage = -1
    private var dark = false
    private val ink get() = if (dark) Color.rgb(236,239,232) else Color.rgb(30,46,41)
    private val muted get() = if (dark) Color.rgb(166,180,170) else Color.rgb(105,119,111)
    private val paper get() = if (dark) Color.rgb(26,33,30) else Color.rgb(246,245,240)
    private val card get() = if (dark) Color.rgb(38,48,43) else Color.WHITE
    private val accent get() = if (dark) Color.rgb(147,211,183) else Color.rgb(36,107,91)
    private val savePosition = Runnable { persistPosition() }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (reader != null) showLibrary() else finish()
            }
        })
        store = DocumentStore(this)
        thumbnails = ThumbnailLoader(this)
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
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
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
        contentDescription = label; setOnClickListener { action() }
    }
    private fun gap(size: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }
    private fun newRoot() {
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else R.style.AppTheme)
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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (!dark) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun showLibrary() {
        persistPosition()
        reader?.close(); reader = null; documentId = null
        handler.removeCallbacks(savePosition)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        newRoot()
        val header = row().apply { setPadding(dp(24), dp(18), dp(16), dp(10)) }
        val title = column().apply {
            addView(text("PDFCraft", 26f, ink, true))
            addView(text("Dein Platz zum Lesen.", 13f, muted))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Einstellungen") { settings() })
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = column().apply { setPadding(dp(24), dp(16), dp(24), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val docs = store.list()
        val hero = column().apply {
            background = shape(if (dark) Color.rgb(40,66,55) else Color.rgb(224,237,227), 24)
            setPadding(dp(22), dp(24), dp(22), dp(22))
        }
        hero.addView(text(if (docs.isEmpty()) "Ein gutes Dokument.\nEin ruhiger Moment." else "Deine nächste Seite\nwartet schon.", 27f, ink, true))
        hero.addView(gap(12))
        hero.addView(text("PDFs lesen. Offline, ohne Konto.\nDeine Lesestelle bleibt gespeichert.", 15f, muted))
        hero.addView(gap(18))
        hero.addView(button("+  PDF öffnen") { pickPdf() })
        body.addView(hero)
        body.addView(gap(28))
        if (docs.isEmpty()) {
            body.addView(text("Deine Bibliothek", 20f, ink, true))
            body.addView(gap(12))
            body.addView(text("Noch nichts hier — öffne eine PDF aus deinen Dateien oder teile sie aus einer anderen App mit PDFCraft.", 16f, muted))
            body.addView(gap(24))
            body.addView(text("Nur lokale Kopien · Originale bleiben unverändert", 12f, muted))
        } else {
            body.addView(text("Weiterlesen", 20f, ink, true))
            body.addView(gap(12))
            body.addView(documentCard(docs.first(), true))
            body.addView(gap(24))
            body.addView(text("Bibliothek  ·  ${docs.size}", 20f, ink, true))
            docs.forEach { doc -> body.addView(gap(12)); body.addView(documentCard(doc, false)) }
        }
    }

    private fun documentCard(doc: PdfDocument, featured: Boolean): View {
        val container = row().apply {
            background = shape(card); setPadding(dp(14), dp(14), dp(8), dp(14))
            isClickable = true; isFocusable = true; contentDescription = "${doc.name}, weiterlesen"
            setOnClickListener { openDocument(doc.id) }
        }
        val thumb = ImageView(this).apply { setBackgroundColor(Color.rgb(233,234,229)) }
        container.addView(thumb, LinearLayout.LayoutParams(dp(if (featured) 72 else 52), dp(if (featured) 98 else 72)))
        thumbnails.load(store.file(doc), if (featured) doc.page else 0, dp(100), thumb)
        val info = column().apply { setPadding(dp(16), 0, dp(4), 0) }
        info.addView(text(doc.name, if (featured) 18f else 16f, ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(gap(6))
        info.addView(text(if (doc.pageCount > 0) "Seite ${doc.page + 1} von ${doc.pageCount}" else "Bereit zum Lesen", 13f, muted))
        if (doc.pageCount > 0) {
            info.addView(gap(8))
            info.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = doc.pageCount; progress = doc.page + 1
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(-1, dp(4)))
        }
        container.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(button("···") { documentMenu(doc) }.apply { contentDescription = "Dokumentoptionen für ${doc.name}" })
        return container
    }

    private fun documentMenu(doc: PdfDocument) {
        AlertDialog.Builder(this).setTitle(doc.name).setItems(arrayOf("Öffnen", "Aus Bibliothek entfernen")) { _, which ->
            if (which == 0) openDocument(doc.id)
            else AlertDialog.Builder(this).setTitle("Lokale Kopie entfernen?")
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
        val progress = AlertDialog.Builder(this).setTitle("PDF wird geöffnet")
            .setMessage("Eine lokale Kopie wird in deine Bibliothek übernommen …").setCancelable(false).create()
        progress.show()
        io.execute {
            val result = runCatching { store.importDocument(uri) }
            handler.post {
                importing = false
                if (isFinishing || isDestroyed) return@post
                progress.dismiss()
                result.fold({ openDocument(it.id) }, { error(it.message ?: "Die PDF konnte nicht geöffnet werden.") })
            }
        }
    }

    private fun openDocument(id: String) {
        val doc = store.get(id) ?: return
        persistPosition(); reader?.close(); handler.removeCallbacks(savePosition)
        documentId = id; store.markOpened(id); chromeVisible = true
        lastChromePage = -1
        positionPage = doc.page; positionOffset = doc.offset; positionZoom = doc.zoom
        newRoot()
        val top = row().apply { setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(paper) }
        top.addView(button("‹") { showLibrary() }.apply { contentDescription = "Zur Bibliothek" })
        top.addView(text(doc.name, 16f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        bookmarkButton = button("☆") { reader?.let { store.toggleBookmark(id, it.currentPage); lastChromePage = -1; updateReaderChrome() } }.apply { contentDescription = "Lesezeichen setzen oder entfernen" }
        top.addView(bookmarkButton)
        top.addView(button("···") { readerMenu() }.apply { contentDescription = "Leseoptionen" })
        topBar = top; root.addView(top)
        val view = PdfReaderView(this)
        reader = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = column().apply { setPadding(dp(12), dp(4), dp(12), dp(8)); setBackgroundColor(paper) }
        pageSeek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = "Seite wählen"
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
        nav.addView(button("‹") { view.goToPage(view.currentPage - 1) }.apply { contentDescription = "Vorherige Seite" })
        pageLabel = button("…") { jumpDialog() }.apply { contentDescription = "Seitenzahl, zu Seite springen" }
        nav.addView(pageLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(button("Seiten") { pagesDialog(false) })
        nav.addView(button("›") { view.goToPage(view.currentPage + 1) }.apply { contentDescription = "Nächste Seite" })
        bottom.addView(nav); bottomBar = bottom; root.addView(bottom)
        view.onDocumentLoaded = { count ->
            pageSeek?.max = (count - 1).coerceAtLeast(0)
            applyReaderSettings()
            updateReaderChrome()
        }
        view.onPositionChanged = { page, offset, zoom ->
            positionPage = page; positionOffset = offset; positionZoom = zoom
            updateReaderChrome(); handler.removeCallbacks(savePosition); handler.postDelayed(savePosition, 350)
        }
        view.onTap = { toggleChrome() }
        view.onError = { message -> error(message); showLibrary() }
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
        store.updateProgress(id, positionPage, positionOffset, positionZoom, view.pageCount)
    }

    private fun updateReaderChrome() {
        val view = reader ?: return
        if (lastChromePage == view.currentPage) return
        lastChromePage = view.currentPage
        pageLabel?.text = "${view.currentPage + 1} / ${view.pageCount}"
        if (pageSeek?.isPressed != true) pageSeek?.progress = view.currentPage
        bookmarkButton?.text = if (store.get(documentId ?: "")?.bookmarks?.contains(view.currentPage) == true) "★" else "☆"
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        topBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        bottomBar?.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chromeVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun jumpDialog() {
        val view = reader ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 bis ${view.pageCount}"; setText("${view.currentPage + 1}"); selectAll()
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Zu Seite springen").setView(input)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Öffnen", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..view.pageCount) input.error = "Bitte eine Seite zwischen 1 und ${view.pageCount} eingeben"
                else { view.goToPage(page - 1); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun readerMenu() {
        AlertDialog.Builder(this).setTitle("Lesen").setItems(arrayOf("Ansicht & Lesekomfort", "Meine Lesezeichen", "Seitenübersicht", "Zoom zurücksetzen", "Bibliothek")) { _, index ->
            when (index) { 0 -> settings(); 1 -> pagesDialog(true); 2 -> pagesDialog(false); 3 -> reader?.resetZoom(); 4 -> showLibrary() }
        }.show()
    }

    private fun pagesDialog(bookmarksOnly: Boolean) {
        val doc = store.get(documentId ?: "") ?: return
        val view = reader ?: return
        val pages = if (bookmarksOnly) doc.bookmarks else (0 until view.pageCount).toList()
        if (pages.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Meine Lesezeichen").setMessage("Tippe beim Lesen auf ☆, um eine Seite zu merken.").setPositiveButton("OK", null).show()
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
                thumbnails.load(store.file(doc), pages[position], dp(120), cell.getChildAt(0) as ImageView)
                return cell
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (bookmarksOnly) "Meine Lesezeichen" else "Seitenübersicht")
            .setView(grid).setNegativeButton("Schließen", null).create()
        grid.setOnItemClickListener { _, _, index, _ -> view.goToPage(pages[index]); dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }

    private fun settings() {
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        content.addView(text("Seitenansicht", 14f, muted, true))
        val modes = RadioGroup(this)
        val continuous = RadioButton(this).apply { id = View.generateViewId(); text = "Fortlaufend · vertikal scrollen"; setTextColor(ink) }
        val single = RadioButton(this).apply { id = View.generateViewId(); text = "Einzelseiten · horizontal wischen"; setTextColor(ink) }
        modes.addView(continuous); modes.addView(single)
        modes.check(if (prefs.getBoolean("single", false)) single.id else continuous.id)
        content.addView(modes)
        fun toggle(label: String, key: String): Switch = Switch(this).apply {
            text = label; setTextColor(ink); minHeight = dp(52); isChecked = prefs.getBoolean(key, false); content.addView(this)
        }
        val fit = toggle("Ganze Seite einpassen", "fit")
        val theme = toggle("Dunkle Oberfläche", "dark")
        val pages = toggle("PDF-Farben für die Nacht invertieren", "dark_pages")
        val awake = toggle("Bildschirm beim Lesen wach halten", "awake")
        content.addView(text("Nachtansicht verändert auch Bildfarben.\nDokumente bleiben unverändert und auf diesem Gerät.", 12f, muted))
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Ansicht & Lesekomfort").setView(scroll)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Übernehmen") { _, _ ->
                persistPosition()
                prefs.edit().putBoolean("single", modes.checkedRadioButtonId == single.id).putBoolean("fit", fit.isChecked)
                    .putBoolean("dark", theme.isChecked).putBoolean("dark_pages", pages.isChecked).putBoolean("awake", awake.isChecked).apply()
                val themeChanged = dark != theme.isChecked
                dark = theme.isChecked
                val id = documentId
                if (themeChanged) { if (id == null) showLibrary() else openDocument(id) }
                else applyReaderSettings()
            }.show()
    }

    private fun applyReaderSettings() {
        reader?.setReadingMode(if (prefs.getBoolean("single", false)) ReadingMode.SINGLE else ReadingMode.CONTINUOUS)
        reader?.setFitPage(prefs.getBoolean("fit", false))
        reader?.setDarkPages(prefs.getBoolean("dark_pages", false))
        if (reader != null && prefs.getBoolean("awake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun error(message: String) {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("PDFCraft").setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onPause() { persistPosition(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { persistPosition(); outState.putString("document", documentId); super.onSaveInstanceState(outState) }
    override fun onDestroy() { persistPosition(); handler.removeCallbacksAndMessages(null); reader?.close(); thumbnails.close(); io.shutdownNow(); super.onDestroy() }
}
