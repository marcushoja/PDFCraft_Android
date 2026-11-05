package de.pdfcraft.android

/** Additional reader rotation, independent of the PDF's own page rotation. */
internal object PdfViewRotation {
    data class Point(val x: Float, val y: Float)
    fun normalize(degrees: Int): Int = if (degrees % 90 == 0) Math.floorMod(degrees, 360) else 0
    fun swapsAxes(degrees: Int) = normalize(degrees) % 180 != 0

    fun forward(x: Float, y: Float, degrees: Int): Point = when (normalize(degrees)) {
        90 -> Point(1f - y, x)
        180 -> Point(1f - x, 1f - y)
        270 -> Point(y, 1f - x)
        else -> Point(x, y)
    }

    fun inverse(x: Float, y: Float, degrees: Int) = forward(x, y, (360 - normalize(degrees)) % 360)

    /** Native PDF page pixels -> oriented bitmap pixels, in Android Matrix row-major order. */
    fun renderMatrix(degrees: Int, sourceWidth: Float, sourceHeight: Float,
                     targetWidth: Float, targetHeight: Float): FloatArray {
        require(sourceWidth > 0f && sourceHeight > 0f && targetWidth > 0f && targetHeight > 0f)
        val x = targetWidth / sourceWidth
        val y = targetHeight / sourceHeight
        return when (normalize(degrees)) {
            90 -> floatArrayOf(0f, -targetWidth / sourceHeight, targetWidth, targetHeight / sourceWidth, 0f, 0f, 0f, 0f, 1f)
            180 -> floatArrayOf(-x, 0f, targetWidth, 0f, -y, targetHeight, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, targetWidth / sourceHeight, 0f, -targetHeight / sourceWidth, 0f, targetHeight, 0f, 0f, 1f)
            else -> floatArrayOf(x, 0f, 0f, 0f, y, 0f, 0f, 0f, 1f)
        }
    }
}
