package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
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
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.cos.COSStream
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
    private val palette = MarkupColors.values
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

    private fun appearanceContents(form: PDFormXObject, seen: MutableSet<COSStream> = mutableSetOf()): String {
        if (!seen.add(form.cosObject)) return ""
        val own = form.contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
        val nested = form.resources?.let { resources ->
            resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDFormXObject }
                .joinToString("\n") { appearanceContents(it, seen) }
        }.orEmpty()
        return "$own\n$nested"
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

    @Test fun everyPaletteColorRoundTripsInEveryStyleIgnoringInputAlpha() {
        val source = sample()
        val original = source.readBytes()
        for (style in TextMarkupStyle.values()) for (color in palette) {
            val output = track(markup.markText(source, 0, "hello", color and 0x00FFFFFF, style))
            PDDocument.load(output).use { document ->
                assertEquals(2, document.getPage(0).annotations.size)
                document.getPage(0).annotations.forEach {
                    val annotation = it as PDAnnotationTextMarkup
                    assertEquals("DeviceRGB", annotation.color.colorSpace.name)
                    assertArrayEquals(floatArrayOf(
                        Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f
                    ), annotation.color.components, 0.0001f)
                    assertEquals(if (style == TextMarkupStyle.HIGHLIGHT) 0.4f else 1f,
                        annotation.constantOpacity, 0.0001f)
                    assertNotNull(annotation.normalAppearanceStream)
                    assertTrue(annotation.normalAppearanceStream.contents.use { stream -> stream.readBytes().isNotEmpty() })
                }
            }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun modesPersistCorrectTypesPreciseQuadsAndTheirOwnAppearances() {
        val source = sample()
        val original = source.readBytes()
        val expectedLeft = 70f + PDType1Font.HELVETICA.getStringWidth("Alpha H") / 1000f * 12f
        val expectedRight = expectedLeft + PDType1Font.HELVETICA.getStringWidth("ELL") / 1000f * 12f
        val appearances = mutableSetOf<String>()
        for ((style, subtype) in listOf(
            TextMarkupStyle.HIGHLIGHT to "Highlight",
            TextMarkupStyle.UNDERLINE to "Underline",
            TextMarkupStyle.STRIKEOUT to "StrikeOut"
        )) {
            val output = track(markup.markText(source, 0, "ell", Color.BLUE, style))
            PDDocument.load(output).use { document ->
                val page = document.getPage(0)
                assertEquals(2, page.annotations.size)
                page.annotations.forEachIndexed { index, value ->
                    val annotation = value as PDAnnotationTextMarkup
                    assertEquals(subtype, annotation.subtype)
                    val quad = annotation.quadPoints
                    assertEquals(8, quad.size)
                    val left = if (index == 0) expectedLeft else 70f + PDType1Font.HELVETICA.getStringWidth("h") / 1000f * 12f
                    val right = if (index == 0) expectedRight else left + PDType1Font.HELVETICA.getStringWidth("ell") / 1000f * 12f
                    assertEquals(left, quad[0], 0.1f)
                    assertEquals(right, quad[2], 0.1f)
                    assertEquals(left, quad[4], 0.1f)
                    assertEquals(right, quad[6], 0.1f)
                    assertEquals(quad[1], quad[3], 0f)
                    assertEquals(quad[5], quad[7], 0f)
                    assertTrue(quad[1] > 300f - index * 18f)
                    assertTrue(quad[5] <= 300f - index * 18f)
                    assertTrue(quad[1] - quad[5] < 20f)
                    val appearance = annotation.normalAppearanceStream
                    assertNotNull(appearance)
                    val content = appearanceContents(appearance)
                    assertTrue(content.isNotBlank())
                    assertTrue("Appearance must contain color operators",
                        Regex("(?m)(?:^|\\s)(?:rg|RG|sc|SC|scn|SCN)(?:\\s|$)").containsMatchIn(content))
                    if (index == 0) appearances.add(content)
                }
                PDDocument.load(source).use { input ->
                    assertArrayEquals(input.getPage(0).contents.use { it.readBytes() }, page.contents.use { it.readBytes() })
                    assertEquals(PDFTextStripper().getText(input), PDFTextStripper().getText(document))
                }
            }
            assertArrayEquals(original, source.readBytes())
        }
        assertEquals("Each style must generate its own appearance", 3, appearances.size)
    }

    @Test fun compatibilityWrapperAcceptsCustomColor() {
        val source = sample()
        val original = source.readBytes()
        val output = track(markup.highlightText(source, 0, "hello", Color.RED))
        PDDocument.load(output).use { document ->
            val annotation = document.getPage(0).annotations.first() as PDAnnotationTextMarkup
            assertEquals("Highlight", annotation.subtype)
            assertArrayEquals(floatArrayOf(1f, 0f, 0f), annotation.color.components, 0f)
        }
        assertArrayEquals(original, source.readBytes())
    }

    /** Run on API 31 and 37: native rendering of the viewing-only appearance copy, no UI overlay. */
    @Test fun nativePdfRendererDisplaysEveryColoredHighlight() {
        val source = sample(listOf("HELLO"), 36f)
        val original = source.readBytes()
        for (color in palette) {
            val output = track(markup.highlightText(source, 0, "HELLO", color))
            val savedBytes = output.readBytes()
            val before = render(source)
            val after = render(output)
            try {
                assertEquals(before.width, after.width)
                assertEquals(before.height, after.height)
                var coloredPixels = 0
                var expectedColorPixels = 0
                var outsideTextBand = 0
                val expectedChannels = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
                    .map { (255f * 0.6f + it * 0.4f).toInt() }
                for (y in 0 until after.height) for (x in 0 until after.width) {
                    val pixel = after.getPixel(x, y)
                    val previous = before.getPixel(x, y)
                    val channels = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (channels.maxOrNull()!! - channels.minOrNull()!! > 20 &&
                        Color.red(previous) > 240 && Color.green(previous) > 240 && Color.blue(previous) > 240) {
                        coloredPixels++
                        if (channels.indices.all { kotlin.math.abs(channels[it] - expectedChannels[it]) <= 20 }) {
                            expectedColorPixels++
                        }
                        // Allows both media-box and crop-box native page sizing, but rejects whole-page tinting.
                        if (x < 40 || x > 230 || y < 60 || y > 210) outsideTextBand++
                    }
                }
                assertTrue("API ${Build.VERSION.SDK_INT}: native PdfRenderer did not display " +
                    "prepared highlight RGB ${Integer.toHexString(color)}",
                    coloredPixels > 100)
                assertTrue("Native renderer must show the selected RGB, not a fixed highlight color " +
                    "(API ${Build.VERSION.SDK_INT}, RGB ${Integer.toHexString(color)})", expectedColorPixels > 100)
                assertEquals("Colored annotation must remain around matched glyphs", 0, outsideTextBand)
            } finally { before.recycle(); after.recycle() }
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(savedBytes, output.readBytes())
            PDDocument.load(output).use { document ->
                assertEquals("Saved PDF must retain editable markup", 1, document.getPage(0).annotations.size)
                assertEquals("Highlight", document.getPage(0).annotations.first().subtype)
            }
        }
    }

    @Test fun viewingCopyOwnershipPreservesSavedAnnotationsAndPlainInputs() {
        val source = sample()
        val original = source.readBytes()
        val unchanged = PdfAnnotationRendering.prepare(source, context.cacheDir)
        assertFalse(unchanged.temporary)
        assertEquals(source, unchanged.file)
        unchanged.close()
        assertTrue(source.isFile)
        for (style in TextMarkupStyle.values()) {
            val saved = track(markup.markText(source, 0, "hello", Color.CYAN, style))
            val savedBytes = saved.readBytes()
            val first = PdfAnnotationRendering.prepare(saved, context.cacheDir)
            val second = PdfAnnotationRendering.prepare(saved, context.cacheDir)
            try {
                assertTrue(first.temporary)
                assertTrue(second.temporary)
                assertNotEquals(first.file, second.file)
                PDDocument.load(first.file).use { document ->
                    assertTrue(document.getPage(0).annotations.isEmpty())
                    assertTrue(document.getPage(0).resources.xObjectNames.toList().isNotEmpty())
                }
                first.close()
                assertFalse(first.file.exists())
                assertTrue("One consumer must not delete another's viewing copy", second.file.isFile)
                assertArrayEquals(savedBytes, saved.readBytes())
                PDDocument.load(saved).use { assertEquals(2, it.getPage(0).annotations.size) }
            } finally { first.close(); second.close() }
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun nativePdfRendererDisplaysUnderlineAndStrikeoutViewingAppearances() {
        val source = sample(listOf("HELLO"), 36f)
        for (style in listOf(TextMarkupStyle.UNDERLINE, TextMarkupStyle.STRIKEOUT)) {
            val saved = track(markup.markText(source, 0, "HELLO", Color.RED, style))
            val savedBytes = saved.readBytes()
            val bitmap = render(saved)
            try {
                var redPixels = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) redPixels++
                }
                assertTrue("API ${Build.VERSION.SDK_INT}: no visible $style appearance", redPixels > 50)
            } finally { bitmap.recycle() }
            assertArrayEquals(savedBytes, saved.readBytes())
        }
    }

    @Test fun passwordLockedViewingInputRemainsForNativeUnlockHandling() {
        val source = sample()
        val locked = track(File.createTempFile("markup-locked-", ".pdf", context.cacheDir))
        PDDocument.load(source).use { document ->
            document.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
            document.save(locked)
        }
        val original = locked.readBytes()
        PdfAnnotationRendering.prepare(locked, context.cacheDir).use { prepared ->
            assertFalse(prepared.temporary)
            assertEquals(locked, prepared.file)
        }
        assertArrayEquals(original, locked.readBytes())
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

    private fun render(source: File): Bitmap = PdfAnnotationRendering.prepare(source, context.cacheDir).use { prepared ->
        ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    @Test fun invalidEmptyScannedAndRotatedInputsFailWithoutChangingSource() {
        val source = sample()
        val original = source.readBytes()
        expectFailure("Suchtext") { markup.highlightText(source, 0, " ") }
        expectFailure("Textzeile") { markup.markText(source, 0, "omega\nhello") }
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
        for (style in TextMarkupStyle.values()) {
            track(markup.markText(annotateOnly, 0, "hello", Color.CYAN, style))
            expectFailure("Berechtigungen") { markup.markText(modifyOnly, 0, "hello", Color.CYAN, style) }
            expectFailure("Textextraktion") { markup.markText(noExtraction, 0, "hello", Color.CYAN, style) }
        }
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
