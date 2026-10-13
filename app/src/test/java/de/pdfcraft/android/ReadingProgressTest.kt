package de.pdfcraft.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun validProgressIsUnchanged() {
        assertEquals(ReadingProgress(3, 0.4f, 2f, 10), ReadingProgress.sanitize(3, 0.4f, 2f, 10))
    }

    @Test
    fun pageIsClampedToZeroBasedBounds() {
        assertEquals(0, ReadingProgress.sanitize(-1, 0f, 1f, 10).page)
        assertEquals(9, ReadingProgress.sanitize(Int.MAX_VALUE, 0f, 1f, 10).page)
        assertEquals(0, ReadingProgress.sanitize(1, 0f, 1f, 1).page)
        assertEquals(Int.MAX_VALUE - 1, ReadingProgress.sanitize(Int.MAX_VALUE, 0f, 1f, Int.MAX_VALUE).page)
    }

    @Test
    fun unknownOrNegativePageCountResetsPage() {
        assertEquals(ReadingProgress(0, 0f, 1f, 0), ReadingProgress.sanitize(8, 0f, 1f, 0))
        assertEquals(ReadingProgress(0, 0f, 1f, 0), ReadingProgress.sanitize(8, 0f, 1f, Int.MIN_VALUE))
    }

    @Test
    fun offsetIsClampedToPageFraction() {
        assertEquals(0f, ReadingProgress.sanitize(0, -0.5f, 1f, 1).offset, 0f)
        assertEquals(1f, ReadingProgress.sanitize(0, 1.5f, 1f, 1).offset, 0f)
        for (offset in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, ReadingProgress.sanitize(0, offset, 1f, 1).offset, 0f)
        }
    }

    @Test
    fun zoomIsClampedAndNonFiniteValuesReset() {
        for (zoom in listOf(-5f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, ReadingProgress.sanitize(0, 0f, zoom, 1).zoom, 0f)
        }
        assertEquals(5f, ReadingProgress.sanitize(0, 0f, 100f, 1).zoom, 0f)
        assertEquals(5f, ReadingProgress.sanitize(0, 0f, 5f, 1).zoom, 0f)
    }
}
