package de.pdfcraft.android

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PdfToolsIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tools = PdfTools(context)
    private val files = mutableListOf<File>()

    private fun track(file: File): File = file.also { files.add(it) }

    private fun sample(): File {
        val file = track(File.createTempFile("tools-test-", ".pdf", context.cacheDir))
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(320, 480, index + 1).create())
                page.canvas.drawText("Page ${index + 1}", 24f, 48f, Paint().apply { textSize = 20f })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    private fun expectFailure(word: String, operation: () -> Unit) {
        try {
            operation()
            fail("Expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(word))
        }
    }

    @After fun cleanUp() { files.forEach { it.delete() } }

    private fun pageText(pdf: PDDocument, index: Int): String {
        val page = pdf.getPage(index)
        val rotation = page.rotation
        return try {
            // Test logical content, not TextStripper's layout heuristics for sideways text.
            page.rotation = 0
            PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
                sortByPosition = true
            }.getText(pdf)
        } finally { page.rotation = rotation }
    }

    private fun compact(text: String): String = text.filterNot { it.isWhitespace() }

    @Test fun organizeRealPdfWithoutChangingOriginal() {
        val source = sample()
        val original = source.readBytes()
        val rotated = track(tools.rotate(source, listOf(0, 2), -90))
        PDDocument.load(rotated).use { pdf ->
            assertEquals(270, pdf.getPage(0).rotation)
            assertEquals(0, pdf.getPage(1).rotation)
            assertEquals(270, pdf.getPage(2).rotation)
        }
        val extracted = track(tools.extract(rotated, listOf(2, 0)))
        PDDocument.load(rotated).use { input -> PDDocument.load(extracted).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(270, pdf.getPage(0).rotation)
            listOf(2, 0).forEachIndexed { outputIndex, sourceIndex ->
                val originalContent = input.getPage(sourceIndex).contents.use { it.readBytes() }
                val extractedContent = pdf.getPage(outputIndex).contents.use { it.readBytes() }
                assertTrue("Source page $sourceIndex has no content stream", originalContent.isNotEmpty())
                assertArrayEquals("Content lost or changed: input $sourceIndex -> output $outputIndex",
                    originalContent, extractedContent)
                assertNotNull("Resources lost on output page $outputIndex", pdf.getPage(outputIndex).resources)
                val before = pageText(input, sourceIndex)
                val after = pageText(pdf, outputIndex)
                val message = "Input page $sourceIndex text=[$before]; output page $outputIndex text=[$after]"
                assertEquals(message, compact(before), compact(after))
                assertTrue(message, compact(after).contains("Page${sourceIndex + 1}"))
                assertFalse(message, compact(after).contains("Page2"))
            }
        } }
        val deleted = track(tools.deletePages(source, listOf(1)))
        assertEquals(2, tools.properties(deleted).pages)
        val merged = track(tools.merge(listOf(extracted, deleted, source)))
        assertEquals(7, tools.properties(merged).pages)
        assertEquals(File(context.cacheDir, "tools").canonicalFile, merged.parentFile!!.canonicalFile)
        assertArrayEquals(original, source.readBytes())
        assertNotEquals(source.canonicalPath, rotated.canonicalPath)
    }

    @Test fun unicodeStickyNoteAndMetadataSurvive() {
        val source = sample()
        PDDocument.load(source).use { pdf ->
            pdf.documentInformation.title = "Prüfung"
            pdf.documentInformation.author = "PDFCraft"
            pdf.getPage(0).cropBox = PDRectangle(20f, 30f, 250f, 390f)
            pdf.save(source)
        }
        val before = source.readBytes()
        val text = "Grüße — 日本語 — 😀"
        val noted = track(tools.addNote(source, 0, text))
        assertEquals(PdfNote(0, text), tools.notes(noted).single())
        PDDocument.load(noted).use { pdf ->
            val note = pdf.getPage(0).annotations.single()
            assertEquals(text, note.contents)
            assertEquals("Text", note.subtype)
            assertTrue(note.rectangle.lowerLeftX >= 20f)
            assertTrue(note.rectangle.upperRightY <= 420f)
        }
        val extracted = track(tools.extract(noted, listOf(0)))
        assertEquals("Prüfung", tools.properties(extracted).title)
        assertEquals("PDFCraft", tools.properties(extracted).author)
        PDDocument.load(extracted).use { assertEquals(text, it.getPage(0).annotations.single().contents) }
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun metadataEditingPreservesPagesAndOriginal() {
        val source = sample()
        val before = source.readBytes()
        val changed = track(tools.setMetadata(source, "Grüße — 日本語", "Ada Lovelace"))
        val properties = tools.properties(changed)
        assertEquals("Grüße — 日本語", properties.title)
        assertEquals("Ada Lovelace", properties.author)
        assertEquals(3, properties.pages)
        PDDocument.load(source).use { input -> PDDocument.load(changed).use { output ->
            repeat(input.numberOfPages) { index ->
                assertArrayEquals(input.getPage(index).contents.use { it.readBytes() },
                    output.getPage(index).contents.use { it.readBytes() })
            }
        } }
        val cleared = track(tools.setMetadata(changed, "", ""))
        assertEquals("", tools.properties(cleared).title)
        assertEquals("", tools.properties(cleared).author)
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun validationRejectsInvalidOperations() {
        val source = sample()
        val before = source.readBytes()
        expectFailure("verbleiben") { tools.deletePages(source, listOf(0, 1, 2)) }
        expectFailure("mindestens") { tools.extract(source, emptyList()) }
        expectFailure("außerhalb") { tools.rotate(source, listOf(3), 90) }
        expectFailure("außerhalb") { tools.addNote(source, -1, "Notiz") }
        expectFailure("einmal") { tools.extract(source, listOf(0, 0)) }
        expectFailure("90") { tools.rotate(source, listOf(0), 45) }
        expectFailure("mindestens") { tools.merge(emptyList()) }
        expectFailure("Notiztext") { tools.addNote(source, 0, " ") }
        val invalid = track(File.createTempFile("invalid-tools-", ".pdf", context.cacheDir)).apply { writeText("not a PDF") }
        expectFailure("PDF-Passwortschutz") { tools.needsPassword(invalid) }
        val missing = File(context.cacheDir, "missing-tools-${System.nanoTime()}.pdf")
        expectFailure("nicht vorhanden") { tools.needsPassword(missing) }
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun acroFormValuesAndAppearancesAreSaved() {
        val source = track(File.createTempFile("form-test-", ".pdf", context.cacheDir))
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            val form = PDAcroForm(pdf)
            pdf.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = PDTextField(form).apply {
                partialName = "name"
                defaultAppearance = "/Helv 12 Tf 0 g"
            }
            form.fields.add(field)
            val widget = field.widgets.single()
            widget.rectangle = PDRectangle(30f, 400f, 200f, 30f)
            widget.page = page
            page.annotations.add(widget)
            field.setValue("Original")
            pdf.save(source)
        }
        val before = source.readBytes()
        assertEquals(PdfFormField("name", "Original", "Tx"), tools.forms(source).single())
        val filled = track(tools.fillForms(source, mapOf("name" to "Ada Lovelace")))
        assertEquals("Ada Lovelace", tools.forms(filled).single().value)
        PDDocument.load(filled).use { pdf ->
            assertNotNull(pdf.documentCatalog.acroForm.getField("name").widgets.single().normalAppearanceStream)
        }
        val merged = track(tools.merge(listOf(filled, source)))
        assertEquals(2, tools.properties(merged).pages)
        assertEquals(setOf("Ada Lovelace", "Original"), tools.forms(merged).map { it.value }.toSet())
        assertEquals(2, tools.forms(merged).map { it.name }.distinct().size)
        expectFailure("nicht gefunden") { tools.fillForms(source, mapOf("missing" to "value")) }
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun passwordProtectionAndPermissionsAreExplicit() {
        val source = sample()
        val before = source.readBytes()
        val protected = track(tools.protect(source, "Sicheres-Passwort-42"))
        assertFalse(tools.needsPassword(source))
        assertTrue(tools.needsPassword(protected))
        expectFailure("Passwort") { tools.properties(protected) }
        PDDocument.load(protected, "Sicheres-Passwort-42").use { pdf ->
            assertTrue(pdf.isEncrypted)
            assertEquals(3, pdf.numberOfPages)
            assertTrue(pdf.currentAccessPermission.canPrint())
            assertTrue(pdf.currentAccessPermission.canFillInForm())
            assertFalse(pdf.currentAccessPermission.canModify())
        }
        val protectedBefore = protected.readBytes()
        val cachedBefore = File(context.cacheDir, "tools").listFiles().orEmpty().map { it.name }.toSet()
        expectFailure("Passwort ist falsch") { tools.unlockForViewing(protected, "wrong-password") }
        assertEquals("Wrong passwords must not leave viewing copies", cachedBefore,
            File(context.cacheDir, "tools").listFiles().orEmpty().map { it.name }.toSet())
        val viewing = tools.unlockForViewing(protected, "Sicheres-Passwort-42")
        track(viewing.file)
        assertFalse(viewing.canExtract)
        assertTrue(viewing.canPrint)
        assertFalse(viewing.canModify)
        assertFalse(viewing.owner)
        assertEquals(File(context.cacheDir, "tools").canonicalFile, viewing.file.parentFile!!.canonicalFile)
        PDDocument.load(viewing.file).use { pdf ->
            assertFalse(pdf.isEncrypted)
            assertEquals(3, pdf.numberOfPages)
        }
        assertArrayEquals(protectedBefore, protected.readBytes())
        val restricted = track(File.createTempFile("restricted-test-", ".pdf", context.cacheDir))
        PDDocument.load(source).use { pdf ->
            val permissions = AccessPermission().apply {
                setCanModify(false)
                setCanAssembleDocument(false)
                setCanModifyAnnotations(false)
            }
            pdf.protect(StandardProtectionPolicy("owner-secret", "", permissions))
            pdf.save(restricted)
        }
        expectFailure("Berechtigungen") { tools.rotate(restricted, listOf(0), 90) }
        expectFailure("Berechtigungen") { tools.extract(restricted, listOf(0)) }
        expectFailure("Berechtigungen") { tools.merge(listOf(source, restricted)) }
        expectFailure("Berechtigungen") { tools.addNote(restricted, 0, "Notiz") }
        expectFailure("Berechtigungen") { tools.setMetadata(restricted, "Titel", "Autor") }
        assertFalse("Empty user passwords need no password prompt", tools.needsPassword(restricted))
        val ownerViewing = tools.unlockForViewing(restricted, "owner-secret")
        track(ownerViewing.file)
        assertTrue(ownerViewing.owner)
        assertTrue(ownerViewing.canModify)
        assertTrue(ownerViewing.canExtract)
        assertArrayEquals(before, source.readBytes())
    }
}
