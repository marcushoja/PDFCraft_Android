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
    private val tabButtons = mutableListOf<TextView>()
    private val empty = label("Öffne deine erste PDF.\nDeine Lesestelle bleibt gespeichert.", 17f).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setTextColor(muted) }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val doc = shown[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(10))
                val frame = FrameLayout(context)
                frame.background = rounded(surface, 14)
                frame.addView(ImageView(context).apply { setPadding(dp(5), dp(5), dp(5), dp(5)) }, FrameLayout.LayoutParams(-1,-1))
                frame.addView(ImageView(context).apply { setPadding(dp(12),dp(12),dp(12),dp(12)); setImageDrawable(MaterialSymbols.drawable(context,"more",muted)); background = rounded(tint,14); contentDescription = UiText.t(context,"Dokumentoptionen") }, FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.TOP))
                addView(frame, LayoutParams(-1, dp(174)))
                addView(label("", 15f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = dp(44); setPadding(dp(2),dp(5),dp(2),0) })
                addView(label("", 12f).apply { setTextColor(muted) })
            }
            val frame = cell.getChildAt(0) as FrameLayout
            thumbnails.load(store.file(doc), 0, dp(150), frame.getChildAt(0) as ImageView)
            frame.getChildAt(1).setOnClickListener { onOptions(doc) }
            (cell.getChildAt(1) as TextView).text = doc.name.replace(Regex("\\.pdf$",RegexOption.IGNORE_CASE),"")
            (cell.getChildAt(2) as TextView).text = UiText.t(context, if (doc.pageCount > 0) "Seite ${doc.page + 1} / ${doc.pageCount}" else "Noch nicht gelesen")
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
        val frame = FrameLayout(context)
        val grid = GridView(context).apply {
            numColumns = if (resources.configuration.screenWidthDp < 420) 2 else 3
            horizontalSpacing = dp(8); verticalSpacing = dp(8); setPadding(dp(12),dp(8),dp(12),dp(8))
            clipToPadding = false; adapter = this@LibraryView.adapter
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1,-1))
        frame.addView(empty, FrameLayout.LayoutParams(-1,-1))
        addView(frame, LayoutParams(-1,0,1f))
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(8),dp(16),dp(12)) }
        bottom.addView(label("Von Marcus Hoja\nOffline · private Kopien", 12f).apply { setTextColor(ZenColors.of(dark).brown) },LayoutParams(0,-2,1f))
        bottom.addView(action("PDF öffnen", "PDF öffnen") { onImport() }.apply { background = rounded(tint, 18); compoundDrawablePadding = dp(8); setCompoundDrawablesWithIntrinsicBounds(MaterialSymbols.drawable(context,"folder",accent),null,null,null) })
        addView(bottom)
        refresh()
    }

    private fun refresh() {
        shown = all.filter { it.name.contains(query, ignoreCase = true) && (tab != 2 || it.bookmarks.isNotEmpty()) }
            .let { if (tab == 1) it.sortedBy { doc -> doc.name.lowercase() } else it }
        empty.text = UiText.t(context, if (all.isEmpty()) "Öffne deine erste PDF.\nDeine Lesestelle bleibt gespeichert." else if (tab == 2 && query.isEmpty()) "Noch keine Lesezeichen.\nTippe beim Lesen auf das Lesezeichen-Symbol." else "Keine passenden Dokumente.")
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        tabButtons.forEachIndexed { index, view -> view.background = rounded(if (tab == index) tint else Color.TRANSPARENT,14); view.setTextColor(if (tab == index) accent else muted) }
        adapter.notifyDataSetChanged()
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(value: String, size: Float) = TextView(context).apply { text = UiText.t(context,value); textSize = size; setTextColor(ink) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun action(value: String, description: String, callback: () -> Unit) = label(value, 14f).apply {
        gravity = Gravity.CENTER; minWidth = dp(48); minHeight = dp(48); setPadding(dp(12),0,dp(12),0)
        setTextColor(accent); contentDescription = UiText.t(context,description); isFocusable = true; setOnClickListener { callback() }
    }
}
