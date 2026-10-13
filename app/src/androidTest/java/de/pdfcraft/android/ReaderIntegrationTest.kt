package de.pdfcraft.android

import android.content.Intent
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on a real Android PDF renderer, not a mocked JVM graphics stack. */
@RunWith(AndroidJUnit4::class)
class ReaderIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun sample(label: String = ""): File {
        val file = File(instrumentation.targetContext.cacheDir, if (label.isEmpty()) "reader-integration.pdf" else "content-integration.pdf")
        val pdf = AndroidPdfDocument()
        try {
            repeat(8) { index ->
                val page = pdf.startPage(AndroidPdfDocument.PageInfo.Builder(480, if (index == 7) 300 else 720, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
                page.canvas.drawText("PDFCraft $label — Seite ${index + 1}", 32f, 64f, paint)
                paint.textSize = 12f
                repeat(50) { line -> page.canvas.drawText("Lesetest Zeile ${line + 1} mit eingebettetem Text", 32f, 110f + line * 11.5f, paint) }
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    @Test fun testImportDeduplicateProgressBookmarksAndRemoval() {
        val store = DocumentStore(instrumentation.targetContext)
        val source = sample("Store-${System.nanoTime()}")
        val first = store.importDocument(Uri.fromFile(source))
        store.updateProgress(first.id, 4, 0.35f, 2f, 8)
        store.toggleBookmark(first.id, 4)
        val again = store.importDocument(Uri.fromFile(source))
        assertEquals(first.id, again.id)
        assertEquals(4, again.page)
        assertEquals(0.35f, again.offset, 0.001f)
        assertEquals(2f, again.zoom, 0.001f)
        assertEquals(listOf(4), again.bookmarks)
        assertEquals(1, store.list().count { it.id == first.id })
        store.remove(first.id)
        assertNull(store.get(first.id))
        assertTrue("Original must remain intact", source.exists())
        assertFalse(store.file(first).exists())
    }

    @Test fun testInvalidFileDoesNotEnterLibrary() {
        val store = DocumentStore(instrumentation.targetContext)
        val bad = File(instrumentation.targetContext.cacheDir, "not-a-pdf.txt").apply { writeText("not a PDF") }
        val before = store.list().size
        try {
            store.importDocument(Uri.fromFile(bad))
            fail("Expected import failure")
        } catch (_: java.io.IOException) { }
        assertEquals(before, store.list().size)
    }

    @Test fun testContentUriImport() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PDFCraft-Content-Test.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Could not create content URI")
        try {
            resolver.openOutputStream(uri)!!.use { output -> sample("Content URI").inputStream().use { it.copyTo(output) } }
            val store = DocumentStore(instrumentation.targetContext)
            val doc = store.importDocument(uri)
            assertEquals("PDFCraft-Content-Test.pdf", doc.name)
            assertTrue(store.file(doc).length() > 0)
            store.remove(doc.id)
            assertTrue("Source content must not be deleted", resolver.openInputStream(uri)!!.use { it.read() >= 0 })
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun testRealRendererLoadsAndRestoresPosition() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        val loaded = CountDownLatch(1)
        var failure: String? = null
        lateinit var reader: PdfReaderView
        var reportedZoom = 0f
        var reportedOffset = 0f
        val source = sample()
        instrumentation.runOnMainSync {
            reader = PdfReaderView(activity)
            activity.setContentView(reader)
            reader.onDocumentLoaded = { loaded.countDown() }
            reader.onError = { failure = it; loaded.countDown() }
            reader.onPositionChanged = { _, offset, zoom -> reportedZoom = zoom; reportedOffset = offset }
            reader.open(source, 4, 0.25f, 1.5f)
        }
        assertTrue("Renderer did not load", loaded.await(20, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull(failure)
        instrumentation.runOnMainSync {
            assertEquals(8, reader.pageCount)
            assertEquals(4, reader.currentPage)
            assertEquals("Zoom restored", 1.5f, reportedZoom, 0.001f)
            assertEquals("Offset restored", 0.25f, reportedOffset, 0.001f)
            reader.resetZoom()
            reader.goToPage(4)
            val start = SystemClock.uptimeMillis()
            fun touch(action: Int, time: Long, x: Float, y: Float) {
                val event = MotionEvent.obtain(start, time, action, x, y, 0)
                reader.onTouchEvent(event)
                event.recycle()
            }
            touch(MotionEvent.ACTION_DOWN, start, 300f, 500f)
            touch(MotionEvent.ACTION_UP, start + 20, 300f, 500f)
            touch(MotionEvent.ACTION_DOWN, start + 110, 300f, 500f)
            touch(MotionEvent.ACTION_UP, start + 130, 300f, 500f)
            assertEquals("Double-tap should zoom", 2.5f, reportedZoom, 0.001f)
            val pinchStart = start + 600
            fun pinch(action: Int, time: Long, left: Float, right: Float, count: Int = 2) {
                val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                    x = if (index == 0) left else right; y = 500f; pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(pinchStart, time, action, count, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                reader.onTouchEvent(event); event.recycle()
            }
            pinch(MotionEvent.ACTION_DOWN, pinchStart, 400f, 600f, 1)
            pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pinchStart + 20, 400f, 600f)
            repeat(9) { step -> pinch(MotionEvent.ACTION_MOVE, pinchStart + 40 + step * 20L, 400f - step * 48, 600f + step * 48) }
            pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pinchStart + 240, 16f, 984f)
            pinch(MotionEvent.ACTION_UP, pinchStart + 260, 16f, 984f, 1)
            assertTrue("Pinch should increase zoom", reportedZoom > 2.5f)
            reader.goToPage(7)
            assertEquals(7, reader.currentPage)
            reader.goToPage(500)
            assertEquals(7, reader.currentPage)
            reader.setReadingMode(ReadingMode.SINGLE)
            reader.goToPage(2)
            assertEquals(2, reader.currentPage)
            reader.setDarkPages(true)
            reader.resetZoom()
            reader.setReadingMode(ReadingMode.CONTINUOUS)
            reader.setFitPage(true)
            reader.goToPage(7)
            assertEquals("Short final page should remain selected", 7, reader.currentPage)
            reader.layout(0, 0, 900, 420)
            assertEquals("Rotation must preserve page", 7, reader.currentPage)
            reader.close()
            activity.finish()
        }
    }
}
