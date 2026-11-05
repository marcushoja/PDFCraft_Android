package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class PdfViewRotationTest {
    @Test fun quarterTurnsCycleAndStoredValuesAreNormalized() {
        var rotation = 0
        val cycle = (1..4).map { rotation = PdfViewRotation.normalize(rotation + 90); rotation }
        assertEquals(listOf(90, 180, 270, 0), cycle)
        assertEquals(270, PdfViewRotation.normalize(-90))
        assertEquals(90, PdfViewRotation.normalize(450))
        assertEquals(0, PdfViewRotation.normalize(37))
        assertEquals(0, PdfViewRotation.normalize(Int.MIN_VALUE))
        assertTrue(PdfViewRotation.swapsAxes(90))
        assertTrue(PdfViewRotation.swapsAxes(270))
        assertFalse(PdfViewRotation.swapsAxes(180))
    }

    @Test fun normalizedPointsRotateClockwiseAndTextHitTestingInvertsThem() {
        val expected = listOf(.2f to .3f, .7f to .2f, .8f to .7f, .3f to .8f)
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val point = PdfViewRotation.forward(.2f, .3f, rotation)
            assertEquals(expected[index].first, point.x, .0001f)
            assertEquals(expected[index].second, point.y, .0001f)
            for ((x, y) in listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, .2f to .3f)) {
                val visual = PdfViewRotation.forward(x, y, rotation)
                val source = PdfViewRotation.inverse(visual.x, visual.y, rotation)
                assertEquals(x, source.x, .0001f)
                assertEquals(y, source.y, .0001f)
            }
        }
    }

    @Test fun nativeRenderMatricesMapTheEntirePageWithoutCroppingOrMirroring() {
        val corners = listOf(0f to 0f, 300f to 0f, 300f to 500f, 0f to 500f)
        val expected = listOf(
            listOf(0f to 0f, 600f to 0f, 600f to 1000f, 0f to 1000f),
            listOf(1000f to 0f, 1000f to 600f, 0f to 600f, 0f to 0f),
            listOf(600f to 1000f, 0f to 1000f, 0f to 0f, 600f to 0f),
            listOf(0f to 600f, 0f to 0f, 1000f to 0f, 1000f to 600f),
        )
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val swapped = PdfViewRotation.swapsAxes(rotation)
            val matrix = PdfViewRotation.renderMatrix(rotation, 300f, 500f,
                if (swapped) 1000f else 600f, if (swapped) 600f else 1000f)
            corners.forEachIndexed { corner, (x, y) ->
                assertEquals(expected[index][corner].first, matrix[0] * x + matrix[1] * y + matrix[2], .001f)
                assertEquals(expected[index][corner].second, matrix[3] * x + matrix[4] * y + matrix[5], .001f)
            }
            assertTrue("Rotation must never mirror text", matrix[0] * matrix[4] - matrix[1] * matrix[3] > 0f)
        }
    }
}
