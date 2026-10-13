package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PdfPageTextIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun inclusiveReversedSubstringAndMultilineSelectionPreserveReadingOrder() {
        fixture { document ->
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 20f)
                content.newLineAtOffset(40f, 700f)
                content.showText("Alpha beta")
                content.newLineAtOffset(0f, -32f)
                content.showText("Gamma delta")
                content.endText()
            }
        }.useFixture { file ->
            val before = file.readBytes()
            val page = PdfPageTextExtractor.extract(file, 0)
            assertEquals("Alpha beta\nGamma delta", page.selection(0, page.glyphs.lastIndex).text)
            assertEquals("pha", page.selection(2, 4).text)
            assertEquals("pha", page.selection(4, 2).text)
            val selection = page.selection(6, 14)
            assertEquals("beta\nGamma", selection.text)
            assertEquals(2, selection.boxes.size)
            assertEquals(2, selection.pdfQuads.size)
            selection.pdfQuads.forEach { quad ->
                assertEquals(8, quad.size)
                assertTrue(quad[0] < quad[2]); assertTrue(quad[1] > quad[5])
                assertEquals(quad[0], quad[4], 0f); assertEquals(quad[2], quad[6], 0f)
            }
            val glyph = page.glyphs[2]
            assertEquals(2, page.closestGlyph(glyph.bounds.centerX(), glyph.bounds.centerY()))
            assertNotNull(page.closestGlyph(glyph.bounds.centerX(), glyph.bounds.top - 0.005f))
            assertNull(page.closestGlyph(0.99f, 0.99f))
            assertNull(page.closestGlyph(Float.NaN, 0.5f))
            assertNull(page.closestGlyph(-0.01f, 0.5f))
            assertTrue(page.selection(-1, 3).boxes.isEmpty())
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun synthesizedWordSpacesAreCopiedWithoutInventingCharacterCoordinates() {
        fixture { document ->
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 20f)
                content.newLineAtOffset(40f, 700f); content.showText("Alpha")
                content.newLineAtOffset(70f, 0f); content.showText("beta")
                content.endText()
            }
        }.useFixture { file ->
            val page = PdfPageTextExtractor.extract(file, 0)
            assertEquals("Alpha beta", page.selection(0, page.glyphs.lastIndex).text)
            assertTrue(page.glyphs.any { it.text == " " })
        }
    }

    @Test fun cropOffsetsAndEveryPageRotationMatchNativePaintAndPdfQuadCoordinates() {
        val crop = PDRectangle(70f, 110f, 320f, 480f)
        val x = 120f
        val y = 470f
        val glyphWidth = PDType1Font.HELVETICA.getStringWidth("H") * 24f / 1000f
        fixture { document ->
            for (rotation in listOf(0, 90, 180, 270)) {
                val page = PDPage(PDRectangle(600f, 800f)).apply {
                    cropBox = crop
                    this.rotation = rotation
                }
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    // A known PDF-space rectangle behind the glyph exposes wrong crop/rotation transforms.
                    content.setNonStrokingColor(0f, 1f, 1f)
                    content.addRect(x, y - 6f, glyphWidth, 26f); content.fill()
                    content.setNonStrokingColor(0f, 0f, 0f)
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 24f)
                    content.newLineAtOffset(x, y); content.showText("H"); content.endText()
                }
            }
        }.useFixture { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    repeat(4) { index ->
                        val model = PdfPageTextExtractor.extract(file, index)
                        assertEquals("H", model.glyphs.single().text)
                        val bounds = model.glyphs.single().bounds
                        val selection = model.selection(0, 0)
                        val quad = selection.pdfQuads.single()
                        assertEquals(x, quad[0], 0.02f)
                        assertEquals(x + glyphWidth, quad[2], 0.02f)
                        assertTrue(quad[1] > y); assertTrue(quad[5] < y)
                        renderer.openPage(index).use { native ->
                            assertEquals(if (index % 2 == 0) 320 else 480, native.width)
                            assertEquals(if (index % 2 == 0) 480 else 320, native.height)
                            val bitmap = Bitmap.createBitmap(native.width * 2, native.height * 2, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                native.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                var blackPixels = 0
                                var cyanPixels = 0
                                for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
                                    val color = bitmap.getPixel(px, py)
                                    if (Color.red(color) < 80 && Color.green(color) < 80 && Color.blue(color) < 80) {
                                        blackPixels++
                                        val nx = (px + 0.5f) / bitmap.width
                                        val ny = (py + 0.5f) / bitmap.height
                                        assertTrue("Rotation ${index * 90}: glyph paint outside extracted X bounds", nx >= bounds.left - 0.004f && nx <= bounds.right + 0.004f)
                                        assertTrue("Rotation ${index * 90}: glyph paint outside extracted Y bounds", ny >= bounds.top - 0.004f && ny <= bounds.bottom + 0.004f)
                                    }
                                    if (Color.red(color) < 40 && Color.green(color) > 200 && Color.blue(color) > 200) cyanPixels++
                                }
                                assertTrue("Native glyph really rendered", blackPixels > 30)
                                assertTrue("PDF-space fixture rectangle really rendered", cyanPixels > 30)
                                val u = (x + glyphWidth / 2f - crop.lowerLeftX) / crop.width
                                val v = (y + 19f - crop.lowerLeftY) / crop.height
                                val visual = when (index) {
                                    1 -> v to u
                                    2 -> (1f - u) to v
                                    3 -> (1f - v) to (1f - u)
                                    else -> u to (1f - v)
                                }
                                val marker = bitmap.getPixel((visual.first * bitmap.width).toInt(), (visual.second * bitmap.height).toInt())
                                assertTrue("Known crop-relative marker agrees with native rotation", Color.red(marker) < 80 && Color.green(marker) > 180)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        }
    }

    @Test fun unicodeLigaturesCombiningMarksAndSurrogatesRemainWholeGlyphs() {
        fixture { document ->
            // ToUnicode deliberately maps ONE painted glyph to several Unicode code units.
            val font = PDType1Font(COSDictionary(PDType1Font.HELVETICA.cosObject))
            val mapping = PDStream(document)
            mapping.createOutputStream().use { output ->
                output.write("""
                    /CIDInit /ProcSet findresource begin
                    12 dict begin begincmap
                    /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                    /CMapName /GlyphUnicode def /CMapType 2 def
                    1 begincodespacerange <00> <FF> endcodespacerange
                    3 beginbfchar <41> <00650301> <42> <FB01> <43> <D83DDE00> endbfchar
                    endcmap CMapName currentdict /CMap defineresource pop end end
                """.trimIndent().toByteArray(Charsets.US_ASCII))
            }
            font.cosObject.setItem(COSName.TO_UNICODE, mapping)
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText(); content.setFont(font, 20f)
                content.newLineAtOffset(40f, 700f); content.showText("ABC"); content.endText()
            }
        }.useFixture { file ->
            val page = PdfPageTextExtractor.extract(file, 0)
            assertEquals(listOf("é", "fi", "😀"), page.glyphs.map { it.text })
            assertEquals("éfi😀", page.selection(0, 2).text)
            assertEquals("fi", page.selection(1, 1).text)
            assertEquals(1, page.selection(1, 1).pdfQuads.size)
        }
    }

    @Test fun blankPagesHaveNoSelectableGlyphs() {
        fixture { it.addPage(PDPage()) }.useFixture { file ->
            val page = PdfPageTextExtractor.extract(file, 0)
            assertTrue(page.glyphs.isEmpty())
            assertNull(page.closestGlyph(0.5f, 0.5f))
            assertEquals("", page.selection(0, 0).text)
            assertTrue(page.selection(0, 0).pdfQuads.isEmpty())
        }
    }

    @Test fun extractionPermissionIsEnforcedAndSourceRemainsUnchanged() {
        fixture { document ->
            document.addPage(PDPage())
            document.protect(StandardProtectionPolicy("owner-only", "", AccessPermission().apply {
                setCanExtractContent(false)
            }))
        }.useFixture { file ->
            val original = file.readBytes()
            try {
                PdfPageTextExtractor.extract(file, 0)
                fail("Extraction permission must be enforced")
            } catch (_: IOException) {
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    private fun fixture(build: (PDDocument) -> Unit): File {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("page-text-test-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document -> build(document); document.save(file) }
            return file
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    private inline fun File.useFixture(test: (File) -> Unit) {
        try { test(this) } finally { delete() }
    }
}
