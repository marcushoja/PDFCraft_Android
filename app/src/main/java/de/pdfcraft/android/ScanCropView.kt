package de.pdfcraft.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Corners are normalized, clockwise: top left, top right, bottom right, bottom left. */
class ScanCropView(context: Context) : View(context) {
    private var photo: Bitmap? = null
    private var points = defaultCorners()
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var appearance:ColorMatrixColorFilter?=null
    fun setAppearance(mode:ScanColorMode,tone:ScanTonePreset) {
        appearance=ScanProcessing.previewFilter(mode,tone,binaryPreview=mode==ScanColorMode.DOCUMENT);invalidate()
    }
    private var dragging = -1
    var onCornersChanged: ((List<PointF>) -> Unit)? = null
    var editable = true
        set(value) { field = value; if (!value) finishDrag(); invalidate() }
    var accentColor = ZenColors.light.accent
    private val density = resources.displayMetrics.density
    private val loupeRect = RectF()
    private val zoomRect = RectF()
    private val loupeClip = Path()
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(6f * density, 0f, 2f * density, 0x55000000)
    }

    init {
        contentDescription = context.getString(R.string.scan_adjust_corners)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun setPhoto(bitmap: Bitmap?, corners: List<PointF> = defaultCorners()) {
        finishDrag()
        photo = bitmap
        points = corners.map { PointF(it.x, it.y) }
        updateRect()
        invalidate()
    }

    fun corners(): List<PointF> = points.map { PointF(it.x, it.y) }

    /** A defensive snapshot for gesture/rendering tests. */
    internal fun magnifierBounds(): RectF? = if (updateMagnifierBounds()) RectF(loupeRect) else null

    private fun updateMagnifierBounds(): Boolean {
        if (!editable || photo == null || dragging !in points.indices || width <= 0 || height <= 0) return false
        val margin = min(12f * density, min(width, height) / 8f)
        val diameter = min(112f * density, min(width, height) - 2f * margin)
        // Keep the loupe at the top, opposite the active corner's original side.
        val left = if (dragging == 0 || dragging == 3) width - margin - diameter else margin
        loupeRect.set(left, margin, left + diameter, margin + diameter)
        return true
    }

    private fun drawMagnifier(canvas: Canvas, bitmap: Bitmap) {
        if (!updateMagnifierBounds()) return
        val cx = loupeRect.centerX()
        val cy = loupeRect.centerY()
        val radius = loupeRect.width() / 2f
        canvas.drawCircle(cx, cy, radius, shadowPaint)
        loupeClip.rewind()
        loupeClip.addCircle(cx, cy, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(loupeClip)
        canvas.drawColor(Color.WHITE)
        val active = points[dragging]
        // Enlarge the displayed photo, not a fitted thumbnail of the entire source.
        val zoomWidth = imageRect.width() * 3f
        val zoomHeight = imageRect.height() * 3f
        zoomRect.set(cx - active.x * zoomWidth, cy - active.y * zoomHeight,
            cx + (1f - active.x) * zoomWidth, cy + (1f - active.y) * zoomHeight)
        loupePaint.style = Paint.Style.FILL
        loupePaint.colorFilter = appearance
        canvas.drawBitmap(bitmap, null, zoomRect, loupePaint)
        loupePaint.colorFilter = null
        loupePaint.color = accentColor
        loupePaint.strokeWidth = 1.5f * density
        val arm = min(10f * density, radius / 3f)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, loupePaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, loupePaint)
        canvas.drawCircle(cx, cy, min(2f * density, arm / 2f), loupePaint)
        canvas.restoreToCount(saved)
        loupePaint.style = Paint.Style.STROKE
        loupePaint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, (radius - density).coerceAtLeast(0f), loupePaint)
    }

    private fun finishDrag() {
        dragging = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        finishDrag()
        super.onDetachedFromWindow()
    }

    private fun updateRect() {
        val bitmap = photo ?: return
        val padding = 24f * density
        val scale = min((width - padding * 2).coerceAtLeast(1f) / bitmap.width,
            (height - padding * 2).coerceAtLeast(1f) / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateRect() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = photo ?: return
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.colorFilter=appearance
        canvas.drawBitmap(bitmap, null, imageRect, paint)
        paint.colorFilter=null
        if (!editable) return
        val polygon = Path()
        points.forEachIndexed { index, p ->
            val x = imageRect.left + p.x * imageRect.width()
            val y = imageRect.top + p.y * imageRect.height()
            if (index == 0) polygon.moveTo(x, y) else polygon.lineTo(x, y)
        }
        polygon.close()
        val shade = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(imageRect, Path.Direction.CW); addPath(polygon) }
        paint.color = 0x66000000
        canvas.drawPath(shade, paint)
        paint.color = accentColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawPath(polygon, paint)
        paint.style = Paint.Style.FILL
        points.forEach { p ->
            val x = imageRect.left + p.x * imageRect.width()
            val y = imageRect.top + p.y * imageRect.height()
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 10f * density, paint)
            paint.color = accentColor
            canvas.drawCircle(x, y, 7f * density, paint)
        }
        drawMagnifier(canvas, bitmap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return super.onTouchEvent(event)
        if (photo == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = points.indices.minByOrNull { i ->
                    hypot(event.x - imageRect.left - points[i].x * imageRect.width(),
                        event.y - imageRect.top - points[i].y * imageRect.height())
                } ?: -1
                val p = points.getOrNull(dragging) ?: return false
                if (hypot(event.x - imageRect.left - p.x * imageRect.width(),
                        event.y - imageRect.top - p.y * imageRect.height()) > 36f * density) {
                    dragging = -1; return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                updateDraggedCorner(event.x,event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging < 0) return false
                // Android may coalesce MOVE events while the magnifier is being rendered.
                if(event.actionMasked==MotionEvent.ACTION_UP) updateDraggedCorner(event.x,event.y)
                finishDrag()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }
    private fun updateDraggedCorner(x:Float,y:Float) {
        val candidate=corners().toMutableList()
        candidate[dragging]=PointF(((x-imageRect.left)/imageRect.width()).coerceIn(0f,1f),
            ((y-imageRect.top)/imageRect.height()).coerceIn(0f,1f))
        if(validCorners(candidate)) { points=candidate;onCornersChanged?.invoke(corners()) }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        fun defaultCorners() = listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))

        /** Reject crossing, collapsed and concave quadrilaterals before processing. */
        fun validCorners(points: List<PointF>): Boolean {
            if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
            return points.indices.all { i ->
                val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > .002f
            }
        }
    }
}
