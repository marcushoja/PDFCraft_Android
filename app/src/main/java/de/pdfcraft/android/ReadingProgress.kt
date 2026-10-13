package de.pdfcraft.android

/** Page indices are zero-based; offset is the fraction scrolled within a page. */
data class ReadingProgress(
    val page: Int,
    val offset: Float,
    val zoom: Float,
    val pageCount: Int,
) {
    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f

        /** A zero page count means unknown; until known the current page is zero. */
        fun sanitize(page: Int, offset: Float, zoom: Float, pageCount: Int): ReadingProgress {
            val count = pageCount.coerceAtLeast(0)
            return ReadingProgress(
                page = if (count == 0) 0 else page.coerceIn(0, count - 1),
                offset = if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f,
                zoom = if (zoom.isFinite()) zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM,
                pageCount = count,
            )
        }
    }
}
