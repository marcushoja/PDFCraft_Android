package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class PdfCurvedMeshTest {
    @Test fun curvedBandIsNonAffineWithStrongDepthAndSeparateBackside() {
        val fold = PdfCurlGeometry(1000f, 1000f, 400f, 1000f)
        val mesh = PdfCurvedMesh(fold)
        val radius = mesh.radius
        val start = fold.mx - PI.toFloat() * radius / 2f
        val a = mesh.project(start, 500f)
        val b = mesh.project(fold.mx, 500f)
        val c = mesh.project(fold.mx + PI.toFloat() * radius / 2f, 500f)
        assertEquals(0f, a.depth, .001f)
        assertEquals(radius, b.depth, .001f)
        assertEquals(2f * radius, c.depth, .001f)
        assertTrue("A bent grid must differ substantially from a flat/reflected triangle", abs(b.x - (a.x + c.x) / 2f) > radius * .9f)
        assertFalse(a.back)
        assertTrue(c.back)
        assertTrue(b.light < .7f)
    }

    @Test fun untouchedFrontStaysFlatAndPulledCornerTracksFingerForBothDirections() {
        for ((cx, px) in listOf(1000f to 400f, 0f to 600f)) {
            val mesh = PdfCurvedMesh(PdfCurlGeometry(cx, 1000f, px, 700f))
            val corner = mesh.project(cx, 1000f)
            assertEquals(px, corner.x, .001f)
            assertEquals(700f, corner.y, .001f)
            assertTrue(corner.back)
            val opposite = mesh.project(1000f - cx, 0f)
            assertEquals(1000f - cx, opposite.x, .001f)
            assertEquals(0f, opposite.y, .001f)
            assertEquals(0f, opposite.depth, .001f)
        }
    }

    @Test fun radiusRespondsToPullAndAllNearDegenerateVerticesStayFinite() {
        assertTrue(PdfCurvedMesh(PdfCurlGeometry(100f, 200f, 0f, 100f)).radius >
            PdfCurvedMesh(PdfCurlGeometry(100f, 200f, 95f, 195f)).radius)
        for (pull in listOf(0f, .0001f, .5f, 100f, 2000f)) {
            val mesh = PdfCurvedMesh(PdfCurlGeometry(100f, 200f, 100f - pull, 200f - pull))
            for (x in 0..100 step 10) for (y in 0..200 step 10) {
                val v = mesh.project(x.toFloat(), y.toFloat())
                assertTrue(v.x.isFinite() && v.y.isFinite() && v.depth.isFinite())
            }
        }
        val zero = PdfCurvedMesh(PdfCurlGeometry(100f, 200f, 100f, 200f)).project(80f, 50f)
        assertEquals(80f, zero.x, 0f)
        assertEquals(1f, zero.light, 0f)
    }
}
