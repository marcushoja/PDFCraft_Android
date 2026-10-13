package de.pdfcraft.android

import kotlin.math.max
import kotlin.math.sqrt

/** Pure sheet geometry: the crease is the perpendicular bisector of corner and finger. */
internal class PdfCurlGeometry(cx: Float, cy: Float, px: Float, py: Float) {
    data class Point(val x: Float, val y: Float)

    val length = sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py))
    val nx = (cx - px) / max(.001f, length)
    val ny = (cy - py) / max(.001f, length)
    val mx = (cx + px) / 2f
    val my = (cy + py) / 2f

    /** Sutherland–Hodgman clipping; cornerSide selects the patch to be reflected. */
    fun clip(input: List<Point>, cornerSide: Boolean): List<Point> {
        if (input.isEmpty()) return emptyList()
        if (length < .001f) return if (cornerSide) emptyList() else input
        fun distance(p: Point) = ((p.x - mx) * nx + (p.y - my) * ny) *
            if (cornerSide) 1f else -1f
        val output = ArrayList<Point>(input.size + 1)
        var a = input.last()
        var da = distance(a)
        for (b in input) {
            val db = distance(b)
            if ((da >= 0f) != (db >= 0f)) {
                val t = da / (da - db)
                output.add(Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
            if (db >= 0f) output.add(b)
            a = b
            da = db
        }
        return output
    }

    fun reflect(point: Point): Point {
        val distance = (point.x - mx) * nx + (point.y - my) * ny
        return Point(point.x - 2f * nx * distance, point.y - 2f * ny * distance)
    }

    /** Android Matrix-compatible row-major affine reflection, with determinant -1. */
    fun matrixValues(): FloatArray {
        val dot = nx * mx + ny * my
        return floatArrayOf(1f - 2f * nx * nx, -2f * nx * ny, 2f * nx * dot,
            -2f * nx * ny, 1f - 2f * ny * ny, 2f * ny * dot, 0f, 0f, 1f)
    }
}
