package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class ReaderMenusTest {
    @Test fun toolsComeFirstWithoutLosingOrDuplicatingExistingActions() {
        for (reflow in listOf(false, true)) {
            val actions = ReaderMenus.pdf(reflow)
            assertEquals("tools", actions.first().id)
            assertEquals(18, actions.size)
            assertEquals(actions.size, actions.map { it.id }.toSet().size)
            assertEquals(if (reflow) "PDF-Ansicht" else "Textmodus / Reflow", actions.single { it.id == "text" }.label)
            assertTrue(actions.any { it.id == "settings" })
            assertTrue(actions.any { it.id == "bookmark" })
            assertTrue(actions.any { it.id == "dark" })
            assertFalse(actions.any { it.id in listOf("open", "full", "about") })
        }
    }

    @Test fun libraryOffersBackupSettingsUpdatesAndAbout() {
        val actions = ReaderMenus.library("Auf Updates prüfen")
        assertEquals(listOf("backup", "settings", "updates", "about"), actions.map { it.id })
    }
}
