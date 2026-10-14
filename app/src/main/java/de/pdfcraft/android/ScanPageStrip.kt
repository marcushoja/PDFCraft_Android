package de.pdfcraft.android

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import java.io.File

data class ScanStripPage(val id: String, val thumbnail: File)

/** Thumbnail-only UI. The owner confirms deletion and persists every requested change. */
class ScanPageStrip(context: Context, dark: Boolean) : HorizontalScrollView(context) {
    private val colors = ZenColors.of(dark)
    val row = LinearLayout(context).apply {
        id = R.id.scan_strip
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private var pages = emptyList<ScanStripPage>()
    private var selected = 0
    private var actionsEnabled = true
    private var select: (Int) -> Unit = {}
    private var delete: (Int) -> Unit = {}
    private var move: (Int, Int) -> Unit = { _, _ -> }
    private class DragToken(val pageId: String)
    private var activeDrag: DragToken? = null
    private var pendingRender = false
    private var hoverX = 0f
    private var highlighted = -1
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (activeDrag == null || !actionsEnabled) return
            val edge = dp(40)
            val delta = when {
                hoverX < edge -> -dp(8)
                hoverX > width - edge -> dp(8)
                else -> 0
            }
            if (delta != 0) {
                scrollBy(delta, 0)
                highlight(targetIndex(hoverX))
            }
            postOnAnimation(this)
        }
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(colors.card)
        addView(row)
        setOnDragListener { _, event -> handleDrag(event, event.x) }
    }

    fun bind(
        pages: List<ScanStripPage>, selected: Int, enabled: Boolean = true,
        onSelect: (Int) -> Unit, onDelete: (Int) -> Unit,
        onMove: (from: Int, to: Int) -> Unit
    ) {
        this.pages = pages.toList()
        this.selected = selected
        actionsEnabled = enabled
        select = onSelect
        delete = onDelete
        move = onMove
        // Do not replace drag recipients underneath Android's active gesture.
        if (activeDrag != null) {
            pendingRender = true
            if (!enabled) {
                removeCallbacks(edgeScroll)
                highlight(-1)
                setChildrenEnabled(false)
            }
        } else render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        if (stroke) setStroke(dp(2), this@ScanPageStrip.colors.accent)
    }

    private fun render() {
        pendingRender = false
        row.removeAllViews()
        pages.forEachIndexed { index, page ->
            val label = context.getString(R.string.scan_page, index + 1)
            val item = FrameLayout(context).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "$label, ${context.getString(R.string.scan_reorder_hint)}"
                isSelected = index == selected
                isEnabled = actionsEnabled
                isFocusable = true
                background = rounded(if (isSelected) colors.tint else colors.card)
                setOnClickListener {
                    val current = pages.indexOfFirst { it.id == page.id }
                    if (actionsEnabled && current >= 0) select(current)
                }
                setOnLongClickListener {
                    if (!actionsEnabled || activeDrag != null || pages.none { it.id == page.id }) false
                    else {
                        val token = DragToken(page.id)
                        activeDrag = token
                        val started = startDragAndDrop(ClipData.newPlainText("scan-page", ""), View.DragShadowBuilder(this), token, 0)
                        if (!started) finishDrag()
                        started
                    }
                }
                setOnDragListener { view, event ->
                    handleDrag(event, view.left - scrollX + event.x)
                }
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(colors.paper)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Refuse full-size camera files before allocating any pixel buffer.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.thumbnail.path, bounds)
                if (bounds.outWidth in 1..160 && bounds.outHeight in 1..160) {
                    setImageBitmap(BitmapFactory.decodeFile(page.thumbnail.path))
                }
            }
            item.addView(image, FrameLayout.LayoutParams(dp(68), dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            footer.addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(colors.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2))
            footer.addView(ImageView(context).apply {
                setImageDrawable(MaterialSymbols.drawable(context, "scan_drag", colors.muted))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(3) })
            item.addView(footer, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM))
            item.addView(ImageButton(context).apply {
                contentDescription = context.getString(R.string.scan_delete_page_number, index + 1)
                setImageDrawable(MaterialSymbols.drawable(context, "close", colors.accent))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = InsetDrawable(GradientDrawable().apply {
                    setColor((this@ScanPageStrip.colors.tint and 0x00ffffff) or (0xcc shl 24))
                    cornerRadius = dp(12).toFloat()
                }, dp(8))
                // InsetDrawable supplies its own padding; retain the smaller icon size.
                setPadding(dp(11),dp(11),dp(11),dp(11))
                isEnabled = actionsEnabled
                setOnClickListener {
                    val current = pages.indexOfFirst { it.id == page.id }
                    if (actionsEnabled && current >= 0) delete(current)
                }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT))
            row.addView(item, LinearLayout.LayoutParams(dp(80), dp(96)))
            if (index == selected) item.post {
                if (activeDrag == null && item.parent === row) {
                    item.requestRectangleOnScreen(Rect(0, 0, item.width, item.height), true)
                }
            }
        }
    }

    private fun setChildrenEnabled(enabled: Boolean) {
        for (index in 0 until row.childCount) {
            val item = row.getChildAt(index) as FrameLayout
            item.isEnabled = enabled
            for (child in 0 until item.childCount) item.getChildAt(child).isEnabled = enabled
        }
    }

    private fun targetIndex(viewportX: Float): Int {
        val x = viewportX + scrollX
        for (index in 0 until row.childCount) {
            if (x < row.getChildAt(index).right) return index
        }
        return row.childCount - 1
    }

    private fun highlight(index: Int) {
        if (highlighted == index) return
        highlighted = index
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i)
            item.isActivated = i == index
            item.background = rounded(if (item.isSelected || item.isActivated) colors.tint else colors.card, item.isActivated)
            ViewCompat.setStateDescription(item, if (item.isActivated) context.getString(R.string.scan_reorder_hint) else null)
        }
        if (index >= 0) row.getChildAt(index)?.announceForAccessibility(context.getString(R.string.scan_page, index + 1))
    }

    private fun handleDrag(event: DragEvent, viewportX: Float): Boolean {
        val token = activeDrag ?: return false
        if (event.localState !== token) return false
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            finishDrag()
            return true
        }
        if (!actionsEnabled) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                hoverX = viewportX
                highlight(targetIndex(viewportX))
                removeCallbacks(edgeScroll)
                postOnAnimation(edgeScroll)
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                removeCallbacks(edgeScroll)
                highlight(-1)
            }
            DragEvent.ACTION_DROP -> {
                val requested = resolveMove(pages, token.pageId, targetIndex(viewportX))
                finishDrag()
                requested?.let { (from, to) -> move(from, to) }
                return requested != null
            }
        }
        return true
    }

    private fun finishDrag() {
        activeDrag = null
        removeCallbacks(edgeScroll)
        highlight(-1)
        if (pendingRender) render()
    }

    override fun onDetachedFromWindow() {
        finishDrag()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The same ID/current-order resolver used by ACTION_DROP, including edge overshoot. */
        internal fun resolveMove(pages: List<ScanStripPage>, pageId: String, targetIndex: Int): Pair<Int, Int>? {
            val from = pages.indexOfFirst { it.id == pageId }
            if (from < 0 || pages.isEmpty()) return null
            val to = targetIndex.coerceIn(pages.indices)
            return if (from == to) null else from to to
        }
    }
}
