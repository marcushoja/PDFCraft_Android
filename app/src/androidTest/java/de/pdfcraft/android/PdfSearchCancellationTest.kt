package de.pdfcraft.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PdfSearchCancellationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun immediateAndRunningCancellationLeaveExtractionResponsiveAndSuppressCallbacks() {
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val large = createPdf(300, 100)
        // Reusable synthetic fixture for the real UI cancellation smoke test.
        large.copyTo(File(context.cacheDir,"large-reader-test.pdf"),overwrite=true)
        val small = createPdf(1, 1)
        val service = PdfTextService(context)
        val callbacks = AtomicInteger()
        try {
            // Cancel on the main thread before any result/error/progress can be delivered.
            instrumentation.runOnMainSync {
                service.search(large, "not-present", { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() },
                    onProgress = { _, _ -> callbacks.incrementAndGet() })
                service.cancelSearch()
            }
            assertExtractionResponsive(service, small)
            instrumentation.waitForIdleSync()
            assertEquals(0, callbacks.get())

            // Progress proves the second search is running, rather than merely queued.
            val cancelled = CountDownLatch(1)
            val progressCallbacks = AtomicInteger()
            service.search(large, "not-present", { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() },
                onProgress = { page, total ->
                    assertTrue(page in 1..total)
                    assertEquals(300, total)
                    progressCallbacks.incrementAndGet()
                    service.cancelSearch()
                    cancelled.countDown()
                })
            assertTrue("Search did not start", cancelled.await(15, TimeUnit.SECONDS))
            assertExtractionResponsive(service, small)
            instrumentation.waitForIdleSync()
            assertEquals(0, callbacks.get())
            assertEquals("Cancelled progress must not be delivered", 1, progressCallbacks.get())

            val searched = CountDownLatch(1)
            val hits = AtomicReference<List<SearchHit>>()
            val error = AtomicReference<String>()
            service.search(small, "äpfel", { hits.set(it); searched.countDown() }, { error.set(it); searched.countDown() })
            assertTrue(searched.await(5, TimeUnit.SECONDS))
            assertNull(error.get())
            assertTrue(hits.get().single().snippet.contains("ÄPFEL"))
        } finally {
            service.close()
            large.delete()
            small.delete()
        }
    }

    private fun assertExtractionResponsive(service: PdfTextService, file: File) {
        val extracted = CountDownLatch(1)
        val text = AtomicReference<String>()
        val error = AtomicReference<String>()
        service.extract(file, 0, { text.set(it); extracted.countDown() }, { error.set(it); extracted.countDown() })
        assertTrue("Cancelled search blocked extraction", extracted.await(5, TimeUnit.SECONDS))
        assertNull(error.get())
        assertTrue(text.get().contains("ÄPFEL"))
    }

    private fun createPdf(pages: Int, lines: Int): File {
        val file = File.createTempFile("search-cancel-", ".pdf", instrumentation.targetContext.cacheDir)
        PDDocument().use { document ->
            repeat(pages) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 6f)
                    content.newLineAtOffset(20f, 750f)
                    repeat(lines) {
                        content.showText("ÄPFEL ÖL ÜBER selectable text " + "repeated large text ".repeat(8))
                        content.newLineAtOffset(0f, -7f)
                    }
                    content.endText()
                }
            }
            document.save(file)
        }
        return file
    }
}
