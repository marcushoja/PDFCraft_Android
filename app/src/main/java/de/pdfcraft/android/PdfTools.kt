package de.pdfcraft.android

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import java.io.File
import java.io.IOException
import java.security.SecureRandom

data class PdfProperties(val title: String, val author: String, val pages: Int, val version: String, val encrypted: Boolean)
data class PdfFormField(val name: String, val value: String, val type: String)

/**
 * Blocking PDF operations: call from an IO worker, never the UI thread. Page indices are zero-based.
 * Results are independent private cache files; callers own their eventual removal/export.
 * Editing rewrites the PDF and therefore invalidates existing digital signatures.
 * Password-required inputs are unsupported. Editable empty-password inputs produce unencrypted
 * copies, unless protect() is used. Extraction retains the catalog; navigation/form references
 * to removed pages are not repaired, and page deletion is not secure content redaction.
 */
class PdfTools(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "tools")

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    fun rotate(source: File, pages: List<Int>, degrees: Int): File = edit(source) { document ->
        checkPages(document, pages)
        if (degrees % 90 != 0) throw IOException("Die Drehung muss ein Vielfaches von 90 Grad sein.")
        requirePermission(document.currentAccessPermission.canModify(), "Das Drehen")
        pages.forEach { index ->
            val page = document.getPage(index)
            page.rotation = Math.floorMod(page.rotation.toLong() + degrees.toLong(), 360L).toInt()
        }
    }

    fun extract(source: File, pages: List<Int>): File = edit(source) { document ->
        checkPages(document, pages)
        requirePermission(document.currentAccessPermission.canAssembleDocument(), "Das Entnehmen von Seiten")
        // Keep the source object graph in this independently loaded document: unlike shallow
        // cross-document page copies, inherited resources, annotations and metadata stay available.
        val selected = pages.map { document.getPage(it).apply {
            // Materialize inherited attributes before detaching from the old page tree.
            resources = resources
            mediaBox = mediaBox
            cropBox = cropBox
            rotation = rotation
        } }
        while (document.numberOfPages > 0) document.removePage(0)
        selected.forEach { document.addPage(it) }
    }

    fun deletePages(source: File, pages: List<Int>): File = edit(source) { document ->
        checkPages(document, pages)
        if (pages.size == document.numberOfPages) throw IOException("Mindestens eine Seite muss im PDF verbleiben.")
        requirePermission(document.currentAccessPermission.canAssembleDocument(), "Das Löschen von Seiten")
        pages.sortedDescending().forEach { document.removePage(it) }
    }

    fun merge(sources: List<File>): File = io("PDFs konnten nicht zusammengeführt werden") {
        if (sources.isEmpty()) throw IOException("Bitte mindestens eine PDF-Datei auswählen.")
        sources.forEach { source ->
            load(source).use { document ->
                requirePermission(document.currentAccessPermission.canAssembleDocument(), "Das Zusammenführen")
                if (document.documentCatalog.acroForm?.hasXFA() == true) {
                    throw IOException("PDFs mit XFA-Formularen können nicht verlustfrei zusammengeführt werden.")
                }
            }
        }
        output { result ->
            val merger = PDFMergerUtility()
            merger.documentMergeMode = PDFMergerUtility.DocumentMergeMode.PDFBOX_LEGACY_MODE
            merger.acroFormMergeMode = PDFMergerUtility.AcroFormMergeMode.PDFBOX_LEGACY_MODE
            merger.isIgnoreAcroFormErrors = false
            sources.forEach { merger.addSource(it) }
            merger.destinationFileName = result.absolutePath
            // Legacy mode preserves document-level structures (forms, outlines, metadata).
            // Do not enable ignoreAcroFormErrors: an unsupported form must fail explicitly.
            merger.mergeDocuments(memory())
        }
    }

    fun addNote(source: File, page: Int, text: String): File = edit(source) { document ->
        checkPages(document, listOf(page))
        if (text.isBlank()) throw IOException("Bitte einen Notiztext eingeben.")
        requirePermission(document.currentAccessPermission.canModifyAnnotations(), "Das Hinzufügen von Notizen")
        val target = document.getPage(page)
        val box = target.cropBox
        val size = minOf(24f, box.width, box.height)
        val inset = minOf(12f, (box.width - size) / 2f, (box.height - size) / 2f).coerceAtLeast(0f)
        val note = PDAnnotationText().apply {
            contents = text
            titlePopup = "PDFCraft"
            name = PDAnnotationText.NAME_NOTE
            rectangle = PDRectangle(box.lowerLeftX + inset, box.upperRightY - inset - size, size, size)
            setOpen(false)
            isPrinted = true
            this.page = target
        }
        target.annotations.add(note)
    }

    fun properties(source: File): PdfProperties = io("PDF-Eigenschaften konnten nicht gelesen werden") {
        load(source).use { document ->
            PdfProperties(document.documentInformation.title.orEmpty(), document.documentInformation.author.orEmpty(),
                document.numberOfPages, document.version.toString(), document.isEncrypted)
        }
    }

    fun forms(source: File): List<PdfFormField> = io("PDF-Formular konnte nicht gelesen werden") {
        load(source).use { document ->
            val form = document.documentCatalog.acroForm ?: return@use emptyList()
            if (form.hasXFA()) throw IOException("XFA-Formulare werden nicht unterstützt.")
            form.fieldTree.filterIsInstance<PDTerminalField>().map { field ->
                PdfFormField(field.fullyQualifiedName, field.valueAsString.orEmpty(), field.fieldType.orEmpty())
            }
        }
    }

    fun fillForms(source: File, values: Map<String, String>): File = edit(source) { document ->
        requirePermission(document.currentAccessPermission.canFillInForm(), "Das Ausfüllen von Formularen")
        val form = document.documentCatalog.acroForm ?: throw IOException("Dieses PDF enthält kein ausfüllbares Formular.")
        if (form.hasXFA()) throw IOException("XFA-Formulare werden nicht unterstützt.")
        if (values.isEmpty()) throw IOException("Bitte mindestens ein Formularfeld angeben.")
        values.forEach { (name, value) ->
            val field = form.getField(name) ?: throw IOException("Das Formularfeld „$name“ wurde nicht gefunden.")
            if (field !is PDTerminalField) throw IOException("„$name“ ist eine Formulargruppe und kein ausfüllbares Feld.")
            if (field.isReadOnly) throw IOException("Das Formularfeld „$name“ ist schreibgeschützt.")
            if (field.fieldType == "Sig") throw IOException("Digitale Signaturfelder können nicht als Text ausgefüllt werden.")
            try { field.setValue(value) } catch (error: IllegalArgumentException) {
                throw IOException("Der Wert für das Formularfeld „$name“ wird nicht unterstützt.", error)
            }
        }
    }

    /** Adds a user password; the unrelated, random owner password prevents permission escalation. */
    fun protect(source: File, password: String): File = io("PDF konnte nicht geschützt werden") {
        if (password.isBlank()) throw IOException("Bitte ein nicht leeres Passwort eingeben.")
        if (password.toByteArray(Charsets.UTF_8).size > 127) throw IOException("Das Passwort darf höchstens 127 UTF-8-Bytes enthalten.")
        load(source).use { document ->
            requirePermission(document.currentAccessPermission.canModify(), "Das Schützen")
            val owner = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            val permission = AccessPermission().apply {
                setCanModify(false)
                setCanAssembleDocument(false)
                setCanExtractContent(false)
                setCanModifyAnnotations(false)
                setCanPrint(true)
                setCanFillInForm(true)
            }
            val policy = StandardProtectionPolicy(owner, password, permission).apply { encryptionKeyLength = 256 }
            document.protect(policy)
            output { document.save(it) }
        }
    }

    private fun edit(source: File, change: (PDDocument) -> Unit): File = io("PDF konnte nicht bearbeitet werden") {
        load(source).use { document ->
            change(document)
            document.isAllSecurityToBeRemoved = true
            output { document.save(it) }
        }
    }

    private fun checkPages(document: PDDocument, pages: List<Int>) {
        if (pages.isEmpty()) throw IOException("Bitte mindestens eine Seite auswählen.")
        if (pages.distinct().size != pages.size) throw IOException("Eine Seite darf nur einmal ausgewählt werden.")
        if (pages.any { it !in 0 until document.numberOfPages }) throw IOException("Eine ausgewählte Seite liegt außerhalb des PDFs.")
    }

    private fun requirePermission(allowed: Boolean, action: String) {
        if (!allowed) throw IOException("$action ist durch die PDF-Berechtigungen nicht erlaubt.")
    }

    private fun memory(): MemoryUsageSetting {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Der private PDF-Arbeitsordner konnte nicht erstellt werden.")
        return MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(directory)
    }

    private fun load(source: File): PDDocument {
        if (!source.isFile || !source.canRead()) throw IOException("Die PDF-Datei ist nicht vorhanden oder nicht lesbar.")
        return try { PDDocument.load(source, memory()) } catch (error: InvalidPasswordException) {
            throw IOException("Dieses PDF benötigt ein Passwort. Das Öffnen passwortgeschützter PDFs wird hier nicht unterstützt.", error)
        }
    }

    private fun output(write: (File) -> Unit): File {
        memory()
        val result = File.createTempFile("pdfcraft-", ".pdf", directory)
        try {
            write(result)
            return result
        } catch (error: Throwable) {
            result.delete()
            throw error
        }
    }

    private fun <T> io(message: String, operation: () -> T): T = try { operation() } catch (error: IOException) {
        throw IOException("$message: ${error.message ?: "Dateifehler"}", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("$message: Ungültige oder nicht unterstützte PDF-Daten.", error)
    } catch (error: UnsupportedOperationException) {
        throw IOException("$message: Diese PDF-Funktion wird nicht unterstützt.", error)
    }
}
