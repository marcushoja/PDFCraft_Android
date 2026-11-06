package de.pdfcraft.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Compact, searchable cover library with MJ-PDF-inspired navigation. */
class LibraryView(
    context: Context, private val store: DocumentStore, private val thumbnails: ThumbnailLoader,
    private val dark: Boolean, private val onOpen: (PdfDocument) -> Unit,
    private val onOptions: (PdfDocument) -> Unit, private val onImport: () -> Unit,
    private val onSettings: () -> Unit,
    private val onScan: (() -> Unit)?=null,
    private val onSupport:(() -> Unit)?=null,
    private val onToggleDark: (() -> Unit)? = null,
    initialFolderId: String? = null,
    private val onFolderChanged: (String?) -> Unit = {},
) : LinearLayout(context) {
    private val ink = ZenColors.of(dark).ink
    private val muted = ZenColors.of(dark).muted
    private val accent = ZenColors.of(dark).accent
    private val surface = ZenColors.of(dark).card
    private val tint = ZenColors.of(dark).tint
    private var all = store.list()
    private var folders = store.folders()
    private var folderId = initialFolderId?.takeIf { id -> folders.any { it.id == id } }
    private val selected = mutableSetOf<String>()
    private val folderDialogs = FolderDialogs(context, store) { selected.clear(); refresh() }
    private val folderRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val breadcrumbs = LinearLayout(context)
    private val selectionBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val folderScroll = ScrollView(context).apply { addView(folderRows) }
    private var shown = all
    private var tab = 1
    private var query = ""
    private lateinit var searchInput: EditText
    private val preferences = context.getSharedPreferences("library_settings", Context.MODE_PRIVATE)
    private var listMode = preferences.getBoolean("list", false)
    private var sortOrder = preferences.getInt("sort", 0).coerceIn(0, 3)
    private lateinit var grid: GridView
    private val updateContainer = FrameLayout(context)
    private val tabButtons = mutableListOf<TextView>()
    private val empty = label("Öffne deine erste PDF.\nDeine Lesestelle bleibt gespeichert.", 17f).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setTextColor(muted) }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val doc = shown[position]
            val cell = (convertView as? LinearLayout)?.takeIf { it.tag == listMode } ?: LinearLayout(context).apply {
                tag = listMode
                orientation = if (listMode) HORIZONTAL else VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(if (listMode) 4 else 10))
                val frame = FrameLayout(context)
                frame.background = rounded(surface, 14)
                frame.clipToOutline=true
                frame.addView(ImageView(context).apply {
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    background=rounded(surface,10)
                    clipToOutline=true
                },FrameLayout.LayoutParams(-1,-1).apply { setMargins(dp(5),dp(5),dp(5),dp(5)) })
                frame.addView(ImageView(context).apply {
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                    setImageDrawable(MaterialSymbols.drawable(context,"more",muted))
                    background=android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf((accent and 0x00ffffff) or 0x33000000),
                        android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),rounded(Color.WHITE,14))
                    contentDescription = UiText.t(context,"Dokumentoptionen")
                }, FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.TOP))
                addView(frame, LayoutParams(if (listMode) dp(48) else -1, dp(if (listMode) 64 else 174)))
                val details = LinearLayout(context).apply {
                    orientation = VERTICAL
                    if (listMode) setPadding(dp(12),0,dp(8),0)
                    addView(label("", if (listMode) 14f else 15f).apply {
                        maxLines = if (listMode) 1 else 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        minHeight = if (listMode) 0 else dp(44); setPadding(dp(2),dp(if (listMode) 0 else 5),dp(2),0)
                    })
                    addView(label("", 12f).apply { setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(label("", 12f).apply { setTextColor(muted); visibility = if (listMode) VISIBLE else GONE })
                }
                addView(details, if (listMode) LayoutParams(0,-2,1f) else LayoutParams(-1,-2))
                if (listMode) {
                    val options = frame.getChildAt(1)
                    frame.removeView(options)
                    addView(options, LayoutParams(dp(48),dp(48)))
                }
            }
            val frame = cell.getChildAt(0) as FrameLayout
            thumbnails.load(store.file(doc), 0, dp(if (listMode) 48 else 150), frame.getChildAt(0) as ImageView)
            (if (listMode) cell.getChildAt(2) else frame.getChildAt(1)).setOnClickListener {
                if (selected.isNotEmpty()) toggleSelection(doc.id) else onOptions(doc)
            }
            val details = cell.getChildAt(1) as LinearLayout
            (details.getChildAt(0) as TextView).text = doc.name.replace(Regex("\\.pdf$",RegexOption.IGNORE_CASE),"")
            val reading = UiText.t(context, if (doc.pageCount > 0) "Seite ${doc.page + 1} / ${doc.pageCount}" else "Noch nicht gelesen")
            (details.getChildAt(1) as TextView).text = if (query.isNotBlank() || tab != 1)
                reading + " · " + (LibraryFolders.path(folders, doc.folderId).joinToString(" › ") { it.name }.ifEmpty { UiText.t(context,"Bibliothek") }) else reading
            (details.getChildAt(2) as TextView).text = java.text.DateFormat.getDateInstance().format(java.util.Date(doc.addedAt))
            cell.contentDescription = UiText.t(context,"${doc.name}, weiterlesen")
            cell.isClickable = true; cell.isFocusable = true
            cell.isSelected = doc.id in selected
            cell.background = if (cell.isSelected) rounded(tint, 12) else null
            cell.setOnClickListener { if (selected.isNotEmpty()) toggleSelection(doc.id) else onOpen(doc) }
            cell.setOnLongClickListener { toggleSelection(doc.id); true }
            return cell
        }
    }

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(12),dp(12),dp(6)) }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(44),dp(44)).apply { marginEnd = dp(10) })
        header.addView(label(context.getString(R.string.app_name), 24f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(0,-2,1f))
        onToggleDark?.let { toggle ->
            header.addView(iconAction(if (dark) "sun" else "moon",
                if (dark) R.string.library_light_mode else R.string.library_dark_mode, toggle).apply {
                id = R.id.library_theme
                isSelected = dark
            })
        }
        header.addView(action("", context.getString(R.string.library_menu)) { onSettings() }.apply {
            id = R.id.library_menu
            setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"menu",accent),null,null,null)
        })
        addView(header)
        addView(updateContainer, LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), 0) })
        val search = EditText(context).apply {
            hint = UiText.t(context,"Bibliothek durchsuchen"); textSize = 16f; setTextColor(ink); setHintTextColor(muted); setSingleLine()
            background = rounded(tint, 28); setPadding(dp(22),dp(8),dp(16),dp(8)); contentDescription = UiText.t(context,"Bibliothek durchsuchen")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); refresh() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchInput = search
        addView(search, LayoutParams(-1,dp(54)).apply { setMargins(dp(16),dp(6),dp(16),dp(12)) })
        val tabs = LinearLayout(context).apply { setPadding(dp(12),0,dp(12),dp(8)) }
        listOf(UiText.t(context,"Zuletzt"), UiText.t(context,"Bibliothek"), UiText.t(context,"Lesezeichen"), context.getString(R.string.folder_all_pdfs)).forEachIndexed { index, title ->
            val button = action(title, title) { selected.clear(); tab = index; refresh() }
            tabButtons.add(button); tabs.addView(button, LayoutParams(0,dp(48),1f))
        }
        addView(tabs)
        addView(HorizontalScrollView(context).apply { addView(breadcrumbs); isHorizontalScrollBarEnabled = false })
        addView(folderScroll)
        addView(selectionBar)
        val viewOptions = LinearLayout(context).apply { gravity = Gravity.END; setPadding(dp(12),0,dp(12),0) }
        viewOptions.addView(iconAction(if (listMode) "pages" else "library_list", if (listMode) R.string.library_grid else R.string.library_list) {
            listMode = !listMode
            preferences.edit().putBoolean("list",listMode).apply()
            grid.numColumns = columnCount()
            grid.verticalSpacing = dp(if (listMode) 2 else 8)
            (viewOptions.getChildAt(0) as ImageView).apply {
                setImageDrawable(MaterialSymbols.drawable(context,if (listMode) "pages" else "library_list",accent))
                contentDescription = context.getString(if (listMode) R.string.library_grid else R.string.library_list)
                tooltipText = contentDescription
            }
            adapter.notifyDataSetChanged()
        })
        viewOptions.addView(iconAction("library_sort", R.string.library_sort) {
            val labels = arrayOf(R.string.library_name_ascending,R.string.library_name_descending,R.string.library_date_descending,R.string.library_date_ascending)
            android.app.AlertDialog.Builder(context).setTitle(R.string.library_sort)
                .setSingleChoiceItems(labels.map { context.getString(it) }.toTypedArray(),sortOrder) { dialog, index ->
                    sortOrder = index
                    preferences.edit().putInt("sort",index).apply()
                    tab = 1; refresh(); dialog.dismiss()
                }.setNegativeButton(R.string.support_close,null).show()
        })
        viewOptions.addView(action(context.getString(R.string.folder_create), context.getString(R.string.folder_create)) {
            folderDialogs.create(if (tab == 1) folderId else null) { folder ->
                tab = 1; folderId = folder.parentId; searchInput.setText(""); refresh()
            }
        }, LayoutParams(0, dp(48), 1f))
        addView(viewOptions)
        val frame = FrameLayout(context)
        grid = GridView(context).apply {
            numColumns = columnCount()
            horizontalSpacing = dp(8); verticalSpacing = dp(if (listMode) 2 else 8); setPadding(dp(12),dp(8),dp(12),dp(8))
            clipToPadding = false; adapter = this@LibraryView.adapter
            setRecyclerListener { cell ->
                val frame=(cell as? LinearLayout)?.getChildAt(0) as? FrameLayout
                (frame?.getChildAt(0) as? ImageView)?.let { thumbnails.cancel(it) }
            }
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1,-1))
        frame.addView(empty, FrameLayout.LayoutParams(-1,-1))
        addView(frame, LayoutParams(-1,0,1f))
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(8),dp(16),dp(12)) }
        val credits = LinearLayout(context).apply { orientation = VERTICAL }
        credits.addView(label("Von Marcus Hoja\nOffline · private Kopien", 12f).apply { setTextColor(ZenColors.of(dark).brown) })
        credits.addView(action("Buy me a coffee", "Buy me a coffee") {
            if (onSupport != null) onSupport.invoke() else SupportDialog.create(context).show()
        }.apply {
            textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0,0,0,0)
            compoundDrawablePadding = dp(4)
            setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"support",accent),null,null,null)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        })
        bottom.addView(credits,LayoutParams(0,-2,1f))
        onScan?.let { scan ->
            bottom.addView(ImageView(context).apply {
                setImageDrawable(MaterialSymbols.drawable(context,"camera",accent))
                setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(tint,18)
                contentDescription=context.getString(R.string.scan_title);isFocusable=true
                setOnClickListener { scan() }
            },LayoutParams(dp(48),dp(48)).apply { marginEnd=dp(8) })
        }
        bottom.addView(action("PDF öffnen", "PDF öffnen") { onImport() }.apply { background = rounded(tint, 18); compoundDrawablePadding = dp(8); setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"folder",accent),null,null,null) })
        addView(bottom)
        refresh()
    }

    internal fun showUpdate(release: AppRelease?, onDownload: () -> Unit, onDismiss: () -> Unit) {
        updateContainer.removeAllViews()
        if (release != null) updateContainer.addView(UpdateNoticeView(context, dark, release, onDownload, onDismiss),
            FrameLayout.LayoutParams(-1, -2))
    }

    private fun refresh() {
        all = store.list(); folders = store.folders()
        if (folderId != null && folders.none { it.id == folderId }) folderId = null
        onFolderChanged(folderId)
        selected.retainAll(all.map { it.id }.toSet())
        shown = all.filter { it.name.contains(query, ignoreCase = true) && (tab != 2 || it.bookmarks.isNotEmpty()) &&
            (query.isNotBlank() || tab != 1 || it.folderId == folderId) }
            .let { docs -> if (tab != 1) docs else when (sortOrder) {
                1 -> docs.sortedByDescending { it.name.lowercase() }
                2 -> docs.sortedByDescending { it.addedAt }
                3 -> docs.sortedBy { it.addedAt }
                else -> docs.sortedBy { it.name.lowercase() }
            } }
        empty.text = UiText.t(context, if (all.isEmpty()) "Öffne deine erste PDF.\nDeine Lesestelle bleibt gespeichert." else if (tab == 2 && query.isEmpty()) "Noch keine Lesezeichen.\nTippe beim Lesen auf das Lesezeichen-Symbol." else "Keine passenden Dokumente.")
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        breadcrumbs.removeAllViews(); folderRows.removeAllViews(); selectionBar.removeAllViews()
        val browsing = tab == 1 && query.isBlank()
        breadcrumbs.visibility = if (browsing) VISIBLE else GONE
        breadcrumbs.addView(action(UiText.t(context,"Bibliothek"), UiText.t(context,"Bibliothek")) { folderId = null; selected.clear(); refresh() })
        LibraryFolders.path(folders, folderId).forEach { folder ->
            breadcrumbs.addView(action("› ${folder.name}", folder.name, raw = true) { folderId = folder.id; selected.clear(); refresh() })
        }
        val children = folders.filter { it.parentId == folderId }.sortedBy { it.name.lowercase() }
        folderScroll.visibility = if (browsing && children.isNotEmpty()) VISIBLE else GONE
        folderScroll.layoutParams = LayoutParams(-1, dp(minOf(children.size, 3) * 52))
        if (browsing) children.forEach { folder ->
            folderRows.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(action("▸ ${folder.name}", folder.name, raw = true) { folderId = folder.id; selected.clear(); refresh() }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnLongClickListener { folderDialogs.options(folder); true }
                }, LayoutParams(0, dp(52), 1f))
                addView(action("⋮", context.getString(R.string.folder_options)) { folderDialogs.options(folder) })
            })
        }
        selectionBar.visibility = if (selected.isEmpty()) GONE else VISIBLE
        if (selected.isNotEmpty()) {
            selectionBar.addView(label(context.getString(R.string.folder_selected, selected.size), 14f), LayoutParams(0,-2,1f))
            selectionBar.addView(action(context.getString(R.string.folder_move), context.getString(R.string.folder_move)) {
                folderDialogs.moveDocuments(selected.toSet(), folderId)
            })
            selectionBar.addView(action(context.getString(R.string.folder_select_all), context.getString(R.string.folder_select_all)) {
                selected.addAll(shown.map { it.id }); refresh()
            })
            selectionBar.addView(action("×", context.getString(android.R.string.cancel)) { selected.clear(); refresh() })
        }
        tabButtons.forEachIndexed { index, view -> view.background = rounded(if (tab == index) tint else Color.TRANSPARENT,14); view.setTextColor(if (tab == index) accent else muted) }
        adapter.notifyDataSetChanged()
    }
    private fun toggleSelection(id: String) { if (!selected.add(id)) selected.remove(id); refresh() }

    fun handleBack(): Boolean {
        if (selected.isNotEmpty()) { selected.clear(); refresh(); return true }
        if (query.isNotEmpty()) { searchInput.setText(""); return true }
        if (tab == 1 && folderId != null) {
            folderId = folders.firstOrNull { it.id == folderId }?.parentId
            refresh(); return true
        }
        return false
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun columnCount() = if (listMode) 1 else if (resources.configuration.screenWidthDp < 420) 2 else 3
    private fun iconAction(key: String, description: Int, callback: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(MaterialSymbols.drawable(context,key,accent))
        contentDescription = context.getString(description); tooltipText = contentDescription
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tint),null,rounded(Color.WHITE,24))
        setPadding(dp(12),dp(12),dp(12),dp(12)); layoutParams = LayoutParams(dp(48),dp(48))
        setOnClickListener { callback() }
    }
    private fun label(value: String, size: Float) = TextView(context).apply { text = UiText.t(context,value); textSize = size; setTextColor(ink) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun action(value: String, description: String, raw: Boolean = false, callback: () -> Unit) = label(if (raw) "" else value, 14f).apply {
        if (raw) text = value
        gravity = Gravity.CENTER; minWidth = dp(48); minHeight = dp(48); setPadding(dp(12),0,dp(12),0)
        setTextColor(accent); contentDescription = if (raw) description else UiText.t(context,description); isFocusable = true; setOnClickListener { callback() }
    }
}
