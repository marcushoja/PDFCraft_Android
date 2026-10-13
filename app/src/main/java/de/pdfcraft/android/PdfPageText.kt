package de.pdfcraft.android

import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.text.Normalizer

/** One selectable glyph, not one UTF-16 character: ligatures and combining marks stay together. */
data class PdfTextGlyph(val text: String, val bounds: RectF, val line: Int)

/** Boxes are normalized visual coordinates; quads are absolute, unrotated PDF user coordinates. */
data class PdfTextSelection(val text: String, val boxes: List<RectF>, val pdfQuads: List<FloatArray>)

data class PdfPageText(val glyphs: List<PdfTextGlyph>) {
    // Kept separately so callers constructing a text-only model need no page geometry.
    internal var pdfGlyphBounds: List<RectF> = emptyList()

    /** A small near-glyph tolerance, never a nearest-word match from a distant blank area. */
    fun closestGlyph(x: Float, y: Float): Int? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        var best: Int? = null
        var distance = Float.POSITIVE_INFINITY
        glyphs.forEachIndexed { index, glyph ->
            if(glyph.text.isBlank()) return@forEachIndexed
            val box = glyph.bounds
            val dx = maxOf(box.left - x, 0f, x - box.right)
            val dy = maxOf(box.top - y, 0f, y - box.bottom)
            val candidate = dx * dx + dy * dy
            if (candidate <= HIT_TOLERANCE * HIT_TOLERANCE && candidate < distance) {
                best = index
                distance = candidate
            }
        }
        return best
    }

    /** Inclusive glyph indices, accepting reversed handles. Invalid indices return an empty selection. */
    fun selection(start: Int, end: Int): PdfTextSelection {
        if (start !in glyphs.indices || end !in glyphs.indices) return PdfTextSelection("", emptyList(), emptyList())
        val first = minOf(start, end)
        val last = maxOf(start, end)
        val text = StringBuilder()
        val boxes = mutableListOf<RectF>()
        val quads = mutableListOf<FloatArray>()
        var previousLine = glyphs[first].line
        var visual: RectF? = null
        var pdf: RectF? = null
        fun finishBox() {
            visual?.let { boxes.add(RectF(it)) }
            pdf?.let { box ->
                // Adobe order: top-left, top-right, bottom-left, bottom-right.
                quads.add(floatArrayOf(box.left, box.bottom, box.right, box.bottom,
                    box.left, box.top, box.right, box.top))
            }
            visual = null
            pdf = null
        }
        for (index in first..last) {
            val glyph = glyphs[index]
            if (glyph.line != previousLine) {
                text.append('\n')
                finishBox()
                previousLine = glyph.line
            }
            text.append(glyph.text)
            // Split disconnected runs (e.g. columns), rather than highlighting the intervening blank area.
            val raw = pdfGlyphBounds.getOrNull(index)
            if (pdf != null && raw != null && raw.left - pdf!!.right > maxOf(12f, pdf!!.height() * 2f)) finishBox()
            if (visual == null) visual = RectF(glyph.bounds) else visual!!.union(glyph.bounds)
            if (raw != null) {
                if (pdf == null) pdf = RectF(raw) else pdf!!.union(raw)
            }
        }
        finishBox()
        return PdfTextSelection(text.toString().trim(), boxes, quads)
    }

    private companion object { const val HIT_TOLERANCE = 0.018f }
}

/** Blocking, read-only extraction; initialize PDFBoxResourceLoader in the host before calling. */
object PdfPageTextExtractor {
    /** Page is zero-based. This owns its document and never alters the source or renderer descriptor. */
    fun extract(source: File, page: Int): PdfPageText {
        PDDocument.load(source, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { document ->
            require(page in 0 until document.numberOfPages) { "Die ausgewählte Seite liegt außerhalb des PDFs." }
            if (!document.currentAccessPermission.canExtractContent()) {
                throw IOException("Die Textextraktion ist durch die PDF-Berechtigungen nicht erlaubt.")
            }
            val target = document.getPage(page)
            val crop = target.cropBox
            require(crop.width.isFinite() && crop.height.isFinite() && crop.width > 0f && crop.height > 0f)
            val rotation = Math.floorMod(target.rotation, 360)
            require(rotation % 90 == 0) { "Nicht unterstützte Seitendrehung." }
            val collector = Collector(crop, rotation).apply {
                startPage = page + 1
                endPage = page + 1
                sortByPosition = true
            }
            collector.getText(document)
            return PdfPageText(collector.glyphs.toList()).also { it.pdfGlyphBounds = collector.rawBoxes.toList() }
        }
    }

    private class Collector(private val crop: PDRectangle, private val rotation: Int) : PDFTextStripper() {
        val glyphs = mutableListOf<PdfTextGlyph>()
        val rawBoxes = mutableListOf<RectF>()
        private var line = 0
        private var pendingSpace = false
        private var lineHasText = false

        override fun processTextPosition(text: TextPosition) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            super.processTextPosition(text)
        }

        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            positions.forEach { position ->
                val matrix = position.textMatrix
                // Page /Rotate is independent of text direction. PDFBox's xDirAdj/yDirAdj
                // remove TEXT rotation, whereas x/y apply PAGE rotation to the baseline only.
                // Use the crop-translated, unrotated matrix instead of mixing those two spaces.
                if (position.font.isVertical || position.dir != 0f || matrix.scaleX <= 0f || matrix.scaleY <= 0f ||
                    kotlin.math.abs(matrix.shearX) > 0.001f || kotlin.math.abs(matrix.shearY) > 0.001f) return@forEach
                val unicode = Normalizer.normalize(position.unicode.orEmpty(), Normalizer.Form.NFKC)
                if (unicode.isEmpty()) return@forEach
                val descriptor = position.font.fontDescriptor
                val ascent = descriptor?.ascent?.takeIf { it.isFinite() && it > 0f }
                    ?.let { it * position.yScale / 1000f } ?: (position.heightDir * 1.15f)
                val descent = descriptor?.descent?.takeIf { it.isFinite() && it < 0f }
                    ?.let { -it * position.yScale / 1000f } ?: (position.heightDir * 0.2f)
                val x = crop.lowerLeftX + matrix.translateX
                val y = crop.lowerLeftY + matrix.translateY
                val raw = RectF(x, y - descent, x + position.widthDirAdj, y + ascent)
                if (!listOf(raw.left, raw.top, raw.right, raw.bottom).all { it.isFinite() } ||
                    !raw.intersect(crop.lowerLeftX, crop.lowerLeftY, crop.upperRightX, crop.upperRightY)) return@forEach
                if (pendingSpace && lineHasText) {
                    val previous = rawBoxes.last()
                    // PDFTextStripper can synthesize a separator where the PDF has no space glyph.
                    if (previous.right < raw.left && glyphs.last().text.lastOrNull()?.isWhitespace() != true &&
                        unicode.firstOrNull()?.isWhitespace() != true) {
                        add(" ", RectF(previous.right, minOf(previous.top, raw.top), raw.left, maxOf(previous.bottom, raw.bottom)))
                    }
                }
                pendingSpace = false
                add(unicode, raw)
            }
        }

        override fun writeWordSeparator() { pendingSpace = true }
        override fun writeLineSeparator() { finishLine() }
        override fun writeParagraphSeparator() { finishLine() }

        private fun finishLine() {
            if (lineHasText) line++
            lineHasText = false
            pendingSpace = false
        }

        private fun add(text: String, raw: RectF) {
            val points = listOf(visual(raw.left, raw.top), visual(raw.right, raw.top),
                visual(raw.left, raw.bottom), visual(raw.right, raw.bottom))
            val bounds = RectF(points.minOf { it.first }, points.minOf { it.second },
                points.maxOf { it.first }, points.maxOf { it.second })
            glyphs.add(PdfTextGlyph(text, bounds, line))
            rawBoxes.add(raw)
            lineHasText = true
        }

        private fun visual(x: Float, y: Float): Pair<Float, Float> {
            val u = ((x - crop.lowerLeftX) / crop.width).coerceIn(0f, 1f)
            val v = ((y - crop.lowerLeftY) / crop.height).coerceIn(0f, 1f)
            return when (rotation) {
                90 -> v to u
                180 -> (1f - u) to v
                270 -> (1f - v) to (1f - u)
                else -> u to (1f - v)
            }
        }
    }
}
