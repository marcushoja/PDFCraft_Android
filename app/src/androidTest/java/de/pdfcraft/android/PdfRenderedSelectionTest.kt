package de.pdfcraft.android

import android.content.Intent
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfRenderedSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(): File {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("rendered-selection-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle(400f, 600f))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 20f)
                    stream.newLineAtOffset(40f, 480f)
                    stream.showText(if (index == 0) "Alpha beta gamma" else "Second page text")
                    stream.endText()
                }
            }
            document.save(file)
        }
        return file
    }

    private fun withReader(zoom: Float = 1f, action: (PdfReaderView, File) -> Unit) {
        val file = fixture()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val loaded = CountDownLatch(1)
        lateinit var reader: PdfReaderView
        try {
            instrumentation.runOnMainSync {
                reader = PdfReaderView(activity)
                activity.setContentView(reader)
                reader.onFirstPageRendered = { loaded.countDown() }
                reader.open(file, initialZoom = zoom)
            }
            assertTrue("PDF must render", loaded.await(20, TimeUnit.SECONDS))
            action(reader, file)
        } finally {
            instrumentation.runOnMainSync { reader.close(); activity.finish() }
            file.delete()
        }
    }

    private fun pageRect(reader: PdfReaderView): RectF {
        // Keep test-only layout introspection out of the reader's production API.
        val method = PdfReaderView::class.java.getDeclaredMethod("pageRect", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(reader, 0) as RectF
    }

    private fun glyphPoint(reader: PdfReaderView, glyph: PdfTextGlyph): PointF {
        val page = pageRect(reader)
        return PointF(page.left + glyph.bounds.centerX() * page.width(),
            page.top + glyph.bounds.centerY() * page.height())
    }

    private fun touch(reader: PdfReaderView, action: Int, point: PointF, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        reader.onTouchEvent(event)
        event.recycle()
    }

    private fun longPress(reader: PdfReaderView, point: PointF): Long {
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync { touch(reader, MotionEvent.ACTION_DOWN, point, down) }
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
        return down
    }

    @Test fun longPressSelectsWholeWordAndHandleDragSelectsExactRenderedSpan() = withReader { reader, file ->
        val text = PdfPageTextExtractor.extract(file, 0)
        val selected = CountDownLatch(1)
        var taps = 0
        lateinit var point: PointF
        instrumentation.runOnMainSync {
            reader.onSelectionChanged = { _, selection -> if (selection != null) selected.countDown() }
            reader.onTap = { taps++ }
            point = glyphPoint(reader, text.glyphs[1])
        }
        val down = longPress(reader, point)
        assertTrue(selected.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertEquals("Alpha", reader.selectedTextRange!!.text)
            touch(reader, MotionEvent.ACTION_UP, point, down)
            val handle = reader.selectionHandleCenters()[1]
            val target = glyphPoint(reader, text.glyphs.last()).apply { y += 9f * context.resources.displayMetrics.density }
            val drag = SystemClock.uptimeMillis()
            touch(reader, MotionEvent.ACTION_DOWN, handle, drag)
            touch(reader, MotionEvent.ACTION_MOVE, target, drag)
            touch(reader, MotionEvent.ACTION_UP, target, drag)
            assertEquals("Alpha beta gamma", reader.selectedTextRange!!.text)
            assertEquals(1, reader.selectedTextRange!!.pdfQuads.size)
            assertEquals(0, reader.currentPage)
            assertEquals(0, taps)
            assertFalse(reader.selectionContentRect().isEmpty)
            assertTrue(reader.selectAllText())
            val outside = PointF(reader.width * .9f, reader.height * .9f)
            val tap = SystemClock.uptimeMillis()
            touch(reader, MotionEvent.ACTION_DOWN, outside, tap)
            touch(reader, MotionEvent.ACTION_UP, outside, tap)
            assertNull(reader.selectedTextRange)
        }
        Thread.sleep(350)
        instrumentation.runOnMainSync { assertEquals("Clearing must not toggle chrome", 0, taps) }
    }

    @Test fun selectionGeometryTracksZoomAndPageNavigationClearsIt() = withReader(2f) { reader, file ->
        val text = PdfPageTextExtractor.extract(file, 0)
        val selected = CountDownLatch(1)
        lateinit var point: PointF
        instrumentation.runOnMainSync {
            reader.onSelectionChanged = { _, selection -> if (selection != null) selected.countDown() }
            // At 2x the centered horizontal scroll can hide Alpha; gamma remains visible.
            point = glyphPoint(reader, text.glyphs.last())
        }
        val down = longPress(reader, point)
        assertTrue(selected.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            touch(reader, MotionEvent.ACTION_UP, point, down)
            val selection = reader.selectedTextRange!!
            val before = reader.selectionHandleCenters()[1]
            reader.resetZoom()
            assertSame(selection, reader.selectedTextRange)
            val after = reader.selectionHandleCenters()[1]
            assertTrue(before.x != after.x || before.y != after.y)
            val rect = pageRect(reader)
            val expected = rect.left + text.glyphs.last().bounds.right * rect.width()
            assertEquals(expected, after.x, .1f)
            assertFalse(reader.selectionContentRect().isEmpty)
            reader.goToPage(1)
            assertNull(reader.selectedTextRange)
            assertEquals(1, reader.currentPage)
        }
    }

    @Test fun originalExtractionPermissionAlsoAppliesToDecryptedViewingCopies() = withReader { reader,file ->
        val text=PdfPageTextExtractor.extract(file,0)
        lateinit var point:PointF
        instrumentation.runOnMainSync { reader.textSelectionAllowed=false;point=glyphPoint(reader,text.glyphs[1]) }
        val down=longPress(reader,point)
        Thread.sleep(300)
        instrumentation.runOnMainSync {
            touch(reader,MotionEvent.ACTION_UP,point,down)
            assertNull(reader.selectedTextRange)
            assertFalse(reader.selectAllText())
        }
    }

    @Test fun closingWithQueuedExtractionDoesNotDeliverSelection() = withReader { reader, file ->
        val text = PdfPageTextExtractor.extract(file, 0)
        var callbacks = 0
        lateinit var point: PointF
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        instrumentation.runOnMainSync {
            reader.onSelectionChanged = { _, _ -> callbacks++ }
            point = glyphPoint(reader, text.glyphs[1])
            val field = PdfReaderView::class.java.getDeclaredField("worker").apply { isAccessible = true }
            val worker = field.get(reader)!!
            val executorField = worker.javaClass.getDeclaredField("executor").apply { isAccessible = true }
            val executor = executorField.get(worker) as java.util.concurrent.ExecutorService
            executor.execute { blocked.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        try {
            val down = longPress(reader, point)
            instrumentation.runOnMainSync {
                touch(reader, MotionEvent.ACTION_UP, point, down)
                reader.close()
            }
        } finally { release.countDown() }
        Thread.sleep(500)
        instrumentation.runOnMainSync { assertEquals(0, callbacks); assertNull(reader.selectedTextRange) }
    }
}
