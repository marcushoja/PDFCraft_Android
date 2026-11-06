package de.pdfcraft.android

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderTapNavigationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(60)
        }
        fail("Reader did not reach the expected state")
    }

    private fun tap(reader: PdfReaderView, fraction: Float) {
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    reader.width * fraction, reader.height * .5f, 0)
                reader.onTouchEvent(event)
                event.recycle()
            }
        }
        // Let single-tap confirmation and the page-turn animation finish, including boundary taps.
        Thread.sleep(900)
    }

    private fun icon(parent: ViewGroup, description: String): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.contentDescription == UiText.t(context, description)) return child
            if (child is ViewGroup) icon(child, description)?.let { return it }
        }
        return null
    }

    @Test fun toolbarIconsAreSwappedAndPdfTapsOnlyNavigateInNormalAndFullscreenModes() {
        val source = File.createTempFile("reader-taps-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 500, index + 1).create())
                page.canvas.drawText("${source.name}: $index", 20f, 50f, Paint().apply { textSize = 12f })
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val store = DocumentStore(context)
        val doc = store.importDocument(Uri.fromFile(source))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun bar(name: String) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity) as ViewGroup
        lateinit var reader: PdfReaderView
        lateinit var top: ViewGroup
        lateinit var bottom: ViewGroup
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openDocument", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, doc.id)
                reader = activity.findViewById(R.id.pdf_canvas)
                top = bar("topBar"); bottom = bar("bottomBar")
                assertNotNull(icon(top, "Seiten"))
                assertNull(icon(top, "Vollbild"))
                assertNotNull(icon(bottom, "Vollbild"))
                assertNull(icon(bottom, "Seiten"))
            }
            awaitUi { reader.pageCount == 3 && reader.width > 0 }
            instrumentation.runOnMainSync { reader.setReadingMode(ReadingMode.SINGLE); reader.setFitPage(true) }
            for (fullscreen in listOf(false, true)) {
                if (fullscreen) instrumentation.runOnMainSync { icon(bottom, "Vollbild")!!.performClick() }
                for ((fraction, expectedPage) in listOf(.25f to 0, .75f to 1, .75f to 2, .75f to 2,
                    .25f to 1, .25f to 0)) {
                    tap(reader, fraction)
                    awaitUi { reader.currentPage == expectedPage }
                    instrumentation.runOnMainSync {
                        assertEquals(if (fullscreen) View.GONE else View.VISIBLE, top.visibility)
                        assertEquals(if (fullscreen) View.GONE else View.VISIBLE, bottom.visibility)
                    }
                }
            }
            instrumentation.runOnMainSync {
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                icon(content, "Vollbild verlassen")!!.performClick()
                assertEquals(View.VISIBLE, top.visibility)
                assertEquals(View.VISIBLE, bottom.visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            store.remove(doc.id)
            source.delete()
        }
    }
}
