package de.pdfcraft.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ThumbnailLoaderIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class TrackedImageView(context: Context) : ImageView(context) {
        var imageUpdates = 0
        var layoutRequests = 0
        override fun setImageDrawable(drawable: Drawable?) {
            imageUpdates++
            super.setImageDrawable(drawable)
        }
        override fun setImageBitmap(bitmap: Bitmap?) {
            imageUpdates++
            super.setImageBitmap(bitmap)
        }
        override fun requestLayout() {
            layoutRequests++
            super.requestLayout()
        }
    }

    private fun pdf(prefix: String = "thumbnail-test-", pages: Int = 8, color: Int = Color.RED): File {
        val file = File.createTempFile(prefix, ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(30f, 30f, 210f, 290f, Paint().apply { this.color = color })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun idle(loader: ThumbnailLoader) {
        assertTrue("worker completion", loader.awaitIdle(30, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun finish(loader: ThumbnailLoader) {
        loader.close()
        assertTrue("native leases closed", loader.awaitClosed(30, TimeUnit.SECONDS))
    }

    @Test fun eightPagesShareOneRendererAndRebindsHitCache() {
        val source = pdf()
        val loader = ThumbnailLoader(context)
        val cells = mutableListOf<ImageView>()
        try {
            instrumentation.runOnMainSync {
                repeat(8) { page -> cells.add(ImageView(context).also { loader.load(source, page, 127, it) }) }
            }
            idle(loader)
            assertEquals(1L, loader.stats().rendererOpens)
            assertEquals(8L, loader.stats().renderedPages)
            instrumentation.runOnMainSync {
                cells.forEachIndexed { page, cell ->
                    assertNotNull(cell.drawable)
                    loader.cancel(cell) // A recycled/new binding still exercises the memory cache.
                    loader.load(source, page, 128, cell)
                }
            }
            idle(loader)
            assertEquals(8L, loader.stats().cacheHits)
            assertEquals(8L, loader.stats().renderedPages)
            assertEquals(0, loader.stats().outstandingRequests)
        } finally { finish(loader); source.delete() }
    }

    @Test fun annotationFreeInspectionKeepsTheOriginalRendererAndThumbnailCache() {
        val source=pdf(pages=1);val loader=ThumbnailLoader(context)
        lateinit var image:ImageView
        try {
            instrumentation.runOnMainSync { image=ImageView(context);loader.load(source,0,128,image) }
            idle(loader)
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            PdfAnnotationRendering.prepare(source,context.cacheDir).use { assertEquals(source,it.file) }
            instrumentation.runOnMainSync { loader.cancel(image);loader.load(source,0,128,image) }
            idle(loader)
            assertEquals(1L,loader.stats().rendererOpens)
            assertEquals(1L,loader.stats().renderedPages)
            assertEquals(1L,loader.stats().cacheHits)
        } finally { finish(loader);source.delete() }
    }

    @Test fun repeatedPendingAndCompletedBindsDoNotClearImagesOrQueueMoreWork() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val loader = ThumbnailLoader(context, executor)
        val source = pdf(pages = 1)
        lateinit var cell: TrackedImageView
        try {
            instrumentation.runOnMainSync {
                cell = TrackedImageView(context)
                loader.load(source, 0, 127, cell)
                val updates = cell.imageUpdates
                repeat(32) { loader.load(source, 0, 128, cell) }
                assertEquals(updates, cell.imageUpdates)
                assertEquals(1, loader.stats().outstandingRequests)
                assertEquals(1, loader.stats().queuedJobs)
                assertEquals(0L, loader.stats().cancelledRequests)
            }
            gate.countDown()
            idle(loader)
            instrumentation.runOnMainSync {
                val drawable = cell.drawable
                assertNotNull(drawable)
                val updates = cell.imageUpdates
                repeat(32) {
                    cell.requestLayout() // Simulate GridView rebinding after layout requests.
                    val layouts = cell.layoutRequests
                    loader.load(source, 0, 128, cell)
                    assertSame(drawable, cell.drawable)
                    assertEquals(updates, cell.imageUpdates)
                    assertEquals(layouts, cell.layoutRequests)
                    assertEquals(0, loader.stats().outstandingRequests)
                    assertEquals(0, loader.stats().queuedJobs)
                }
            }
            idle(loader)
            assertEquals(1L, loader.stats().renderedPages)
            assertEquals(0L, loader.stats().cacheHits)
        } finally { gate.countDown(); finish(loader); source.delete() }
    }

    @Test fun resizeKeepsDisplayedImageAndCancelInvalidatesCompletedBinding() {
        val executor = Executors.newSingleThreadExecutor()
        val loader = ThumbnailLoader(context, executor)
        val source = pdf(pages = 1)
        val gate = CountDownLatch(1)
        lateinit var cell: ImageView
        try {
            instrumentation.runOnMainSync { cell = ImageView(context); loader.load(source, 0, 128, cell) }
            idle(loader)
            executor.execute { gate.await() }
            instrumentation.runOnMainSync {
                val drawable = cell.drawable
                loader.load(source, 0, 224, cell)
                assertSame(drawable, cell.drawable)
                repeat(8) { loader.load(source, 0, 224, cell) }
                assertSame(drawable, cell.drawable)
                assertEquals(1, loader.stats().outstandingRequests)
            }
            gate.countDown()
            idle(loader)
            assertEquals(2L, loader.stats().renderedPages)
            instrumentation.runOnMainSync { loader.cancel(cell); loader.load(source, 0, 224, cell) }
            idle(loader)
            assertEquals(1L, loader.stats().cacheHits)
            assertEquals(2L, loader.stats().renderedPages)
        } finally { gate.countDown(); finish(loader); source.delete() }
    }

    @Test fun offscreenRequestsAreRemovedBeforeOpeningAnyPdf() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val loader = ThumbnailLoader(context, executor)
        val source = pdf()
        val cells = mutableListOf<ImageView>()
        try {
            instrumentation.runOnMainSync {
                repeat(8) { page -> cells.add(ImageView(context).also { loader.load(source, page, 128, it) }) }
                cells.forEach { loader.cancel(it) }
            }
            assertEquals(0, loader.stats().queuedJobs)
            gate.countDown()
            idle(loader)
            assertEquals(0L, loader.stats().rendererOpens)
            assertEquals(0L, loader.stats().renderedPages)
            assertEquals(8L, loader.stats().cancelledRequests)
        } finally { gate.countDown(); finish(loader); source.delete() }
    }

    @Test fun duplicateTargetsShareWorkAndRecycledTargetGetsOnlyLatestPage() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val loader = ThumbnailLoader(context, executor)
        val source = pdf()
        val cells = mutableListOf<ImageView>()
        try {
            instrumentation.runOnMainSync {
                repeat(12) { cells.add(ImageView(context).also { loader.load(source, 0, 128, it) }) }
                loader.load(source, 1, 128, cells.first())
                assertEquals(2, loader.stats().queuedJobs)
            }
            gate.countDown()
            idle(loader)
            assertEquals(1L, loader.stats().rendererOpens)
            assertEquals(2L, loader.stats().renderedPages)
            instrumentation.runOnMainSync { cells.forEach { assertNotNull(it.drawable) } }
        } finally { gate.countDown(); finish(loader); source.delete() }
    }

    @Test fun preparedPublicationInvalidatesRawThumbnailWithoutPdfBoxPreparation() {
        val source = pdf(pages = 1)
        val blue = pdf(pages = 1, color = Color.BLUE)
        val loader = ThumbnailLoader(context)
        lateinit var cell: ImageView
        try {
            instrumentation.runOnMainSync { cell = ImageView(context); loader.load(source, 0, 128, cell) }
            idle(loader)
            instrumentation.runOnMainSync {
                val bitmap = (cell.drawable as BitmapDrawable).bitmap
                assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            }
            PdfDocumentRenderCache.prepare(source, context.cacheDir, false) {
                val copy = File.createTempFile("thumbnail-prepared-", ".pdf", context.cacheDir)
                blue.copyTo(copy, overwrite = true)
                PreparedPdf(copy, true)
            }.close()
            instrumentation.runOnMainSync {
                loader.cancel(cell) // Revision discovery happens on recycle/reopen, not every layout.
                loader.load(source, 0, 128, cell)
            }
            idle(loader)
            assertEquals(2L, loader.stats().rendererOpens)
            assertEquals(2L, loader.stats().renderedPages)
            instrumentation.runOnMainSync {
                val bitmap = (cell.drawable as BitmapDrawable).bitmap
                assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            }
        } finally { finish(loader); source.delete(); blue.delete() }
    }

    @Test fun unlockedSourcesNeverReadOrWritePersistentThumbnails() {
        val source = pdf(prefix = "unlocked-", pages = 1)
        var loader = ThumbnailLoader(context)
        lateinit var cell: ImageView
        val directory = File(context.cacheDir, "thumbs-native-v3")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        try {
            instrumentation.runOnMainSync { cell = ImageView(context); loader.load(source, 0, 128, cell) }
            idle(loader)
            assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
            finish(loader)
            loader = ThumbnailLoader(context)
            instrumentation.runOnMainSync { loader.load(source, 0, 128, cell) }
            idle(loader)
            assertEquals(1L, loader.stats().renderedPages)
            assertEquals(0L, loader.stats().cacheHits)
        } finally { finish(loader); source.delete() }
    }
}
