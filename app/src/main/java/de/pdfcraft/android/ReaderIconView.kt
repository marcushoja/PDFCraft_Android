package de.pdfcraft.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small, original monochrome symbols drawn in a 24-unit coordinate space. */
internal class ReaderIconView(context: Context, var symbol: String, var tint: Int) : View(context) {
    private var cachedSymbol: String? = null
    private var cachedTint = 0
    private var cachedDrawable: android.graphics.drawable.Drawable? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (cachedSymbol != symbol || cachedTint != tint) {
            cachedSymbol = symbol; cachedTint = tint
            cachedDrawable = MaterialSymbols.drawable(context,symbol,tint)
        }
        cachedDrawable?.let { drawable ->
            val left = ((width-size)/2).toInt(); val top = ((height-size)/2).toInt()
            drawable.setBounds(left,top,left+size.toInt(),top+size.toInt()); drawable.draw(canvas)
            return
        }
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24f, size / 24f)
        paint.color = tint
        paint.style = Paint.Style.STROKE
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        fun lines(vararg points: Float) {
            val path = Path()
            path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            canvas.drawPath(path, paint)
        }
        fun box(l: Float, t: Float, r: Float, b: Float) = canvas.drawRoundRect(l, t, r, b, 1.5f, 1.5f, paint)
        fun dot(x: Float, y: Float, radius: Float = 1.5f) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
        }
        when (symbol) {
            "folder" -> { lines(3f, 7f, 3f, 5f, 9f, 5f, 11f, 7f, 21f, 7f, 21f, 19f, 3f, 19f, 3f, 7f); line(3f, 10f, 21f, 10f) }
            "moon" -> {
                val path = Path().apply { moveTo(15f, 3f); cubicTo(1f, 0f, 0f, 20f, 13f, 21f); cubicTo(18f, 21f, 21f, 17f, 21f, 14f); cubicTo(12f, 18f, 8f, 9f, 15f, 3f); close() }
                paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
            }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "jump" -> { line(4f, 4f, 20f, 4f); line(12f, 20f, 12f, 8f); lines(7f, 13f, 12f, 8f, 17f, 13f) }
            "bookmark" -> lines(6f, 3f, 18f, 3f, 18f, 21f, 12f, 17f, 6f, 21f, 6f, 3f)
            "fullscreen" -> { lines(9f, 3f, 3f, 3f, 3f, 9f); lines(15f, 3f, 21f, 3f, 21f, 9f); lines(3f, 15f, 3f, 21f, 9f, 21f); lines(15f, 21f, 21f, 21f, 21f, 15f) }
            "crop" -> { lines(6f, 2f, 6f, 18f, 22f, 18f); lines(2f, 6f, 18f, 6f, 18f, 22f) }
            "camera" -> { lines(3f, 7f, 7f, 7f, 9f, 4f, 15f, 4f, 17f, 7f, 21f, 7f, 21f, 20f, 3f, 20f, 3f, 7f); canvas.drawCircle(12f, 13f, 4f, paint) }
            "text" -> { line(4f, 5f, 20f, 5f); line(12f, 5f, 12f, 20f); line(8f, 20f, 16f, 20f); line(4f, 5f, 4f, 8f); line(20f, 5f, 20f, 8f) }
            "share" -> { line(7f, 11f, 17f, 5f); line(7f, 13f, 17f, 19f); dot(5f, 12f, 3f); dot(19f, 4f, 3f); dot(19f, 20f, 3f) }
            "print" -> { box(3f, 8f, 21f, 17f); box(7f, 3f, 17f, 8f); box(7f, 14f, 17f, 21f); dot(18f, 11f, .8f) }
            "signature" -> { lines(3f, 18f, 8f, 8f, 10f, 6f, 11f, 9f, 7f, 17f, 11f, 15f, 13f, 12f, 13f, 17f, 17f, 15f, 21f, 15f); line(3f, 21f, 21f, 21f) }
            "reload" -> { canvas.drawArc(4f, 4f, 20f, 20f, 40f, 290f, false, paint); lines(15f, 3f, 20f, 7f, 21f, 2f) }
            "info" -> { canvas.drawCircle(12f, 12f, 9f, paint); dot(12f, 7f, 1f); line(12f, 11f, 12f, 17f) }
            "tools" -> { lines(4f, 20f, 13f, 11f, 12f, 6f, 15f, 3f, 15f, 8f, 19f, 8f, 21f, 5f, 21f, 10f, 17f, 13f, 14f, 13f, 6f, 22f, 4f, 20f) }
            "notes" -> { box(4f, 3f, 20f, 21f); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
            "outline" -> { for (y in listOf(6f, 12f, 18f)) { dot(4f, y, 1f); line(if (y == 12f) 10f else 8f, y, 21f, y) } }
            "settings" -> { for ((x, y) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) { line(x, 3f, x, y - 2f); line(x, y + 2f, x, 21f); canvas.drawCircle(x, y, 2f, paint) } }
            "quote" -> { box(3f, 5f, 9f, 12f); box(14f, 5f, 20f, 12f); lines(9f, 12f, 9f, 15f, 5f, 19f); lines(20f, 12f, 20f, 15f, 16f, 19f) }
            "close" -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
            "back" -> { lines(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
            "expanded" -> lines(6f, 9f, 12f, 15f, 18f, 9f)
            "collapsed" -> lines(9f, 6f, 15f, 12f, 9f, 18f)
            else -> { box(4f, 4f, 20f, 20f); dot(8f, 12f, 1f); dot(12f, 12f, 1f); dot(16f, 12f, 1f) }
        }
        canvas.restore()
    }
}
