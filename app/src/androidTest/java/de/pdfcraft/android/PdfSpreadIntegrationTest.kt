package de.pdfcraft.android

import android.content.Intent
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfSpreadIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withReader(count: Int = 6, test: (PdfReaderView) -> Unit) {
        val context = instrumentation.targetContext
        val file = File.createTempFile("spread-layout-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(count) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(if (index % 2 == 0) 400 else 600,
                    if (index % 2 == 0) 800 else 400, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.rgb(40 + index * 20, 80, 180))
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var reader: PdfReaderView
        val loaded = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                reader = PdfReaderView(activity)
                activity.setContentView(reader)
                reader.onFirstPageRendered = { loaded.countDown() }
                reader.open(file)
            }
            assertTrue("Reader must render the fixture", loaded.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { test(reader) }
        } finally {
            instrumentation.runOnMainSync { reader.close(); activity.finish() }
            file.delete()
        }
    }

    private fun rect(reader: PdfReaderView, page: Int): RectF {
        val method = PdfReaderView::class.java.getDeclaredMethod("pageRect", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(reader, page) as RectF
    }

    @Test fun coverPairsStridesAndLastUnpairedPageKeepRealDocumentCount() = withReader { reader ->
        reader.setReadingMode(ReadingMode.BOOK)
        assertEquals(6, reader.pageCount)
        assertEquals(listOf(0), reader.visiblePages)
        reader.nextPage()
        assertEquals(listOf(1, 2), reader.visiblePages)
        reader.nextPage()
        assertEquals(listOf(3, 4), reader.visiblePages)
        reader.nextPage()
        assertEquals(listOf(5), reader.visiblePages)
        reader.nextPage()
        assertEquals(5, reader.currentPage)
        reader.previousPage()
        assertEquals(listOf(3, 4), reader.visiblePages)
        reader.setReadingMode(ReadingMode.SPREAD)
        reader.goToPage(0)
        reader.navigatePages(true, false)
        assertEquals(listOf(2, 3), reader.visiblePages)
        reader.navigatePages(false, false)
        assertEquals(listOf(0, 1), reader.visiblePages)
    }

    @Test fun rtlBookmarksModeChangesAndMixedSizeRectsPreserveExactRequestedPage() = withReader { reader ->
        reader.goToPage(4)
        reader.setReadingMode(ReadingMode.BOOK)
        assertEquals(4, reader.currentPage)
        assertEquals(listOf(3, 4), reader.visiblePages)
        reader.setRightToLeft(true)
        assertTrue(reader.isRightToLeft)
        assertEquals(ReadingMode.BOOK, reader.readingMode)
        assertEquals(4, reader.currentPage)
        val left = rect(reader, 4)
        val right = rect(reader, 3)
        assertEquals(left.right, right.left, .1f)
        for (page in listOf(3, 4)) {
            val bounds = rect(reader, page)
            assertTrue(bounds.left >= -.1f && bounds.top >= -.1f)
            assertTrue(bounds.right <= reader.width + .1f && bounds.bottom <= reader.height + .1f)
            assertEquals(if (page == 3) 1.5f else .5f, bounds.width() / bounds.height(), .001f)
        }
        reader.setReadingMode(ReadingMode.SINGLE)
        assertEquals(4, reader.currentPage)
        reader.setReadingMode(ReadingMode.SPREAD)
        assertEquals(4, reader.currentPage)
        reader.goToPage(1)
        assertEquals(1, reader.currentPage)
        assertEquals(listOf(0, 1), reader.visiblePages)
        reader.navigatePages(true, false)
        assertEquals(listOf(2, 3), reader.visiblePages)
        reader.navigatePages(false, false)
        assertEquals(listOf(0, 1), reader.visiblePages)
    }

    @Test fun comicIsGaplessFitWidthAndSpreadPhysicalSwipeRespectsRtl() = withReader(5) { reader ->
        reader.setFitPage(true)
        reader.setReadingMode(ReadingMode.COMIC)
        reader.goToPage(0)
        val first = rect(reader, 0)
        val second = rect(reader, 1)
        assertEquals(reader.width.toFloat(), first.width(), .1f)
        assertEquals(first.bottom, second.top, .1f)
        reader.setReadingMode(ReadingMode.SPREAD)
        reader.setRightToLeft(true)
        fun swipe(from: Float, to: Float) {
            val start = SystemClock.uptimeMillis()
            for ((action, x, time) in listOf(Triple(MotionEvent.ACTION_DOWN, from, 0L),
                Triple(MotionEvent.ACTION_MOVE, to, 200L), Triple(MotionEvent.ACTION_UP, to, 400L))) {
                val event = MotionEvent.obtain(start, start + time, action, x, reader.height * .5f, 0)
                reader.onTouchEvent(event)
                event.recycle()
            }
        }
        swipe(reader.width * .2f, reader.width * .8f)
        assertEquals(listOf(2, 3), reader.visiblePages)
        swipe(reader.width * .8f, reader.width * .2f)
        assertEquals(listOf(0, 1), reader.visiblePages)
        reader.goToPage(4)
        assertEquals(listOf(4), reader.visiblePages)
        assertEquals(5, reader.pageCount)
    }

    @Test fun resizedViewportKeepsExactBookmarkedPageInBothReadingDirections() = withReader { reader ->
        val width=reader.width;val height=reader.height
        reader.setReadingMode(ReadingMode.SPREAD)
        reader.goToPage(5)
        reader.layout(0,0,height,width)
        assertEquals(5,reader.currentPage)
        assertEquals(listOf(4,5),reader.visiblePages)
        reader.layout(0,0,width,height)
        assertEquals(5,reader.currentPage)
        reader.setRightToLeft(true);reader.goToPage(1)
        reader.layout(0,0,height,width)
        assertEquals(1,reader.currentPage)
        assertEquals(listOf(0,1),reader.visiblePages)
        reader.layout(0,0,width,height)
        assertEquals(1,reader.currentPage)
    }
}
