package de.pdfcraft.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test fun searchIgnoresCaseAndCollapsesWhitespace() {
        assertEquals(listOf("Hello PDF world"), TextSearch.snippets("Hello\nPDF world", "pdf"))
    }

    @Test fun snippetsMarkTruncatedContext() {
        val snippet = TextSearch.snippets("a".repeat(100) + "needle" + "b".repeat(100), "NEEDLE").single()
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("needle"))
    }

    @Test fun limitsResultsAndIgnoresBlankQuery() {
        assertEquals(2, TextSearch.snippets("one one one", "one", 2).size)
        assertTrue(TextSearch.snippets("anything", " ").isEmpty())
    }

    @Test fun preservesParagraphsShortLinesAndColumns() {
        assertEquals("Heading\nShort line\n\nNext paragraph", TextSearch.normalizeExtractedText("Heading\r\nShort line\r\n\r\nNext paragraph"))
        val columns = "Left column" + " ".repeat(4) + "Right column " + "x".repeat(60) + "\nSecond row"
        assertEquals(columns, TextSearch.normalizeExtractedText(columns))
    }

    @Test fun unwrapsLongProseAndLineEndHyphen() {
        val line = "This is a sufficiently long prose line that ends with a split ex-"
        assertEquals(line.dropLast(1) + "ample continues.", TextSearch.normalizeExtractedText("$line\nample continues."))
        assertEquals(line.dropLast(3).trimEnd() + " next line", TextSearch.normalizeExtractedText("${line.dropLast(3)}\nnext line"))
    }
}
