package de.pdfcraft.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectionTest {
    @Test fun preservesRequestedOrderAndRemovesDuplicates() { assertEquals(listOf(2,1,0,4), PageSelection.parse("3-1, 5, 1", 5)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfRange() { PageSelection.parse("0, 2", 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidSyntax() { PageSelection.parse("1-x", 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmpty() { PageSelection.parse("", 5) }
}
