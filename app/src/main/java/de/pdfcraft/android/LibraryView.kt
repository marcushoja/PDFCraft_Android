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
) : LinearLayout(context) {
    private val ink = ZenColors.of(dark).ink
    private val muted = ZenColors.of(dark).muted
    private val accent = ZenColors.of(dark).accent
    private val surface = ZenColors.of(dark).card
    private val tint = ZenColors.of(dark).tint
    private val all = store.list()
    private var shown = all
    private var tab = 0
    private var query = ""
    private val preferences = context.getSharedPreferences("library_settings", Context.MODE_PRIVATE)
    private var listMode = preferences.getBoolean("list", false)
    private var sortOrder = preferences.getInt("sort", 0).coerceIn(0, 3)
    private lateinit var grid: GridView
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
                        android.content.res.ColorStateList.valueOf(0x33507453),
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
            (if (listMode) cell.getChildAt(2) else frame.getChildAt(1)).setOnClickListener { onOptions(doc) }
            val details = cell.getChildAt(1) as LinearLayout
            (details.getChildAt(0) as TextView).text = doc.name.replace(Regex("\\.pdf$",RegexOption.IGNORE_CASE),"")
            (details.getChildAt(1) as TextView).text = UiText.t(context, if (doc.pageCount > 0) "Seite ${doc.page + 1} / ${doc.pageCount}" else "Noch nicht gelesen")
            (details.getChildAt(2) as TextView).text = java.text.DateFormat.getDateInstance().format(java.util.Date(doc.addedAt))
            cell.contentDescription = UiText.t(context,"${doc.name}, weiterlesen")
            cell.isClickable = true; cell.isFocusable = true
            cell.setOnClickListener { onOpen(doc) }
            return cell
        }
    }

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(12),dp(12),dp(6)) }
        header.addView(label("PDFCraft", 24f).apply { setTypeface(typeface, Typeface.BOLD) }, LayoutParams(0,-2,1f))
        onSupport?.let { support ->
            header.addView(ImageView(context).apply {
                id=R.id.support_button;contentDescription=context.getString(R.string.support_title);isFocusable=true
                setImageDrawable(MaterialSymbols.drawable(context,"support",if(dark) 0xffa5d6a7.toInt() else 0xff4f7d53.toInt()))
                setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(tint,24)
                setOnClickListener { support() }
            },LayoutParams(dp(48),dp(48)).apply { marginEnd=dp(8) })
        }
        header.addView(action("", "Einstellungen") { onSettings() }.apply { setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"menu",accent),null,null,null) })
        addView(header)
        val search = EditText(context).apply {
            hint = UiText.t(context,"Bibliothek durchsuchen"); textSize = 16f; setTextColor(ink); setHintTextColor(muted); setSingleLine()
            background = rounded(tint, 28); setPadding(dp(22),dp(8),dp(16),dp(8)); contentDescription = UiText.t(context,"Bibliothek durchsuchen")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); refresh() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        addView(search, LayoutParams(-1,dp(54)).apply { setMargins(dp(16),dp(6),dp(16),dp(12)) })
        val tabs = LinearLayout(context).apply { setPadding(dp(12),0,dp(12),dp(8)) }
        listOf("Zuletzt", "Bibliothek", "Lesezeichen").forEachIndexed { index, title ->
            val button = action(title, title) { tab = index; refresh() }
            tabButtons.add(button); tabs.addView(button, LayoutParams(0,dp(48),1f))
        }
        addView(tabs)
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
        credits.addView(action("Buy me some tokens", "Buy me some tokens") {
            if (onSupport != null) onSupport.invoke() else SupportDialog.create(context).show()
        }.apply {
            textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0,0,0,0)
            compoundDrawablePadding = dp(4)
            setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"support",if(dark) 0xffa5d6a7.toInt() else 0xff4f7d53.toInt()),null,null,null)
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

    private fun refresh() {
        shown = all.filter { it.name.contains(query, ignoreCase = true) && (tab != 2 || it.bookmarks.isNotEmpty()) }
            .let { docs -> if (tab != 1) docs else when (sortOrder) {
                1 -> docs.sortedByDescending { it.name.lowercase() }
                2 -> docs.sortedByDescending { it.addedAt }
                3 -> docs.sortedBy { it.addedAt }
                else -> docs.sortedBy { it.name.lowercase() }
            } }
        empty.text = UiText.t(context, if (all.isEmpty()) "Öffne deine erste PDF.\nDeine Lesestelle bleibt gespeichert." else if (tab == 2 && query.isEmpty()) "Noch keine Lesezeichen.\nTippe beim Lesen auf das Lesezeichen-Symbol." else "Keine passenden Dokumente.")
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        tabButtons.forEachIndexed { index, view -> view.background = rounded(if (tab == index) tint else Color.TRANSPARENT,14); view.setTextColor(if (tab == index) accent else muted) }
        adapter.notifyDataSetChanged()
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
    private fun action(value: String, description: String, callback: () -> Unit) = label(value, 14f).apply {
        gravity = Gravity.CENTER; minWidth = dp(48); minHeight = dp(48); setPadding(dp(12),0,dp(12),0)
        setTextColor(accent); contentDescription = UiText.t(context,description); isFocusable = true; setOnClickListener { callback() }
    }
}
