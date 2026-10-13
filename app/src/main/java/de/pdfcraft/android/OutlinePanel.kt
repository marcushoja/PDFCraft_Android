package de.pdfcraft.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Native, recycled outline tree. Entry pages and navigation callbacks are zero-based. */
class OutlinePanel(
    context: Context,
    private val dark: Boolean,
    private val entries: List<OutlineEntry>,
    currentPage: Int,
    private val onNavigate: (Int) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private val ink = ZenColors.of(dark).ink
    private val muted = ZenColors.of(dark).muted
    private val border = ZenColors.of(dark).outline
    private val parents = IntArray(entries.size) { -1 }
    private val hasChildren = BooleanArray(entries.size)
    private val expanded = HashSet<Int>()
    private val active = entries.indices.filter { entries[it].page <= currentPage }
        .maxWithOrNull(compareBy<Int> { entries[it].page }.thenBy { entries[it].depth }.thenBy { it }) ?: -1
    private var query = ""
    private var visible = emptyList<Int>()
    private val list = ListView(context)
    private val search = EditText(context)
    private val empty = TextView(context)
    private val adapter = OutlineAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(ZenColors.of(dark).paper)
        val stack = ArrayList<Int>()
        entries.indices.forEach { index ->
            while (stack.isNotEmpty() && entries[stack.last()].depth >= entries[index].depth) stack.removeAt(stack.lastIndex)
            if (stack.isNotEmpty()) { parents[index] = stack.last(); hasChildren[stack.last()] = true }
            stack.add(index)
        }
        expandAncestors(active)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)); setBackgroundColor(ZenColors.of(dark).tint) }
        header.addView(iconButton("back", "Zurück", onClose))
        header.addView(TextView(context).apply {
            text = UiText.t(context,"Inhaltsverzeichnis"); textSize = 20f; setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("jump", "Zum aktuellen Eintrag") { jumpToActive() })
        header.addView(iconButton("search", "Inhaltsverzeichnis durchsuchen") {
            if (search.visibility == View.VISIBLE) {
                search.setText(""); search.visibility = View.GONE
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
            } else {
                search.visibility = View.VISIBLE; search.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        search.apply {
            hint = UiText.t(context,"Kapitel suchen"); textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            setSingleLine(true); minimumHeight = dp(48); visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = cardBackground(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString()?.trim().orEmpty(); rebuild() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        empty.apply { gravity = Gravity.CENTER; textSize = 16f; setTextColor(muted); setPadding(dp(24), dp(32), dp(24), dp(32)) }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.apply {
            divider = null; dividerHeight = 0; cacheColorHint = Color.TRANSPARENT
            setPadding(dp(12), dp(4), dp(12), dp(20)); clipToPadding = false
            adapter = this@OutlinePanel.adapter
            emptyView = empty
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
        if (active >= 0) list.post { list.setSelection(visible.indexOf(active).coerceAtLeast(0)) }
    }

    private fun iconButton(symbol: String, label: String, action: () -> Unit): View =
        android.widget.FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(48), dp(48)); contentDescription = UiText.t(context,label); isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            addView(ReaderIconView(context, symbol, ink), android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { action() }
        }

    private fun expandAncestors(index: Int) {
        var parent = if (index >= 0) parents[index] else -1
        while (parent >= 0) { expanded.add(parent); parent = parents[parent] }
    }

    private fun jumpToActive() {
        if (active < 0) return
        search.setText("")
        expandAncestors(active); rebuild()
        list.smoothScrollToPosition(visible.indexOf(active).coerceAtLeast(0))
    }

    private fun rebuild() {
        if (query.isNotEmpty()) {
            val included = HashSet<Int>()
            entries.indices.filter { entries[it].title.contains(query, ignoreCase = true) }.forEach { index ->
                var item = index
                while (item >= 0) { included.add(item); item = parents[item] }
            }
            visible = entries.indices.filter { it in included }
        } else {
            visible = entries.indices.filter { index ->
                var parent = parents[index]
                var shown = true
                while (parent >= 0) { if (parent !in expanded) { shown = false; break }; parent = parents[parent] }
                shown
            }
        }
        empty.text = UiText.t(context, if (query.isEmpty()) "Kein Inhaltsverzeichnis vorhanden" else "Keine passenden Kapitel")
        adapter.notifyDataSetChanged()
    }

    private fun cardBackground(selected: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) ZenColors.of(dark).tint else ZenColors.of(dark).card)
            setStroke(dp(1), if (selected) ZenColors.of(dark).accent else border)
        }
        return RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x16000000), shape, null)
    }

    private inner class Row : LinearLayout(context) {
        val card = LinearLayout(context)
        val chevron = iconButton("collapsed", "Unterkapitel aufklappen") { }
        val title = TextView(context)
        val page = TextView(context)
        init {
            orientation = VERTICAL
            card.gravity = Gravity.CENTER_VERTICAL; card.minimumHeight = dp(64)
            card.addView(chevron)
            title.apply { textSize = 16f; setTextColor(ink); setPadding(dp(4), dp(12), dp(8), dp(12)) }
            card.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            page.apply { textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(8), 0, dp(12), 0); isFocusable = true }
            card.addView(page, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private inner class OutlineAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = entries[visible[position]]
        override fun getItemId(position: Int) = visible[position].toLong()
        override fun hasStableIds() = true
        override fun isEnabled(position: Int) = false // Each card owns its distinct navigation and expansion targets.
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? Row ?: Row()
            val index = visible[position]
            val entry = entries[index]
            val searching = query.isNotEmpty()
            val open = searching || index in expanded
            // Indent by actual ancestors rather than trusting potentially skipped PDF depth levels.
            var depth = 0; var ancestor = parents[index]
            while (ancestor >= 0) { depth++; ancestor = parents[ancestor] }
            row.setPadding(dp(minOf(depth, 6) * 16), dp(4), 0, dp(4))
            row.card.background = cardBackground(index == active)
            row.title.text = entry.title
            row.title.setTypeface(null, if (index == active) Typeface.BOLD else Typeface.NORMAL)
            row.title.contentDescription = if (index == active) UiText.t(context,"${entry.title}, aktuelles Kapitel") else entry.title
            row.page.text = (entry.page + 1).toString()
            row.page.contentDescription = UiText.t(context,"Zu Seite ${entry.page + 1} springen")
            row.page.setOnClickListener { onNavigate(entry.page) }
            row.chevron.visibility = if (hasChildren[index]) View.VISIBLE else View.INVISIBLE
            row.chevron.isEnabled = !searching
            row.chevron.contentDescription = UiText.t(context, if (open) "Unterkapitel von ${entry.title} zuklappen" else "Unterkapitel von ${entry.title} aufklappen")
            (row.chevron as ViewGroup).getChildAt(0).let { icon ->
                (icon as ReaderIconView).symbol = if (open) "expanded" else "collapsed"; icon.invalidate()
            }
            val activate = {
                if (hasChildren[index]) {
                    if (!searching) { if (!expanded.add(index)) expanded.remove(index); rebuild() }
                } else onNavigate(entry.page)
            }
            row.card.isFocusable = true
            row.card.setOnClickListener { activate() }
            row.chevron.setOnClickListener { activate() }
            return row
        }
    }
}
