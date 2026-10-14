package de.pdfcraft.android

import kotlin.math.max
import kotlin.math.min

/** Logical groups never change document page numbers, including in RTL. */
internal object PdfSpreadLayout {
    fun groups(count: Int, book: Boolean): List<List<Int>> {
        val result = ArrayList<List<Int>>()
        var page = 0
        if (book && count > 0) { result.add(listOf(0)); page++ }
        while (page < count) {
            result.add((page until min(page + 2, count)).toList())
            page += 2
        }
        return result
    }

    data class Sheet(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float)

    /** Common scale preserves mixed-size pages; the complete group fits both viewport axes. */
    fun sheets(pages: List<Int>, widths: FloatArray, heights: FloatArray,
               viewportWidth: Float, viewportHeight: Float, rtl: Boolean): List<Sheet> {
        if (pages.isEmpty() || viewportWidth <= 0f || viewportHeight <= 0f) return emptyList()
        val scale = min(viewportWidth / pages.sumOf { widths[it].toDouble() }.toFloat(),
            viewportHeight / pages.maxOf { heights[it] })
        val total = pages.sumOf { (widths[it] * scale).toDouble() }.toFloat()
        var left = max(0f, (viewportWidth - total) / 2f)
        return (if (rtl) pages.reversed() else pages).map { page ->
            val w = widths[page] * scale
            val h = heights[page] * scale
            Sheet(page, left, (viewportHeight - h) / 2f, w, h).also { left += w }
        }
    }
}
