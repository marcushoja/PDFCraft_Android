package de.pdfcraft.android

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Caller owns this handle; close only after all native renderers/descriptors have closed. */
data class PreparedPdf(val file: File, val temporary: Boolean) : Closeable {
    override fun close() { if (temporary) file.delete() }
}

/**
 * Native Android PdfRenderer on older Android versions omits FPDF_ANNOT, even in PRINT mode.
 * This creates an internal, VIEWING-ONLY copy with visible text-markup appearance XObjects
 * appended as page content. Never export, edit, search or save this copy instead of the source.
 * The source and its editable annotations remain byte-for-byte untouched.
 *
 * Call on a worker after PDFBoxResourceLoader.init(context). Reuse a PreparedPdf across reader,
 * thumbnails and print where practical. There is deliberately no shared file cache: each handle
 * owns a unique temporary file, so one consumer cannot delete another consumer's backing file.
 * Non-text-markup annotations retain the native renderer's existing support/limitations.
 */
object PdfAnnotationRendering {
    fun prepare(source: File, cacheDir: File, forPrinting: Boolean = false): PreparedPdf {
        return try {
            prepareUnlocked(source, cacheDir, forPrinting)
        } catch (_: InvalidPasswordException) {
            // Leave password/unlock handling to the existing native caller, not this adapter.
            PreparedPdf(source, false)
        }
    }

    private fun prepareUnlocked(source: File, cacheDir: File, forPrinting: Boolean): PreparedPdf {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) throw IOException("Cannot create PDF viewing cache")
        val memory = MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(cacheDir)
        PDDocument.load(source, memory).use { document ->
            var changed = false
            for (page in document.pages) {
                val annotations = page.annotations
                val visible = annotations.filterIsInstance<PDAnnotationTextMarkup>().filter {
                    !it.isHidden && !it.isInvisible && (if(forPrinting) it.isPrinted else !it.isNoView)
                }
                if (visible.isEmpty()) continue
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    for (annotation in visible) {
                        if (annotation.normalAppearanceStream == null) annotation.constructAppearances(document)
                        val appearance = annotation.normalAppearanceStream
                            ?: throw IOException("Text markup has no renderable appearance")
                        val box = appearance.bBox ?: throw IOException("Text markup appearance has no bounds")
                        val rect = annotation.rectangle ?: throw IOException("Text markup has no bounds")
                        // PDF spec: transform the appearance BBox by its own Matrix, then fit
                        // that bounding box to /Rect. drawForm applies the appearance Matrix itself.
                        val matrix = appearance.matrix
                        val corners = listOf(
                            matrix.transformPoint(box.lowerLeftX, box.lowerLeftY),
                            matrix.transformPoint(box.lowerLeftX, box.upperRightY),
                            matrix.transformPoint(box.upperRightX, box.lowerLeftY),
                            matrix.transformPoint(box.upperRightX, box.upperRightY)
                        )
                        val left = corners.minOf { it.x }
                        val bottom = corners.minOf { it.y }
                        val width = corners.maxOf { it.x } - left
                        val height = corners.maxOf { it.y } - bottom
                        if (listOf(left, bottom, width, height, rect.lowerLeftX, rect.lowerLeftY,
                                rect.width, rect.height).any { !it.isFinite() } ||
                            width <= 0f || height <= 0f || rect.width <= 0f || rect.height <= 0f) {
                            throw IOException("Text markup appearance has invalid bounds")
                        }
                        val sx = rect.width / width
                        val sy = rect.height / height
                        stream.saveGraphicsState()
                        stream.transform(Matrix(sx, 0f, 0f, sy,
                            rect.lowerLeftX - left * sx, rect.lowerLeftY - bottom * sy))
                        stream.drawForm(appearance)
                        stream.restoreGraphicsState()
                    }
                }
                // Prevent double rendering on newer native renderers that do support annotations.
                page.annotations = annotations.filterNot { it in visible }
                changed = true
            }
            if (!changed) return PreparedPdf(source, false)
            document.isAllSecurityToBeRemoved = true
            val output = File.createTempFile("pdfcraft-view-", ".pdf", cacheDir)
            try {
                document.save(output)
                return PreparedPdf(output, true)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        }
    }
}
