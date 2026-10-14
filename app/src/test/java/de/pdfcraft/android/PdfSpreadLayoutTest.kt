package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class PdfSpreadLayoutTest {
    @Test fun spreadPairsAndBookCoverKeepEveryRealPageExactlyOnce() {
        for (count in 0..12) for (book in listOf(false, true)) {
            val groups = PdfSpreadLayout.groups(count, book)
            assertEquals((0 until count).toList(), groups.flatten())
            assertTrue(groups.all { it.size in 1..2 })
            if (book && count > 0) assertEquals(listOf(0), groups.first())
        }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PdfSpreadLayout.groups(5, false))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), PdfSpreadLayout.groups(6, true))
    }

    @Test fun mixedPagesFitAsOneGroupWithoutStretchingOrOverlapping() {
        val widths = floatArrayOf(400f, 600f)
        val heights = floatArrayOf(800f, 300f)
        for (viewport in listOf(1000f to 600f, 450f to 900f)) for (rtl in listOf(false, true)) {
            val sheets = PdfSpreadLayout.sheets(listOf(0, 1), widths, heights, viewport.first, viewport.second, rtl)
            assertEquals(if (rtl) listOf(1, 0) else listOf(0, 1), sheets.map { it.page })
            assertEquals(sheets[0].left + sheets[0].width, sheets[1].left, .001f)
            sheets.forEach {
                assertTrue(it.left >= 0f && it.top >= 0f)
                assertTrue(it.left + it.width <= viewport.first + .001f)
                assertTrue(it.top + it.height <= viewport.second + .001f)
                assertEquals(widths[it.page] / heights[it.page], it.width / it.height, .001f)
            }
            assertEquals(sheets[0].width / widths[sheets[0].page], sheets[1].width / widths[sheets[1].page], .001f)
        }
    }

    @Test fun unpairedCoverAndEndingPageAreCenteredNotPhantomPairs() {
        val sheet = PdfSpreadLayout.sheets(listOf(0), floatArrayOf(400f), floatArrayOf(800f), 1000f, 600f, true).single()
        assertEquals(300f, sheet.width, .001f)
        assertEquals(350f, sheet.left, .001f)
        assertEquals(0f, sheet.top, .001f)
    }
}
