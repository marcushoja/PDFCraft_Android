package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PdfMarkupIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val markup = PdfMarkup(context)
    private val files = mutableListOf<File>()
    private val handwriting = listOf(
        listOf(InkPoint(0f, 0.7f), InkPoint(0.2f, 0.1f), InkPoint(0.35f, 0.9f), InkPoint(0.6f, 0.3f), InkPoint(1f, 0.5f)),
        listOf(InkPoint(0.1f, 1f), InkPoint(0.9f, 0.8f)),
        listOf(InkPoint(0.8f, 0.1f))
    )

    private fun track(file: File): File = file.also { files.add(it) }
    @After fun cleanUp() { files.forEach { it.delete() } }

    private fun sample(lines: List<String> = listOf("Alpha HELLO omega", "hello world"), size: Float = 12f): File {
        val result = track(File.createTempFile("markup-test-", ".pdf", context.cacheDir))
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(320f, 480f)).apply { cropBox = PDRectangle(20f, 30f, 280f, 390f) }
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                lines.forEachIndexed { index, text ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, size)
                    stream.newLineAtOffset(70f, 300f - index * (size + 6f))
                    stream.showText(text)
                    stream.endText()
                }
            }
            document.save(result)
        }
        return result
    }

    private fun expectFailure(word: String, action: () -> Unit) {
        try { action(); fail("Expected IOException") } catch (error: IOException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(word))
        }
    }

    @Test fun highlightsCoverOnlyMatchedGlyphsAtCropBoxOffset() {
        val source = sample()
        val original = source.readBytes()
        val output = track(markup.highlightText(source, 0, "hello"))
        PDDocument.load(output).use { document ->
            val page = document.getPage(0)
            val annotations = page.annotations.map { it as PDAnnotationTextMarkup }
            assertEquals(2, annotations.size)
            val firstLeft = 70f + PDType1Font.HELVETICA.getStringWidth("Alpha ") / 1000f * 12f
            val firstRight = firstLeft + PDType1Font.HELVETICA.getStringWidth("HELLO") / 1000f * 12f
            annotations.forEachIndexed { index, annotation ->
                assertEquals("Highlight", annotation.subtype)
                assertEquals("hello", annotation.contents)
                assertEquals("PDFCraft", annotation.titlePopup)
                assertArrayEquals(floatArrayOf(1f, 1f, 0f), annotation.color.components, 0.001f)
                assertNotNull(annotation.normalAppearanceStream)
                assertTrue(annotation.isPrinted)
                val quad = annotation.quadPoints
                assertEquals(8, quad.size)
                val baseline = 300f - index * 18f
                val left = if (index == 0) firstLeft else 70f
                val right = if (index == 0) firstRight else 70f + PDType1Font.HELVETICA.getStringWidth("hello") / 1000f * 12f
                assertEquals(left, quad[0], 0.1f)
                assertEquals(right, quad[2], 0.1f)
                assertTrue("Highlight must cover glyphs above baseline", quad[1] > baseline + 4f)
                assertTrue("Highlight must cover baseline", quad[5] <= baseline)
                assertTrue(quad[1] - quad[5] < 20f)
                assertEquals(quad[0], quad[4], 0f)
                assertEquals(quad[2], quad[6], 0f)
                assertTrue(annotation.rectangle.lowerLeftX >= page.cropBox.lowerLeftX)
                assertTrue(annotation.rectangle.upperRightY <= page.cropBox.upperRightY)
            }
            PDDocument.load(source).use { input ->
                assertArrayEquals(input.getPage(0).contents.use { it.readBytes() }, page.contents.use { it.readBytes() })
            }
        }
        assertArrayEquals(original, source.readBytes())
        assertEquals(File(context.cacheDir, "tools").canonicalFile, output.parentFile!!.canonicalFile)
    }

    @Test fun phraseAndPartialWordMapToCorrectGlyphSpansWithoutCrossingLines() {
        val source = sample()
        val phrase = track(markup.highlightText(source, 0, "HELLO OMEGA"))
        PDDocument.load(phrase).use { assertEquals(1, it.getPage(0).annotations.size) }
        val partial = track(markup.highlightText(source, 0, "ell"))
        PDDocument.load(partial).use { document ->
            val annotation = document.getPage(0).annotations.first() as PDAnnotationTextMarkup
            val expected = 70f + PDType1Font.HELVETICA.getStringWidth("Alpha H") / 1000f * 12f
            assertEquals(expected, annotation.quadPoints[0], 0.1f)
            assertEquals(2, document.getPage(0).annotations.size)
        }
        expectFailure("nicht gefunden") { markup.highlightText(source, 0, "omega hello") }
    }

    @Test fun matchCountIsCappedAtTwoHundred() {
        val source = sample(List(30) { "Echo Echo Echo Echo Echo Echo Echo" }, 1f)
        val output = track(markup.highlightText(source, 0, "echo"))
        PDDocument.load(output).use { assertEquals(200, it.getPage(0).annotations.size) }
    }

    @Test fun signatureAppendsVectorsPreservesTextAndRendersAsInk() {
        val source = sample()
        val original = source.readBytes()
        val output = track(markup.addSignature(source, 0, handwriting))
        PDDocument.load(output).use { document ->
            assertEquals(1, document.numberOfPages)
            assertTrue(document.signatureDictionaries.isEmpty())
            assertTrue(document.getPage(0).annotations.isEmpty())
            val content = document.getPage(0).contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            assertTrue(content.contains("1 J"))
            assertTrue(content.contains("1 j"))
            assertEquals(3, Regex("(?m)^S\\s*$").findAll(content).count())
            PDDocument.load(source).use { input ->
                assertEquals(PDFTextStripper().getText(input), PDFTextStripper().getText(document))
                assertEquals(input.getPage(0).resources.fontNames.toList(), document.getPage(0).resources.fontNames.toList())
                val originalContent = input.getPage(0).contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                assertTrue(content.contains(originalContent.trim()))
            }
        }
        val before = render(source)
        val after = render(output)
        try {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            var newInk = 0
            var outsideArea = 0
            for (y in 0 until after.height) for (x in 0 until after.width) {
                val a = after.getPixel(x, y)
                val b = before.getPixel(x, y)
                if (Color.red(a) < 128 && Color.green(a) < 128 && Color.blue(a) < 128 && Color.red(b) > 200) {
                    newInk++
                    if (x < after.width * 0.45f || y < after.height * 0.7f) outsideArea++
                }
            }
            assertTrue("No rendered handwriting", newInk > 50)
            assertEquals("Signature must remain in bottom-right area", 0, outsideArea)
        } finally { before.recycle(); after.recycle() }
        assertArrayEquals(original, source.readBytes())
    }

    private fun render(source: File): Bitmap = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(0).use { page ->
                Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    @Test fun invalidEmptyScannedAndRotatedInputsFailWithoutChangingSource() {
        val source = sample()
        val original = source.readBytes()
        expectFailure("Suchtext") { markup.highlightText(source, 0, " ") }
        expectFailure("nicht gefunden") { markup.highlightText(source, 0, "absent") }
        expectFailure("außerhalb") { markup.highlightText(source, 1, "hello") }
        expectFailure("zeichnen") { markup.addSignature(source, 0, emptyList()) }
        expectFailure("Koordinaten") { markup.addSignature(source, 0, listOf(listOf(InkPoint(Float.NaN, 0f)))) }
        expectFailure("Koordinaten") { markup.addSignature(source, 0, listOf(listOf(InkPoint(2f, 0f)))) }
        val blank = sample(emptyList())
        expectFailure("keinen PDF-Text") { markup.highlightText(blank, 0, "hello") }
        val rotated = sample()
        PDDocument.load(rotated).use { it.getPage(0).rotation = 90; it.save(rotated) }
        expectFailure("Gedrehte Seiten") { markup.highlightText(rotated, 0, "hello") }
        expectFailure("Gedrehte Seiten") { markup.addSignature(rotated, 0, handwriting) }
        assertArrayEquals(original, source.readBytes())
    }

    private fun restricted(annotations: Boolean, modification: Boolean, extract: Boolean = true): File {
        val source = sample()
        val output = track(File.createTempFile("markup-permission-", ".pdf", context.cacheDir))
        PDDocument.load(source).use { document ->
            val access = AccessPermission().apply {
                setCanModifyAnnotations(annotations)
                setCanModify(modification)
                setCanExtractContent(extract)
            }
            document.protect(StandardProtectionPolicy("owner-only-secret", "", access).apply { encryptionKeyLength = 128 })
            document.save(output)
        }
        return output
    }

    @Test fun annotationAndContentPermissionsAreCheckedSeparately() {
        val annotateOnly = restricted(annotations = true, modification = false)
        val original = annotateOnly.readBytes()
        track(markup.highlightText(annotateOnly, 0, "hello"))
        expectFailure("Berechtigungen") { markup.addSignature(annotateOnly, 0, handwriting) }
        val modifyOnly = restricted(annotations = false, modification = true)
        expectFailure("Berechtigungen") { markup.highlightText(modifyOnly, 0, "hello") }
        track(markup.addSignature(modifyOnly, 0, handwriting))
        val noExtraction = restricted(annotations = true, modification = true, extract = false)
        expectFailure("Textextraktion") { markup.highlightText(noExtraction, 0, "hello") }
        assertArrayEquals(original, annotateOnly.readBytes())
    }

    @Test fun padCapturesNormalizedStrokesReturnsSnapshotsAndClears() {
        instrumentation.runOnMainSync {
            val pad = SignaturePadView(context)
            pad.layout(0, 0, 200, 100)
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(1L, 2L, action, x, y, 0)
                try { assertTrue(pad.onTouchEvent(event)) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 20f, 30f)
            touch(MotionEvent.ACTION_MOVE, 100f, 70f)
            touch(MotionEvent.ACTION_UP, 180f, 90f)
            val snapshot = pad.strokes()
            assertEquals(1, snapshot.size)
            assertEquals(3, snapshot.single().size)
            assertEquals(InkPoint(0.1f, 0.3f), snapshot.single().first())
            assertEquals(InkPoint(0.9f, 0.9f), snapshot.single().last())
            assertTrue(pad.contentDescription.toString().contains("löschen"))
            pad.clear()
            assertTrue(pad.strokes().isEmpty())
            assertEquals(3, snapshot.single().size)
            touch(MotionEvent.ACTION_DOWN, -10f, 200f)
            assertEquals(InkPoint(0f, 1f), pad.strokes().single().single())
            touch(MotionEvent.ACTION_CANCEL, 0f, 0f)
            assertTrue(pad.strokes().isEmpty())
        }
    }
}
