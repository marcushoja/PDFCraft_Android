package de.pdfcraft.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PdfCurlGeometryTest {
    private val rectangle = listOf(PdfCurlGeometry.Point(0f, 0f), PdfCurlGeometry.Point(100f, 0f),
        PdfCurlGeometry.Point(100f, 200f), PdfCurlGeometry.Point(0f, 200f))

    private fun area(points: List<PdfCurlGeometry.Point>): Float {
        if (points.isEmpty()) return 0f
        return abs(points.indices.sumOf { i ->
            val a = points[i]
            val b = points[(i + 1) % points.size]
            (a.x * b.y - b.x * a.y).toDouble()
        }.toFloat()) / 2f
    }

    @Test fun diagonalFoldPartitionsPageWithoutLosingArea() {
        val fold = PdfCurlGeometry(100f, 200f, 60f, 150f)
        val front = fold.clip(rectangle, false)
        val patch = fold.clip(rectangle, true)
        assertEquals(20000f, area(front) + area(patch), .01f)
        assertTrue(area(patch) > 0f && area(patch) < area(front))
        assertTrue(front.all { (it.x - fold.mx) * fold.nx + (it.y - fold.my) * fold.ny <= .001f })
        assertTrue(patch.all { (it.x - fold.mx) * fold.nx + (it.y - fold.my) * fold.ny >= -.001f })
    }

    @Test fun cornerReflectsExactlyToFingerAndCreaseStaysFixed() {
        val fold = PdfCurlGeometry(100f, 200f, 60f, 150f)
        val reflected = fold.reflect(PdfCurlGeometry.Point(100f, 200f))
        assertEquals(60f, reflected.x, .001f)
        assertEquals(150f, reflected.y, .001f)
        val crease = PdfCurlGeometry.Point(fold.mx - 25f * fold.ny, fold.my + 25f * fold.nx)
        val fixed = fold.reflect(crease)
        assertEquals(crease.x, fixed.x, .001f)
        assertEquals(crease.y, fixed.y, .001f)
    }

    @Test fun reflectionPreservesAreaAndIsItsOwnInverse() {
        val fold = PdfCurlGeometry(100f, 200f, 60f, 150f)
        val patch = fold.clip(rectangle, true)
        assertEquals(area(patch), area(patch.map(fold::reflect)), .01f)
        rectangle.forEach { p ->
            val twice = fold.reflect(fold.reflect(p))
            assertEquals(p.x, twice.x, .001f)
            assertEquals(p.y, twice.y, .001f)
        }
        val matrix = fold.matrixValues()
        assertEquals(-1f, matrix[0] * matrix[4] - matrix[1] * matrix[3], .001f)
    }

    @Test fun previousPageUsesLeftCornerAndReflectsUnderFinger() {
        val fold = PdfCurlGeometry(0f, 200f, 20f, 180f)
        val patch = fold.clip(rectangle, true)
        assertEquals(200f, area(patch), .01f)
        val finger = fold.reflect(PdfCurlGeometry.Point(0f, 200f))
        assertEquals(20f, finger.x, .001f)
        assertEquals(180f, finger.y, .001f)
    }

    @Test fun slightSwipeHasVisibleBacksideAndCompletedTurnHasNoFront() {
        val slight = PdfCurlGeometry(100f, 200f, 96f, 196f)
        assertEquals(8f, area(slight.clip(rectangle, true)), .01f)
        val complete = PdfCurlGeometry(100f, 200f, -101f, 200f)
        assertTrue(complete.clip(rectangle, false).isEmpty())
        assertEquals(20000f, area(complete.clip(rectangle, true)), .01f)
    }

    @Test fun zeroLengthFoldLeavesPageIntactAndValuesFinite() {
        val fold = PdfCurlGeometry(100f, 200f, 100f, 200f)
        assertTrue(fold.clip(rectangle, true).isEmpty())
        assertEquals(rectangle, fold.clip(rectangle, false))
        assertTrue(fold.matrixValues().all { it.isFinite() })
        assertTrue(fold.clip(emptyList(), true).isEmpty())
    }
}
