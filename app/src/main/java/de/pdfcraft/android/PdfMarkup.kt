package de.pdfcraft.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.text.Normalizer

/** Coordinates in the pad: left/top = 0, right/bottom = 1. */
data class InkPoint(val x: Float, val y: Float)

/** A plain, white handwriting canvas. Call clear() from the host's labelled clear button. */
class SignaturePadView(context: Context) : View(context) {
    private val ink = mutableListOf<MutableList<InkPoint>>()
    private var active: MutableList<InkPoint>? = null
    private var pointer = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(Color.WHITE)
        contentDescription = "Unterschriftenfeld. Mit dem Finger zeichnen. Mit „Unterschrift löschen“ leeren. Keine digitale Signatur."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isClickable = true
    }

    fun strokes(): List<List<InkPoint>> = ink.map { it.toList() }

    fun clear() {
        ink.clear()
        active = null
        pointer = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
        announceForAccessibility("Unterschrift gelöscht")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.forEach { stroke ->
            if (stroke.size == 1) {
                val p = stroke.first()
                canvas.drawPoint(p.x * width, p.y * height, paint)
            } else if (stroke.isNotEmpty()) {
                val path = Path()
                stroke.forEachIndexed { index, p ->
                    if (index == 0) path.moveTo(p.x * width, p.y * height)
                    else path.lineTo(p.x * width, p.y * height)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                active = mutableListOf<InkPoint>().also { ink.add(it) }
                parent?.requestDisallowInterceptTouchEvent(true)
                addPoint(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) {
                    for (history in 0 until event.historySize) {
                        addPoint(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
                    }
                    addPoint(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) {
                    addPoint(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    active = null
                    pointer = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                active?.let { ink.remove(it) }
                active = null
                pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_DISMISS, "Unterschrift löschen"
        ))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS && isEnabled) { clear(); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun addPoint(x: Float, y: Float) {
        val point = InkPoint((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
        active?.let { if (it.lastOrNull() != point) it.add(point) }
        invalidate()
    }
}

/**
 * Blocking IO; invoke on a worker. Pages are zero-based. Originals are never overwritten.
 * Results live in private cache/tools and are owned by the caller. Rewriting invalidates any
 * existing digital signatures. Handwriting is a visual signature, NOT a cryptographic signature.
 * Password-protected inputs must first be unlocked; callers must enforce captured permissions
 * for decrypted viewing copies, whose original access restrictions cannot be recovered here.
 */
class PdfMarkup(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "tools")
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /** Highlights up to 200 case-insensitive, non-overlapping matches, within individual lines. */
    fun highlightText(source: File, page: Int, query: String): File = edit(source, page) { document, target ->
        if (!document.currentAccessPermission.canModifyAnnotations()) {
            throw IOException("Das Hervorheben ist durch die PDF-Berechtigungen nicht erlaubt.")
        }
        if (!document.currentAccessPermission.canExtractContent()) {
            throw IOException("Die Textextraktion ist durch die PDF-Berechtigungen nicht erlaubt.")
        }
        val needle = Normalizer.normalize(query.trim(), Normalizer.Form.NFKC)
        if (needle.isEmpty() || needle.contains('\n') || needle.contains('\r')) {
            throw IOException("Bitte einen Suchtext innerhalb einer Textzeile eingeben.")
        }
        val collector = PositionedLines().apply { startPage = page + 1; endPage = page + 1; sortByPosition = true }
        collector.getText(document)
        collector.finishLine()
        if (collector.lines.none { it.text.isNotBlank() }) {
            throw IOException("Diese Seite enthält keinen PDF-Text. Gescannte Seiten benötigen zuerst OCR.")
        }
        var count = 0
        for (line in collector.lines) {
            var from = 0
            while (count < 200) {
                val start = line.text.indexOf(needle, from, ignoreCase = true)
                if (start < 0) break
                val end = start + needle.length
                val glyphs = line.glyphs.filter { it.start < end && it.end > start }.map { it.position }
                if (glyphs.any { it.dir != 0f }) throw IOException("Gedrehter Text wird beim Hervorheben noch nicht unterstützt.")
                if (glyphs.isNotEmpty()) {
                    val box = target.cropBox
                    val left = maxOf(box.lowerLeftX, glyphs.minOf { box.lowerLeftX + it.xDirAdj })
                    val right = minOf(box.upperRightX, glyphs.maxOf { box.lowerLeftX + it.xDirAdj + it.widthDirAdj })
                    val bottom = maxOf(box.lowerLeftY, glyphs.minOf { box.upperRightY - it.yDirAdj - it.heightDir * 0.15f })
                    val top = minOf(box.upperRightY, glyphs.maxOf { box.upperRightY - it.yDirAdj + it.heightDir * 1.15f })
                    if (right > left && top > bottom) {
                        target.annotations.add(PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                            rectangle = PDRectangle(left, bottom, right - left, top - bottom)
                            // PDFBox highlight appearance uses Adobe's top-left, top-right, bottom-left, bottom-right order.
                            quadPoints = floatArrayOf(left, top, right, top, left, bottom, right, bottom)
                            color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                            constantOpacity = 0.4f
                            contents = query
                            titlePopup = "PDFCraft"
                            isPrinted = true
                            this.page = target
                            constructAppearances(document)
                        })
                        count++
                    }
                }
                from = end
            }
            if (count >= 200) break
        }
        if (count == 0) throw IOException("Der Suchtext wurde auf dieser Seite nicht gefunden.")
    }

    /** Appends rounded black vector strokes in the bottom-right crop-box area; embeds no font. */
    fun addSignature(source: File, page: Int, strokes: List<List<InkPoint>>): File {
        val snapshot = strokes.map { it.toList() }
        if (snapshot.none { it.isNotEmpty() }) throw IOException("Bitte zuerst eine Unterschrift zeichnen.")
        if (snapshot.flatten().any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) {
            throw IOException("Die Unterschrift enthält ungültige Koordinaten.")
        }
        return edit(source, page) { document, target ->
            if (!document.currentAccessPermission.canModify()) {
                throw IOException("Das Einfügen einer Unterschrift ist durch die PDF-Berechtigungen nicht erlaubt.")
            }
            val box = target.cropBox
            val inset = minOf(24f, box.width * 0.05f, box.height * 0.05f)
            val areaWidth = minOf(180f, box.width * 0.4f)
            val areaHeight = minOf(72f, box.height * 0.15f)
            val left = box.upperRightX - inset - areaWidth
            val top = box.lowerLeftY + inset + areaHeight
            val thickness = minOf(1.8f, areaWidth / 100f, areaHeight / 30f)
            val padding = thickness / 2f
            fun x(p: InkPoint) = left + padding + p.x * (areaWidth - thickness)
            fun y(p: InkPoint) = top - padding - p.y * (areaHeight - thickness)
            PDPageContentStream(document, target, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.saveGraphicsState()
                stream.setStrokingColor(0f, 0f, 0f)
                stream.setLineWidth(thickness)
                stream.setLineCapStyle(1)
                stream.setLineJoinStyle(1)
                snapshot.filter { it.isNotEmpty() }.forEach { stroke ->
                    stream.moveTo(x(stroke.first()), y(stroke.first()))
                    if (stroke.size == 1) stream.lineTo(x(stroke.first()) + 0.01f, y(stroke.first()))
                    else stroke.drop(1).forEach { stream.lineTo(x(it), y(it)) }
                    stream.stroke()
                }
                stream.restoreGraphicsState()
            }
        }
    }

    private data class Glyph(val start: Int, val end: Int, val position: TextPosition)
    private data class Line(val text: String, val glyphs: List<Glyph>)
    private class PositionedLines : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        private val text = StringBuilder()
        private val glyphs = mutableListOf<Glyph>()
        override fun writeString(value: String, positions: MutableList<TextPosition>) {
            // One glyph can represent several Unicode characters (e.g. a ligature). Keep its
            // entire Unicode span mapped to that glyph rather than assuming one character each.
            positions.forEach { position ->
                val unicode = Normalizer.normalize(position.unicode.orEmpty(), Normalizer.Form.NFKC)
                val start = text.length
                text.append(unicode)
                if (unicode.isNotEmpty()) glyphs.add(Glyph(start, text.length, position))
            }
        }
        override fun writeWordSeparator() { text.append(' ') }
        override fun writeLineSeparator() { finishLine() }
        override fun writeParagraphSeparator() { finishLine() }
        fun finishLine() {
            if (text.isNotEmpty()) lines.add(Line(text.toString(), glyphs.toList()))
            text.setLength(0)
            glyphs.clear()
        }
    }

    private fun edit(source: File, page: Int, change: (PDDocument, PDPage) -> Unit): File {
        try {
            if (!source.isFile || !source.canRead()) throw IOException("Die PDF-Datei ist nicht vorhanden oder nicht lesbar.")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Der private PDF-Arbeitsordner konnte nicht erstellt werden.")
            val memory = MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(directory)
            PDDocument.load(source, memory).use { document ->
                if (page !in 0 until document.numberOfPages) throw IOException("Die ausgewählte Seite liegt außerhalb des PDFs.")
                val target = document.getPage(page)
                if (Math.floorMod(target.rotation, 360) != 0) throw IOException("Gedrehte Seiten werden beim Markieren noch nicht unterstützt.")
                val box = target.cropBox
                if (!box.width.isFinite() || !box.height.isFinite() || box.width <= 0f || box.height <= 0f) {
                    throw IOException("Die PDF-Seite hat ungültige Abmessungen.")
                }
                change(document, target)
                document.isAllSecurityToBeRemoved = true
                val result = File.createTempFile("pdfcraft-markup-", ".pdf", directory)
                try { document.save(result); return result } catch (error: Throwable) { result.delete(); throw error }
            }
        } catch (error: InvalidPasswordException) {
            throw IOException("Dieses PDF benötigt ein Passwort. Bitte zuerst zum Anzeigen entsperren.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Ungültige oder nicht unterstützte PDF-Daten.", error)
        } catch (error: UnsupportedOperationException) {
            throw IOException("Diese PDF-Funktion wird nicht unterstützt.", error)
        }
    }
}
