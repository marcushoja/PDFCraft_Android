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
        appearance=ScanProcessing.previewFilter(mode,tone);invalidate()
    }
    private var dragging = -1
    var onCornersChanged: ((List<PointF>) -> Unit)? = null
    var editable = true
        set(value) { field = value; invalidate() }
    var accentColor = ZenColors.light.accent
    private val density = resources.displayMetrics.density

    init {
        contentDescription = context.getString(R.string.scan_adjust_corners)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun setPhoto(bitmap: Bitmap?, corners: List<PointF> = defaultCorners()) {
        photo = bitmap
        points = corners.map { PointF(it.x, it.y) }
        updateRect()
        invalidate()
    }

    fun corners(): List<PointF> = points.map { PointF(it.x, it.y) }

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
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return false
                val candidate = corners().toMutableList()
                candidate[dragging] = PointF(((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
                    ((event.y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f))
                if (validCorners(candidate)) { points = candidate; invalidate(); onCornersChanged?.invoke(corners()) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging < 0) return false
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
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
