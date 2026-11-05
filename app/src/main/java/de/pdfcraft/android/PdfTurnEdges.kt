package de.pdfcraft.android

/** Physical screen-side turn zones, independent of density and fitted paper width. */
internal object PdfTurnEdges {
    fun at(x: Float, viewportWidth: Int): Int {
        if (viewportWidth <= 0 || !x.isFinite() || x < 0f || x > viewportWidth.toFloat()) return 0
        return when {
            x <= viewportWidth * .10f -> -1
            x >= viewportWidth * .90f -> 1
            else -> 0
        }
    }
}
