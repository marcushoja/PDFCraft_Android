package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class LocalPdfReplacementTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun pdf(name: String, text: String) = temporary.newFile(name).apply {
        writeText("%PDF-1.7\n$text\n%%EOF\n")
    }

    @Test fun replacementRetainsSourceAndChangesRevisionEvenForSameSizeFiles() {
        val old = pdf("local.pdf", "OLD")
        val source = pdf("result.pdf", "NEW")
        val original = source.readBytes()
        val stamp = old.lastModified()
        old.inputStream().use { openReader ->
            LocalPdfReplacement(old, source).use { staged ->
                val hash = MessageDigest.getInstance("SHA-256").digest(original)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hash, staged.contentHash)
                assertEquals("committed", staged.commit { "committed" })
            }
            assertTrue("Existing readers retain the original inode", openReader.bufferedReader().readText().contains("OLD"))
        }
        assertArrayEquals(original, old.readBytes())
        assertArrayEquals(original, source.readBytes())
        assertTrue(old.lastModified() > stamp)
        assertEquals(setOf("local.pdf", "result.pdf"), temporary.root.list()!!.toSet())
    }

    @Test fun failedMetadataCommitRestoresPreviousBytesAndRevision() {
        val old = pdf("local.pdf", "OLD")
        val source = pdf("result.pdf", "NEW")
        val bytes = old.readBytes()
        val stamp = old.lastModified()
        LocalPdfReplacement(old, source).use { staged ->
            assertTrue(runCatching { staged.commit<Unit> { throw IOException("Metadata failure") } }.isFailure)
        }
        assertArrayEquals(bytes, old.readBytes())
        assertEquals(stamp, old.lastModified())
        assertEquals(setOf("local.pdf", "result.pdf"), temporary.root.list()!!.toSet())
    }

    @Test fun malformedOutputIsRejectedWithoutTouchingTheLibraryFile() {
        val old = pdf("local.pdf", "OLD")
        val invalid = temporary.newFile("invalid.pdf").apply { writeText("not a PDF") }
        val bytes = old.readBytes()
        assertTrue(runCatching { LocalPdfReplacement(old, invalid).close() }.isFailure)
        assertArrayEquals(bytes, old.readBytes())
        assertEquals(setOf("local.pdf", "invalid.pdf"), temporary.root.list()!!.toSet())
    }

    @Test fun abandonedAndRepeatedStagesDoNotLeaveCopiesBehind() {
        val old = pdf("local.pdf", "OLD")
        val source = pdf("result.pdf", "NEW")
        LocalPdfReplacement(old, source).use { }
        assertTrue(old.readText().contains("OLD"))
        repeat(3) { LocalPdfReplacement(old, source).use { it.commit { } } }
        assertTrue(old.readText().contains("NEW"))
        assertEquals(setOf("local.pdf", "result.pdf"), temporary.root.list()!!.toSet())
    }

    @Test fun cancelledStagingDoesNotReplaceTheDocument() {
        val old = pdf("local.pdf", "OLD")
        val source = pdf("result.pdf", "NEW")
        Thread.currentThread().interrupt()
        try { assertTrue(runCatching { LocalPdfReplacement(old, source).close() }.isFailure) }
        finally { Thread.interrupted() }
        assertTrue(old.readText().contains("OLD"))
        assertEquals(setOf("local.pdf", "result.pdf"), temporary.root.list()!!.toSet())
    }
}
