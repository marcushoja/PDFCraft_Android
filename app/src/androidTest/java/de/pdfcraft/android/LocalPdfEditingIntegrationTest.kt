package de.pdfcraft.android

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPdfEditingIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample(): File {
        val source = File.createTempFile("local-edit-test-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(320, 480, index + 1).create())
                page.canvas.drawText("Local edit ${source.name} page $index", 20f, 40f, Paint().apply { textSize = 12f })
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return source
    }

    @Test fun repeatedEditsKeepOneLibraryEntryAndReimportUsesCurrentContentHash() {
        val store = DocumentStore(context)
        val tools = PdfTools(context)
        val source = sample()
        val original = source.readBytes()
        val imported = store.importDocument(Uri.fromFile(source))
        var reimportedOriginal: de.pdfcraft.android.PdfDocument? = null
        val outputs = mutableListOf<File>()
        try {
            store.rename(imported.id, "My edited PDF.pdf")
            store.updateProgress(imported.id, 1, .3f, 2f, 3)
            store.toggleBookmark(imported.id, 1)
            val count = store.list().size
            var current = store.get(imported.id)!!
            val rotated = tools.rotateAll(store.file(current), 90).also { outputs.add(it) }
            current = store.replaceContent(current.id, rotated, 3, current.contentHash)
            assertEquals(imported.id, current.id)
            assertEquals("My edited PDF.pdf", current.name)
            assertEquals(1, current.page)
            assertEquals(listOf(1), current.bookmarks)
            assertEquals(imported.addedAt, current.addedAt)
            val noted = tools.addNote(store.file(current), 1, "Saved in the same local PDF").also { outputs.add(it) }
            val updated = store.replaceContent(current.id, noted, 3, current.contentHash)
            assertEquals(count, store.list().size)
            assertEquals(imported.id, updated.id)
            assertEquals(updated.id, store.importDocument(Uri.fromFile(noted)).id)
            PDDocument.load(store.file(updated)).use { pdf ->
                assertEquals(listOf(90, 90, 90), (0 until 3).map { pdf.getPage(it).rotation })
                assertTrue(pdf.getPage(1).annotations.any { it.contents == "Saved in the same local PDF" })
            }
            reimportedOriginal = store.importDocument(Uri.fromFile(source))
            assertNotEquals(imported.id, reimportedOriginal!!.id)
            assertArrayEquals(original, store.file(reimportedOriginal!!).readBytes())
            assertArrayEquals(original, source.readBytes())
            assertTrue("Stale edits must not overwrite a newer revision", runCatching {
                store.replaceContent(updated.id, rotated, 3, imported.contentHash)
            }.isFailure)
            assertEquals(updated.contentHash, store.get(updated.id)!!.contentHash)
        } finally {
            store.remove(imported.id)
            reimportedOriginal?.let { store.remove(it.id) }
            outputs.forEach { it.delete() }; source.delete()
        }
    }

    @Test fun reorderAndDeleteRemapBookmarksAndReadingPosition() {
        val store = DocumentStore(context)
        val tools = PdfTools(context)
        val source = sample()
        val imported = store.importDocument(Uri.fromFile(source))
        val outputs = mutableListOf<File>()
        try {
            store.updateProgress(imported.id, 2, .4f, 1.5f, 3)
            store.toggleBookmark(imported.id, 0); store.toggleBookmark(imported.id, 2)
            val extracted = tools.extract(store.file(imported), listOf(2, 0)).also { outputs.add(it) }
            val reordered = store.replaceContent(imported.id, extracted, 2, imported.contentHash, listOf(2, 0))
            assertEquals(0, reordered.page)
            assertEquals(.4f, reordered.offset, .001f)
            assertEquals(listOf(0, 1), reordered.bookmarks)
            val deleted = tools.deletePages(store.file(reordered), listOf(0)).also { outputs.add(it) }
            val shortened = store.replaceContent(imported.id, deleted, 1, reordered.contentHash, listOf(1))
            assertEquals(0, shortened.page)
            assertEquals(0f, shortened.offset, .001f)
            assertEquals(listOf(0), shortened.bookmarks)
            assertEquals(1, tools.properties(store.file(shortened)).pages)
        } finally { store.remove(imported.id); outputs.forEach { it.delete() }; source.delete() }
    }
}
