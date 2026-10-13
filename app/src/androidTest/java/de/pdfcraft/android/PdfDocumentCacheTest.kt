package de.pdfcraft.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PdfDocumentCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val handles = mutableListOf<PreparedPdf>()

    @Before fun setup() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "document-cache-test-${System.nanoTime()}")
        check(root.mkdirs())
    }

    @After fun cleanup() {
        handles.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun hold(handle: PreparedPdf): PreparedPdf = handle.also { handles.add(it) }

    private fun sample(markup: Boolean = false, prefix: String = "source-", printed: Boolean = true): File {
        val file = File.createTempFile(prefix, ".pdf", root)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(300f, 400f))
            document.addPage(page)
            if (markup) {
                val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                annotation.rectangle = PDRectangle(30f, 100f, 80f, 20f)
                annotation.quadPoints = floatArrayOf(30f, 120f, 110f, 120f, 30f, 100f, 110f, 100f)
                annotation.color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                annotation.isPrinted = printed
                page.annotations = listOf(annotation)
            }
            document.save(file)
        }
        return file
    }

    @Test fun peekIsCacheOnlyAndPlainHitDoesNotRebuildOrRewriteMetadata() {
        val source = sample()
        assertNull(PdfAnnotationRendering.peek(source, root))
        val builds = AtomicInteger()
        hold(PdfDocumentRenderCache.prepare(source, root, false) {
            builds.incrementAndGet()
            PdfAnnotationRendering.prepare(source, File(root, "inner"))
        })
        val metadata = File(root, "pdf-render-v1").listFiles()!!.single()
        assertTrue(metadata.length() < 256)
        val bytes = metadata.readBytes()
        repeat(3) {
            val cached = hold(PdfDocumentRenderCache.prepare(source, root, false) {
                fail("Plain cache hit must not invoke PDFBox")
                PreparedPdf(source, false)
            })
            assertEquals(source, cached.file)
            assertFalse(cached.temporary)
        }
        assertEquals(1, builds.get())
        assertArrayEquals(bytes, metadata.readBytes())
        assertEquals(source, hold(PdfAnnotationRendering.peek(source, root)!!).file)
    }

    @Test fun annotatedCopiesAreSharedLeasedAndSourceIsUntouched() {
        val source = sample(markup = true)
        val bytes = source.readBytes()
        val first = hold(PdfAnnotationRendering.prepare(source, root))
        val second = hold(PdfAnnotationRendering.peek(source, root)!!)
        assertNotEquals(source, first.file)
        assertEquals(first.file, second.file)
        assertFalse(first.temporary)
        first.close()
        first.close()
        assertTrue(second.file.isFile)
        PDDocument.load(second.file).use { assertTrue(it.getPage(0).annotations.isEmpty()) }
        second.close()
        assertTrue("Shared persistent file survives closing handles", first.file.isFile)
        assertEquals(first.file, hold(PdfAnnotationRendering.prepare(source, root)).file)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun identityInvalidatesOnTouchLengthAndCanonicalPath() {
        val source = sample()
        hold(PdfAnnotationRendering.prepare(source, root))
        val alias = File(source.parentFile, "./${source.name}")
        assertNotNull(hold(PdfAnnotationRendering.peek(alias, root)!!))
        val other = File(root, "other.pdf").also { source.copyTo(it) }
        assertNull(PdfAnnotationRendering.peek(other, root))
        assertTrue(source.setLastModified(source.lastModified() + 10_000))
        assertNull(PdfAnnotationRendering.peek(source, root))
        hold(PdfAnnotationRendering.prepare(source, root))
        source.appendText("\n% extra trailing comment\n")
        assertNull(PdfAnnotationRendering.peek(source, root))
    }

    @Test fun printVisibilityUsesSeparateCacheKey() {
        val source = sample(markup = true, printed = false)
        val view = hold(PdfAnnotationRendering.prepare(source, root))
        assertNotEquals(source, view.file)
        assertNull(PdfAnnotationRendering.peek(source, root, forPrinting = true))
        val print = hold(PdfAnnotationRendering.prepare(source, root, forPrinting = true))
        assertEquals(source, print.file)
        assertEquals(source, hold(PdfAnnotationRendering.peek(source, root, true)!!).file)
        assertEquals(view.file, hold(PdfAnnotationRendering.peek(source, root)!!).file)
    }

    @Test fun unlockedCopiesNeverPersistStatusContentOrDimensions() {
        val source = sample(markup = true, prefix = "unlocked-")
        val first = hold(PdfAnnotationRendering.prepare(source, root))
        val second = hold(PdfAnnotationRendering.prepare(source, root))
        assertTrue(first.temporary)
        assertTrue(second.temporary)
        assertNotEquals(first.file, second.file)
        assertNull(PdfAnnotationRendering.peek(source, root))
        PdfPageSizeCache.write(source, root, floatArrayOf(300f), floatArrayOf(400f))
        assertNull(PdfPageSizeCache.read(source, root))
        assertFalse(File(root, "pdf-render-v1").exists())
        first.close()
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
    }

    @Test fun lockedDocumentsStayUncached() {
        val source = File(root, "locked.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(StandardProtectionPolicy("owner-secret", "reader-secret", AccessPermission()))
            document.save(source)
        }
        assertEquals(source, hold(PdfAnnotationRendering.prepare(source, root)).file)
        assertNull(PdfAnnotationRendering.peek(source, root))
        assertFalse(File(root, "pdf-render-v1").exists())
    }

    @Test fun corruptMetadataAndTruncatedCopyFallBackToRebuild() {
        val source = sample(markup = true)
        val first = hold(PdfAnnotationRendering.prepare(source, root))
        val metadata = File(root, "pdf-render-v1").listFiles()!!.single { it.extension == "json" }
        metadata.writeText("{broken")
        assertNull(PdfAnnotationRendering.peek(source, root))
        val rebuilt = hold(PdfAnnotationRendering.prepare(source, root))
        assertNotEquals(first.file, rebuilt.file)
        assertTrue("Corrupt replacement must not delete live old copy", first.file.exists())
        rebuilt.close()
        rebuilt.file.writeText("truncated")
        assertNull(PdfAnnotationRendering.peek(source, root))
        val repaired = hold(PdfAnnotationRendering.prepare(source, root))
        PDDocument.load(repaired.file).use { assertEquals(1, it.numberOfPages) }
        repaired.close()
        val sameLength = repaired.file.length().toInt()
        repaired.file.writeBytes(ByteArray(sameLength))
        assertNull("Same-size damaged header must also miss", PdfAnnotationRendering.peek(source, root))
        hold(PdfAnnotationRendering.prepare(source, root))
    }

    @Test fun expiryNeverDeletesAnActiveEntry() {
        val source = sample(markup = true)
        val first = hold(PdfAnnotationRendering.prepare(source, root))
        val second = hold(PdfAnnotationRendering.peek(source, root)!!)
        val metadata = File(root, "pdf-render-v1").listFiles()!!.single { it.extension == "json" }
        assertTrue(metadata.setLastModified(1L))
        first.close()
        hold(PdfAnnotationRendering.prepare(sample(), root))
        assertTrue(second.file.exists())
        assertTrue(metadata.exists())
        second.close()
        hold(PdfAnnotationRendering.prepare(sample(), root))
        assertFalse(second.file.exists())
        assertFalse(metadata.exists())
    }

    @Test fun unrelatedCachedPlainLookupDoesNotWaitForSlowScan() {
        val plain = sample()
        hold(PdfAnnotationRendering.prepare(plain, root))
        val slow = sample()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val scan = executor.submit<PreparedPdf> {
                PdfDocumentRenderCache.prepare(slow, root, false) {
                    started.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                    PreparedPdf(slow, false)
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val hit = executor.submit<PreparedPdf?> { PdfAnnotationRendering.peek(plain, root) }
            assertEquals(plain, hold(hit.get(2, TimeUnit.SECONDS)!!).file)
            finish.countDown()
            hold(scan.get(5, TimeUnit.SECONDS))
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun failedMetadataPublicationKeepsUniqueFallbackProtectedUntilClose() {
        val source = sample(markup = true)
        val cache = File(root, "pdf-render-v1").also { check(it.mkdirs()) }
        val key = PdfDocumentRenderCache.digest(PdfDocumentRenderCache.identity(source, false))
        // A directory at the final metadata path deliberately prevents atomic publication.
        check(File(cache, "$key.json").mkdir())
        val fallback = hold(PdfAnnotationRendering.prepare(source, root))
        assertTrue(fallback.temporary)
        assertNull(PdfAnnotationRendering.peek(source, root))
        assertTrue(fallback.file.setLastModified(1L))
        hold(PdfAnnotationRendering.prepare(sample(), root))
        assertTrue("Even failed publications must protect live fallback files", fallback.file.exists())
        PDDocument.load(fallback.file).use { assertEquals(1, it.numberOfPages) }
        fallback.close()
        assertFalse(fallback.file.exists())
    }

    @Test fun cacheDirectoryAliasesShareLeaseProtection() {
        val source = sample(markup = true)
        val alias = File(root, ".")
        val first = hold(PdfAnnotationRendering.prepare(source, alias))
        val second = hold(PdfAnnotationRendering.peek(source, root)!!)
        assertEquals(first.file, second.file)
        val metadata = File(root, "pdf-render-v1").listFiles()!!.single { it.extension == "json" }
        assertTrue(metadata.setLastModified(1L))
        first.close()
        hold(PdfAnnotationRendering.prepare(sample(), alias))
        assertTrue(second.file.exists())
    }

    @Test fun concurrentSameKeyBuildIsDeduplicated() {
        val source = sample()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<PreparedPdf> {
                PdfDocumentRenderCache.prepare(source, root, false) {
                    calls.incrementAndGet()
                    started.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                    PreparedPdf(source, false)
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<PreparedPdf> {
                PdfDocumentRenderCache.prepare(source, root, false) {
                    calls.incrementAndGet()
                    PreparedPdf(source, false)
                }
            }
            finish.countDown()
            hold(first.get(5, TimeUnit.SECONDS))
            hold(second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun interruptedPreparationPublishesNothing() {
        val source = sample(markup = true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cancelled = executor.submit<Boolean> {
                Thread.currentThread().interrupt()
                try {
                    PdfAnnotationRendering.prepare(source, root).close()
                    false
                } catch (_: InterruptedIOException) { true }
                finally { Thread.interrupted() }
            }
            assertTrue(cancelled.get(5, TimeUnit.SECONDS))
            assertNull(PdfAnnotationRendering.peek(source, root))
        } finally { executor.shutdownNow() }
    }

    @Test fun dimensionsRoundTripInvalidateAndRejectInvalidValues() {
        val source = sample()
        val widths = floatArrayOf(300f, 400f)
        val heights = floatArrayOf(400f, 300f)
        assertNull(PdfPageSizeCache.read(source, root))
        PdfPageSizeCache.write(source, root, widths, heights)
        val cached = PdfPageSizeCache.read(source, root)!!
        assertArrayEquals(widths, cached.first, 0f)
        assertArrayEquals(heights, cached.second, 0f)
        PdfPageSizeCache.write(source, root, floatArrayOf(Float.NaN), floatArrayOf(0f))
        assertArrayEquals(widths, PdfPageSizeCache.read(source, root)!!.first, 0f)
        assertTrue(source.setLastModified(source.lastModified() + 10_000))
        assertNull(PdfPageSizeCache.read(source, root))
    }
}
