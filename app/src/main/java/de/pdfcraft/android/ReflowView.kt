package de.pdfcraft.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Native wrapping, accessible text with Android's selection/copy actions. */
class ReflowView(context: Context) : ScrollView(context) {
    val textView = TextView(context)
    var onOffsetChanged: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    private var pendingOffset: Float? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!textView.hasSelection()) performClick()
            return false
        }
    })

    init {
        isFillViewport = true
        clipToPadding = false
        val padding = (24 * resources.displayMetrics.density).roundToInt()
        textView.setPadding(padding, padding, padding, padding * 2)
        textView.setTextIsSelectable(true)
        textView.setLineSpacing(0f, 1.35f)
        // This compile-time constant is the same value supported by TextView since API 23.
        textView.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        textView.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textView.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            false // Never consume TextView's long-press selection or ScrollView's drag.
        }
        setAppearance(false, 19f, false)
    }

    fun showText(text: String, page: Int, pageCount: Int) {
        textView.text = if (text.isBlank()) {
            UiText.t(context,"Diese Seite enthält keinen auswählbaren Text. Vermutlich ist sie ein Scan. Reflow benötigt eingebetteten Text; OCR ist noch nicht verfügbar.")
        } else TextSearch.normalizeExtractedText(text)
        // Keep the TextView's actual text available to accessibility services.
        contentDescription = UiText.t(context,"Reflow, Seite ${page + 1} von $pageCount")
        restoreOffset(0f)
    }

    fun setAppearance(dark: Boolean, fontSize: Float, serif: Boolean) {
        val offset = pendingOffset ?: normalizedOffset()
        setBackgroundColor(ZenColors.of(dark).paper)
        textView.setTextColor(ZenColors.of(dark).ink)
        textView.textSize = if (fontSize.isFinite()) fontSize.coerceIn(12f, 48f) else 19f
        textView.typeface = if (serif) Typeface.create("serif", Typeface.NORMAL) else Typeface.DEFAULT
        restoreOffset(offset)
    }

    fun restoreOffset(offset: Float) {
        pendingOffset = if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        pendingOffset?.let { offset ->
            pendingOffset = null
            scrollTo(0, (maximumScroll() * offset).roundToInt())
            onOffsetChanged?.invoke(normalizedOffset())
        }
    }

    private fun maximumScroll(): Int = (textView.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    private fun normalizedOffset(): Float {
        val maximum = maximumScroll()
        return if (maximum == 0) 0f else (scrollY.toFloat() / maximum).coerceIn(0f, 1f)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (pendingOffset == null) onOffsetChanged?.invoke(normalizedOffset())
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }
}
