package de.pdfcraft.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfViewRotationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun sample(): File {
        val file = File.createTempFile("view-rotation-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300 + index * 100, 500, index + 1).create())
                val w = page.info.pageWidth.toFloat()
                val h = page.info.pageHeight.toFloat()
                val paint = Paint()
                for ((box, color) in listOf(RectF(0f, 0f, w / 2, h / 2) to Color.RED,
                    RectF(w / 2, 0f, w, h / 2) to Color.GREEN,
                    RectF(w / 2, h / 2, w, h) to Color.BLUE,
                    RectF(0f, h / 2, w / 2, h) to Color.YELLOW)) {
                    paint.color = color; page.canvas.drawRect(box, paint)
                }
                paint.color = Color.BLACK; paint.textSize = 20f
                page.canvas.drawText("Rotatable", 30f, 65f, paint)
                paint.textSize = 10f
                page.canvas.drawText(file.name, 30f, 140f, paint)
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(60)
        }
        fail("Reader did not reach the expected rotated state")
    }

    @Test fun viewRotationRendersAllPagesAndSelectionWithoutEditingThePdf() {
        val source = sample()
        val original = source.readBytes()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var reader: PdfReaderView
        val rectMethod = PdfReaderView::class.java.getDeclaredMethod("pageRect", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun matches(): Boolean {
            if (reader.pageCount == 0 || reader.width == 0 || reader.height == 0) return false
            val rect = rectMethod.invoke(reader, reader.currentPage) as RectF
            val image = Bitmap.createBitmap(reader.width, reader.height, Bitmap.Config.ARGB_8888)
            return try {
                reader.draw(Canvas(image))
                listOf(Triple(.2f, .2f, Color.RED), Triple(.8f, .2f, Color.GREEN),
                    Triple(.8f, .8f, Color.BLUE), Triple(.2f, .8f, Color.YELLOW)).all { (x, y, color) ->
                    val point = PdfViewRotation.forward(x, y, reader.viewRotation)
                    image.getPixel((rect.left + rect.width() * point.x).toInt(),
                        (rect.top + rect.height() * point.y).toInt()) == color
                }
            } finally { image.recycle() }
        }
        try {
            instrumentation.runOnMainSync {
                reader = PdfReaderView(activity)
                activity.setContentView(reader)
                reader.setReadingMode(ReadingMode.SINGLE)
                reader.setFitPage(true)
                reader.open(source)
            }
            awaitUi { matches() }
            for (rotation in listOf(90, 180, 270, 0)) {
                instrumentation.runOnMainSync { reader.rotateViewClockwise(); assertEquals(rotation, reader.viewRotation) }
                awaitUi { matches() }
                instrumentation.runOnMainSync { reader.goToPage(1) }
                awaitUi { matches() }
                instrumentation.runOnMainSync { reader.goToPage(0) }
                awaitUi { matches() }
            }
            val text = PdfPageTextExtractor.extract(source, 0)
            val glyph = text.glyphs.first { it.text.contains("R") }
            val select = PdfReaderView::class.java.getDeclaredMethod("selectTextAt", Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType).apply { isAccessible = true }
            for (rotation in listOf(90, 180, 270)) {
                instrumentation.runOnMainSync { reader.setViewRotation(rotation) }
                awaitUi { matches() }
                instrumentation.runOnMainSync {
                    val page = rectMethod.invoke(reader, 0) as RectF
                    val point = PdfViewRotation.forward(glyph.bounds.centerX(), glyph.bounds.centerY(), rotation)
                    select.invoke(reader, page.left + point.x * page.width(), page.top + point.y * page.height())
                }
                awaitUi { reader.textSelection?.text?.contains("Rotatable") == true }
                instrumentation.runOnMainSync { assertFalse(reader.selectionContentRect().isEmpty) }
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            instrumentation.runOnMainSync { reader.close(); activity.finish() }
            source.delete()
        }
    }

    @Test fun toolbarSavesViewRotationPerDocumentAndReopeningRestoresZoomAndPage() {
        val source = sample()
        val store = DocumentStore(context)
        val doc = store.importDocument(Uri.fromFile(source))
        val otherSource = sample()
        val other = store.importDocument(Uri.fromFile(otherSource))
        assertNotEquals(doc.id, other.id)
        val original = store.file(doc).readBytes()
        store.updateProgress(doc.id, 1, .15f, 1.8f, 2)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val open = MainActivity::class.java.getDeclaredMethod("openDocument", String::class.java).apply { isAccessible = true }
        val library = MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync { open.invoke(activity, doc.id) }
            awaitUi { activity.findViewById<PdfReaderView>(R.id.pdf_canvas)?.pageCount == 2 }
            instrumentation.runOnMainSync {
                assertNull(activity.findViewById<View>(R.id.bookmark_button))
                activity.findViewById<View>(R.id.reader_rotate_view).performClick()
                assertEquals(90, store.get(doc.id)!!.viewRotation)
                assertEquals(1, store.get(doc.id)!!.page)
                assertEquals(1.8f, store.get(doc.id)!!.zoom, .001f)
                library.invoke(activity)
                open.invoke(activity, doc.id)
            }
            awaitUi { activity.findViewById<PdfReaderView>(R.id.pdf_canvas)?.pageCount == 2 }
            instrumentation.runOnMainSync {
                val reader = activity.findViewById<PdfReaderView>(R.id.pdf_canvas)
                assertEquals(90, reader.viewRotation)
                assertEquals(1, reader.currentPage)
            }
            val reopened = DocumentStore(context).get(doc.id)!!
            assertEquals(90, reopened.viewRotation)
            assertEquals(0, DocumentStore(context).get(other.id)!!.viewRotation)
            assertEquals(doc.contentHash, reopened.contentHash)
            assertArrayEquals(original, store.file(doc).readBytes())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            store.remove(doc.id); store.remove(other.id)
            source.delete(); otherSource.delete()
        }
    }
}
