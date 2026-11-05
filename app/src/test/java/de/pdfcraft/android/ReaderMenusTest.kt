package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class ReaderMenusTest {
    @Test fun toolsComeFirstWithoutLosingOrDuplicatingExistingActions() {
        for (reflow in listOf(false, true)) {
            val actions = ReaderMenus.pdf(reflow)
            assertEquals("tools", actions.first().id)
            assertEquals(21, actions.size)
            assertEquals(actions.size, actions.map { it.id }.toSet().size)
            assertEquals(if (reflow) "PDF-Ansicht" else "Textmodus / Reflow", actions.single { it.id == "text" }.label)
            assertTrue(actions.any { it.id == "settings" })
        }
    }
}
