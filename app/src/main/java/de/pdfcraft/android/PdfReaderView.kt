package de.pdfcraft.android

import android.content.Context
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ReadingMode { CONTINUOUS, SINGLE }

/** File-based PDF reader. Public methods and callbacks belong to the UI thread. */
class PdfReaderView(context: Context) : View(context) {
    var onPositionChanged: ((page: Int, offset: Float, zoom: Float) -> Unit)? = null
    var onDocumentLoaded: ((pageCount: Int) -> Unit)? = null
    var onLoadProgress: ((loaded: Int, total: Int) -> Unit)? = null
    var onFirstPageRendered: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    val pageCount: Int get() = pageWidths.size
    val currentPage: Int get() = positionPage()

    private var mode = ReadingMode.CONTINUOUS
    private var fitPage = false
    private var darkPages = false
    private var zoom = 1f
    private var scrollXFloat = 0f
    private var scrollYFloat = 0f
    private var pageWidths = FloatArray(0)
    private var pageHeights = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)
    private var starts = FloatArray(1)
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var maximumPageHeight = 0f
    private val uiHandler = Handler(Looper.getMainLooper())
    private val gap = 12f * resources.displayMetrics.density
    private val generation = AtomicInteger()
    private class DocumentWorker {
        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PDFCraft-render").apply { isDaemon = true }
        }
        // Only this worker's serial executor accesses the document and its pages.
        var renderer: PdfRenderer? = null
        var descriptor: ParcelFileDescriptor? = null
        var prepared: PreparedPdf? = null
        fun closeDocument() {
            try { renderer?.close() } catch (_: Exception) { }
            renderer = null
            try { descriptor?.close() } catch (_: Exception) { }
            descriptor = null
            prepared?.close()
            prepared = null
        }
    }
    private var worker: DocumentWorker? = null
    private var renderInFlight = false
    private val failedRenders = HashSet<RenderKey>()
    private data class RenderKey(val page: Int, val width: Int, val height: Int)
    private val cache = object : LruCache<RenderKey, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = value.allocationByteCount
        // Do not recycle evicted images: a hardware Canvas may still reference them.
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.WHITE }
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val scroller = OverScroller(context)
    private val turnMatrix = Matrix()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var curlAnimator: ValueAnimator? = null
    private val curlCorner = PointF()
    private val curlFinger = PointF()
    private var downX = 0f
    private var downY = 0f
    private var gestureAxis = 0 // 0 undecided, 1 horizontal fold, 2 vertical pan
    private var turning = false
    private var turnFrom = 0
    private var turnTo = 0
    private var settling = false
    private var scaledThisGesture = false
    private var gestureStartPage = 0
    private var pendingPosition: Triple<Int, Float, Float>? = null
    private var lastReported: Triple<Int, Float, Float>? = null
    private var documentReady = false
    private var firstPageRendered = false
    private var openingDocument = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaledThisGesture = true
                stopAnimations()
                return pageCount > 0
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                changeZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    private val gestures = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                stopAnimations()
                settling = false
                gestureStartPage = currentPage
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (gestureAxis == 1) return true
                performClick()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scaledThisGesture = true
                changeZoom(if (zoom > 1.1f) 1f else 2.5f, e.x, e.y)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!scaleDetector.isInProgress && !scaledThisGesture) {
                    if (mode == ReadingMode.SINGLE && zoom <= 1.01f) {
                        if (gestureAxis != 2) return true
                        scrollYFloat += dy
                        constrainScroll()
                        positionChanged()
                        invalidate()
                        return true
                    }
                    scrollXFloat += dx
                    scrollYFloat += dy
                    constrainScroll()
                    positionChanged()
                    invalidate()
                }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaledThisGesture || scaleDetector.isInProgress) return false
                if (mode == ReadingMode.SINGLE && zoom <= 1.01f && gestureAxis != 2) {
                    if (turning) settleCurl(turnProgress() > .32f ||
                        (if (turnTo > turnFrom) -vx else vx) > 650f * resources.displayMetrics.density)
                    return true
                }
                val verticalOnly = mode == ReadingMode.SINGLE && zoom <= 1.01f
                scroller.fling(scrollXFloat.toInt(), scrollYFloat.toInt(),
                    if (verticalOnly) 0 else (-vx).toInt(), (-vy).toInt(),
                    if (verticalOnly) scrollXFloat.toInt() else 0,
                    if (verticalOnly) scrollXFloat.toInt() else maxScrollX().toInt(),
                    0, maxScrollY().toInt())
                settling = true
                postInvalidateOnAnimation()
                return true
            }
        })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = UiText.t(context,"PDF-Dokument")
    }

    fun open(file: File, initialPage: Int = 0, initialOffset: Float = 0f, initialZoom: Float = 1f) {
        close()
        val token = generation.get()
        pendingPosition = Triple(initialPage, finite(initialOffset, 0f).coerceIn(0f, 1f),
            finite(initialZoom, 1f).coerceIn(1f, 5f))
        val document = worker ?: DocumentWorker().also { worker = it }
        openingDocument = true
        invalidate()
        document.executor.execute {
            try {
                if (generation.get() != token) return@execute
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                val prepared = PdfAnnotationRendering.prepare(file,context.cacheDir)
                document.prepared = prepared
                if (generation.get() != token) { document.closeDocument();return@execute }
                val fd = ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY)
                document.descriptor = fd
                val pdf = PdfRenderer(fd)
                document.renderer = pdf
                val count = pdf.pageCount
                require(count > 0) { "Das PDF enthält keine Seiten." }
                val pw = FloatArray(count)
                val ph = FloatArray(count)
                var lastProgress = SystemClock.uptimeMillis()
                uiHandler.post { if (generation.get() == token) onLoadProgress?.invoke(0, count) }
                for (i in 0 until count) {
                    if (generation.get() != token) return@execute
                    pdf.openPage(i).use { page ->
                        pw[i] = max(1, page.width).toFloat()
                        ph[i] = max(1, page.height).toFloat()
                    }
                    val now = SystemClock.uptimeMillis()
                    if (i == count - 1 || (i + 1) % 20 == 0 || now - lastProgress >= 50L) {
                        val loaded = i + 1
                        lastProgress = now
                        uiHandler.post {
                            if (generation.get() == token) onLoadProgress?.invoke(loaded, count)
                        }
                    }
                }
                uiHandler.post {
                    if (generation.get() == token) {
                        pageWidths = pw
                        pageHeights = ph
                        openingDocument = false
                        rebuildLayout()
                        restorePendingPosition()
                        invalidate()
                        documentReady = true
                        onDocumentLoaded?.invoke(count)
                        if (generation.get() == token) positionChanged()
                    }
                }
            } catch (error: Exception) {
                document.closeDocument()
                val message = when (error) {
                    is SecurityException -> "Das PDF ist verschlüsselt oder der Zugriff wurde verweigert."
                    is java.io.FileNotFoundException -> "Die PDF-Datei wurde nicht gefunden oder ist nicht lesbar."
                    else -> "Das PDF kann nicht geöffnet werden. Es ist möglicherweise beschädigt oder passwortgeschützt."
                }
                uiHandler.post {
                    if (generation.get() == token) {
                        openingDocument = false
                        pendingPosition = null
                        invalidate()
                        onError?.invoke(message)
                    }
                }
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        stopAnimations()
        settling = false
        renderInFlight = false
        pendingPosition = null
        lastReported = null
        documentReady = false
        firstPageRendered = false
        openingDocument = false
        pageWidths = FloatArray(0)
        pageHeights = FloatArray(0)
        widths = FloatArray(0)
        heights = FloatArray(0)
        starts = FloatArray(1)
        contentWidth = 0f
        contentHeight = 0f
        maximumPageHeight = 0f
        scrollXFloat = 0f
        scrollYFloat = 0f
        zoom = 1f
        cache.evictAll()
        failedRenders.clear()
        worker?.let { document -> document.executor.execute { document.closeDocument() } }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        close()
        worker?.executor?.shutdown() // Queued close runs after the currently open page finishes.
        worker = null
        super.onDetachedFromWindow()
    }

    fun goToPage(page: Int) {
        if (pageCount == 0) return
        stopAnimations()
        settling = false
        setPosition(page.coerceIn(0, pageCount - 1), 0f)
    }

    /** Animated sheet navigation at fit zoom; other modes retain instant navigation. */
    fun turnToPage(page: Int) {
        if (mode != ReadingMode.SINGLE || zoom > 1.01f || width == 0) {
            goToPage(page)
            return
        }
        if (pageCount == 0) return
        stopAnimations()
        gestureStartPage = currentPage
        animateToPage(page)
    }

    fun setReadingMode(mode: ReadingMode) {
        if (this.mode == mode) return
        val saved = position()
        this.mode = mode
        stopAnimations()
        rebuildLayout()
        setPosition(saved.first, saved.second)
    }

    fun setFitPage(enabled: Boolean) {
        if (fitPage == enabled) return
        val saved = position()
        fitPage = enabled
        zoom = 1f
        stopAnimations()
        rebuildLayout()
        setPosition(saved.first, saved.second)
    }

    fun setDarkPages(enabled: Boolean) {
        darkPages = enabled
        pagePaint.colorFilter = if (enabled) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))) else null
        invalidate()
    }

    fun resetZoom() = changeZoom(1f, width / 2f, height / 2f)

    private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback

    private fun changeZoom(value: Float, focusX: Float, focusY: Float) {
        stopAnimations()
        val next = finite(value, zoom).coerceIn(1f, 5f)
        val anchorPage = pageAt(if (mode == ReadingMode.CONTINUOUS)
            scrollYFloat + focusY else scrollXFloat + focusX, zoom)
        val before = if (pageCount > 0) pageRect(anchorPage) else null
        val anchorX = if (before != null) (focusX - before.left) / max(1f, before.width()) else 0f
        val anchorY = if (before != null) (focusY - before.top) / max(1f, before.height()) else 0f
        val ratio = next / zoom
        zoom = next
        scrollXFloat = (scrollXFloat + focusX) * ratio - focusX
        scrollYFloat = (scrollYFloat + focusY) * ratio - focusY
        updateContentSize()
        if (before != null) {
            val after = pageRect(anchorPage)
            scrollXFloat += after.left + after.width() * anchorX - focusX
            scrollYFloat += after.top + after.height() * anchorY - focusY
        }
        constrainScroll()
        positionChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        stopAnimations()
        val saved = position()
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
        if (pendingPosition != null) restorePendingPosition()
        else setPosition(saved.first, saved.second)
    }

    private fun rebuildLayout() {
        widths = FloatArray(pageCount)
        heights = FloatArray(pageCount)
        starts = FloatArray(pageCount + 1)
        maximumPageHeight = 0f
        if (width <= 0 || height <= 0) return
        for (i in 0 until pageCount) {
            val scale = if (fitPage) min(width / pageWidths[i], height / pageHeights[i])
                else width / pageWidths[i]
            widths[i] = pageWidths[i] * scale
            heights[i] = pageHeights[i] * scale
            maximumPageHeight = max(maximumPageHeight, heights[i])
            starts[i + 1] = starts[i] + if (mode == ReadingMode.CONTINUOUS)
                heights[i] + gap else max(width.toFloat(), widths[i])
        }
        updateContentSize()
        constrainScroll()
    }

    private fun updateContentSize() {
        if (mode == ReadingMode.CONTINUOUS) {
            contentWidth = width * zoom
            // Leave enough trailing space to align even a short final page at the top.
            val lastStart = if (pageCount > 0) starts[pageCount - 1] * zoom else 0f
            val lastEnd = if (pageCount > 0) (starts.last() - gap) * zoom else 0f
            contentHeight = max(lastStart + height, lastEnd)
        } else {
            contentWidth = max(width.toFloat(), starts.last() * zoom)
            contentHeight = max(height.toFloat(), maximumPageHeight * zoom)
        }
    }

    private fun maxScrollX() = max(0f, contentWidth - width)
    private fun maxScrollY(): Float = when {
        pageCount == 0 -> 0f
        mode == ReadingMode.SINGLE -> max(0f, heights[positionPage()] * zoom - height)
        // Avoid rounding (lastStart + viewport) - viewport below lastStart.
        else -> max(starts[pageCount - 1] * zoom, contentHeight - height)
    }
    private fun constrainScroll() {
        scrollXFloat = scrollXFloat.coerceIn(0f, maxScrollX())
        scrollYFloat = scrollYFloat.coerceIn(0f, maxScrollY())
    }

    private fun pageAt(coordinate: Float, scale: Float = 1f): Int {
        if (pageCount == 0) return 0
        var low = 0
        var high = pageCount
        while (low + 1 < high) {
            val mid = (low + high) / 2
            // Compare in scroll coordinates: dividing an exact page start by zoom
            // can round down and incorrectly select the preceding page/gap.
            if (starts[mid] * scale <= coordinate) low = mid else high = mid
        }
        return low.coerceIn(0, pageCount - 1)
    }

    private fun positionPage(): Int {
        if (turning) return turnFrom
        return pageAt(if (mode == ReadingMode.CONTINUOUS)
            scrollYFloat else scrollXFloat + width / 2f, zoom)
    }

    private fun position(): Pair<Int, Float> {
        if (pageCount == 0) return 0 to 0f
        val page = positionPage()
        val origin = if (mode == ReadingMode.CONTINUOUS) starts[page] else 0f
        return page to ((scrollYFloat - origin * zoom) / max(1f, heights[page] * zoom)).coerceIn(0f, 1f)
    }

    private fun setPosition(page: Int, offset: Float) {
        if (pageCount == 0 || width == 0 || height == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        if (mode == ReadingMode.CONTINUOUS) {
            scrollYFloat = (starts[p] + heights[p] * offset.coerceIn(0f, 1f)) * zoom
            scrollXFloat = maxScrollX() / 2f
        } else {
            scrollXFloat = starts[p] * zoom + (width * zoom - width) / 2f
            scrollYFloat = heights[p] * offset.coerceIn(0f, 1f) * zoom
        }
        constrainScroll()
        positionChanged()
        invalidate()
    }

    private fun restorePendingPosition() {
        if (pageCount == 0 || width == 0 || height == 0) return
        val saved = pendingPosition ?: return
        pendingPosition = null
        zoom = saved.third
        updateContentSize()
        setPosition(saved.first, saved.second)
    }

    private fun positionChanged() {
        if (pageCount == 0 || !documentReady || pendingPosition != null) return
        val p = position()
        val state = Triple(p.first, p.second, zoom)
        if (state != lastReported) {
            lastReported = state
            onPositionChanged?.invoke(state.first, state.second, state.third)
        }
    }

    private fun pageRect(page: Int): RectF {
        val w = widths[page] * zoom
        val h = heights[page] * zoom
        val left = if (mode == ReadingMode.CONTINUOUS) (contentWidth - w) / 2f
            else starts[page] * zoom + (width * zoom - w) / 2f
        val top = if (mode == ReadingMode.CONTINUOUS) starts[page] * zoom
            else max(0f, (height - h) / 2f)
        return RectF(left - scrollXFloat, top - scrollYFloat,
            left + w - scrollXFloat, top + h - scrollYFloat)
    }

    private fun renderKey(page: Int): RenderKey {
        // Quantized zoom avoids rendering every intermediate pinch frame.
        val level = ceil(zoom * 2f) / 2f
        var w = max(1f, widths[page] * level)
        var h = max(1f, heights[page] * level)
        val coordinate = if (mode == ReadingMode.CONTINUOUS) scrollYFloat else scrollXFloat
        val extent = if (mode == ReadingMode.CONTINUOUS) height else width
        val visibleCount = max(1, pageAt(coordinate + extent, zoom) - pageAt(coordinate, zoom) + 1)
        // Three fit-zoom sheets fit inside the cache, including both preloaded neighbors.
        val pixelBudget = if (mode == ReadingMode.SINGLE && zoom <= 1.01f) 2_000_000.0
            else min(4_000_000.0, 6_000_000.0 / visibleCount)
        val reduction = min(1.0, kotlin.math.sqrt(pixelBudget / (w.toDouble() * h)))
        w *= reduction.toFloat()
        h *= reduction.toFloat()
        return RenderKey(page, max(1, w.toInt()), max(1, h.toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (darkPages) ZenColors.dark.paper else ZenColors.light.tint)
        if (width == 0 || height == 0) return
        if (pageCount == 0) {
            if (openingDocument) {
                loadingPaint.color = Color.WHITE
                drawCenteredLabel(canvas, "PDF wird geladen …", RectF(0f, 0f, width.toFloat(), height.toFloat()))
            }
            return
        }
        if (mode == ReadingMode.SINGLE && zoom <= 1.01f) {
            drawSinglePages(canvas)
            return
        }
        val start = if (mode == ReadingMode.CONTINUOUS) scrollYFloat else scrollXFloat
        val extent = if (mode == ReadingMode.CONTINUOUS) height else width
        val first = pageAt(start, zoom)
        val last = pageAt(start + extent, zoom)
        var request: RenderKey? = null
        var requestDistance = Int.MAX_VALUE
        val snapshots = cache.snapshot()
        for (page in first..last) {
            val rect = pageRect(page)
            if (rect.right < 0 || rect.left > width || rect.bottom < 0 || rect.top > height) continue
            val key = renderKey(page)
            val exact = cache.get(key)
            val bitmap = exact ?: snapshots.entries.filter { it.key.page == page }
                .minByOrNull { abs(it.key.width - key.width) }?.value
            placeholderPaint.color = if (darkPages) Color.BLACK else Color.WHITE
            canvas.drawRect(rect, placeholderPaint)
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, pagePaint)
            else {
                loadingPaint.color = if (darkPages) Color.LTGRAY else Color.DKGRAY
                val visible = RectF(rect)
                if (visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                    canvas.save()
                    canvas.clipRect(visible)
                    drawCenteredLabel(canvas, if (key in failedRenders) "Seite nicht verfügbar"
                        else "Seite wird geladen …", visible)
                    canvas.restore()
                }
            }
            if (exact == null && key !in failedRenders) {
                val distance = abs(page - currentPage)
                if (distance < requestDistance) {
                    request = key
                    requestDistance = distance
                }
            }
        }
        if (!renderInFlight && request != null && !scaleDetector.isInProgress) requestRender(request)
    }

    private fun drawCenteredLabel(canvas: Canvas, label: String, bounds: RectF) {
        val metrics = loadingPaint.fontMetrics
        canvas.drawText(UiText.t(context,label), bounds.centerX(), bounds.centerY() - (metrics.ascent + metrics.descent) / 2f,
            loadingPaint)
    }

    private fun turnProgress(): Float =
        (abs(curlCorner.x - curlFinger.x) / max(1f, sheetRect(turnFrom).width())).coerceIn(0f, 1f)

    private fun sheetRect(page: Int): RectF = pageRect(page).apply {
        offset((width - width()) / 2f - left, 0f)
        // The new page opens at its top, independent of the old tall sheet's scroll offset.
        if (turning && page == turnTo) offset(0f, scrollYFloat)
    }

    private fun polygonPath(points: List<PdfCurlGeometry.Point>): Path = Path().apply {
        if (points.isNotEmpty()) {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
    }

    private fun drawSinglePages(canvas: Canvas) {
        val snapshots = cache.snapshot()
        val first = if (turning) turnFrom else currentPage
        val second = if (turning) turnTo else first
        fun bitmapFor(page: Int): Bitmap? {
            val key = renderKey(page)
            return cache.get(key) ?: snapshots.entries.filter { it.key.page == page }
                .minByOrNull { abs(it.key.width - key.width) }?.value
        }
        fun drawSheet(page: Int) {
            val rect = sheetRect(page)
            val bitmap = bitmapFor(page)
            placeholderPaint.color = if (darkPages) Color.BLACK else Color.WHITE
            canvas.drawRect(rect, placeholderPaint)
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, pagePaint)
            else {
                loadingPaint.color = if (darkPages) Color.LTGRAY else Color.DKGRAY
                canvas.save()
                canvas.clipRect(rect)
                val visible = RectF(rect)
                if (visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                    drawCenteredLabel(canvas, if (renderKey(page) in failedRenders) "Seite nicht verfügbar"
                        else "Seite wird geladen …", visible)
                }
                canvas.restore()
            }
        }
        if (!turning) drawSheet(first)
        else {
            drawSheet(second)
            val rect = sheetRect(first)
            val geometry = PdfCurlGeometry(curlCorner.x, curlCorner.y, curlFinger.x, curlFinger.y)
            val polygon = listOf(PdfCurlGeometry.Point(rect.left, rect.top),
                PdfCurlGeometry.Point(rect.right, rect.top), PdfCurlGeometry.Point(rect.right, rect.bottom),
                PdfCurlGeometry.Point(rect.left, rect.bottom))
            val front = polygonPath(geometry.clip(polygon, false))
            val patch = polygonPath(geometry.clip(polygon, true))
            val nx = geometry.nx
            val ny = geometry.ny
            val mx = geometry.mx
            val my = geometry.my
            turnMatrix.setValues(geometry.matrixValues())
            val back = Path(patch).apply { transform(turnMatrix) }
            val curvature = min(gap * 4f, max(2f, geometry.length * .16f))
            // Shadow cast by the lifted sheet onto the exposed underlying page.
            canvas.save()
            canvas.clipRect(rect)
            canvas.clipPath(patch)
            shadowPaint.shader = LinearGradient(mx, my, mx + nx * curvature, my + ny * curvature,
                Color.argb(100, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            shadowPaint.alpha = 255
            canvas.drawRect(rect, shadowPaint)
            canvas.restore()
            canvas.save()
            canvas.clipPath(front)
            drawSheet(first)
            canvas.restore()
            // The reflected corner contains C -> P: its paper backside stays under the finger.
            canvas.save()
            canvas.clipPath(back)
            backPaint.color = if (darkPages) Color.rgb(65, 64, 60) else Color.rgb(239, 232, 214)
            backPaint.alpha = 255
            canvas.drawPath(back, backPaint)
            canvas.save()
            canvas.concat(turnMatrix)
            backPaint.colorFilter = pagePaint.colorFilter
            backPaint.alpha = 28
            bitmapFor(first)?.let { canvas.drawBitmap(it, null, rect, backPaint) }
            backPaint.colorFilter = null
            backPaint.alpha = 255
            canvas.restore()
            shadowPaint.shader = LinearGradient(mx, my, mx - nx * curvature, my - ny * curvature,
                intArrayOf(Color.argb(110, 45, 36, 24), Color.argb(65, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(back, shadowPaint)
            canvas.restore()
            shadowPaint.shader = null
        }

        // One serial render at a time, visible sheets first, then exactly one neighbor each side.
        if (!renderInFlight && !scaleDetector.isInProgress) {
            val center = currentPage
            val candidates = linkedSetOf(center, first, second)
            // During a distant jump, avoid evicting the two animated sheets for intermediate neighbors.
            if (!turning || abs(turnTo - turnFrom) <= 1) {
                candidates.add(center - 1)
                candidates.add(center + 1)
            }
            val request = candidates.filter { it in 0 until pageCount }.map { renderKey(it) }
                .firstOrNull { cache.get(it) == null && it !in failedRenders }
            if (request != null) requestRender(request)
        }
    }

    private fun requestRender(key: RenderKey) {
        val document = worker ?: return
        val token = generation.get()
        renderInFlight = true
        document.executor.execute {
            var bitmap: Bitmap? = null
            var error: Throwable? = null
            try {
                if (token == generation.get()) {
                    val pdf = document.renderer
                    if (pdf != null) {
                        pdf.openPage(key.page).use { page ->
                            val image = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                            bitmap = image
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (failure: Exception) { error = failure }
            catch (failure: OutOfMemoryError) { error = failure }
            val result = bitmap
            val failed = error != null
            uiHandler.post {
                if (token != generation.get()) {
                    result?.recycle()
                } else {
                    renderInFlight = false
                    if (!failed && result != null) {
                        // Keep one sharp version per page, not every previous zoom level.
                        cache.snapshot().keys.filter { it.page == key.page && it != key }.forEach { cache.remove(it) }
                        cache.put(key, result)
                        if (!firstPageRendered && documentReady && key.page == currentPage) {
                            firstPageRendered = true
                            onFirstPageRendered?.invoke()
                        }
                    }
                    else {
                        result?.recycle()
                        failedRenders.add(key)
                        onError?.invoke("Diese PDF-Seite konnte nicht dargestellt werden. Möglicherweise ist sie beschädigt oder zu groß.")
                    }
                    invalidate()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scaledThisGesture = false
            downX = event.x
            downY = event.y
            gestureAxis = 0
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        // Input dispatchers may coalesce the final MOVE under rendering load. The release
        // coordinate is authoritative, otherwise a completed swipe could wrongly roll back.
        if ((event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) && event.pointerCount == 1 &&
            !scaledThisGesture && mode == ReadingMode.SINGLE && zoom <= 1.01f && pageCount > 0) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (gestureAxis == 0 && max(abs(dx), abs(dy)) > 3f * resources.displayMetrics.density) {
                gestureAxis = if (abs(dx) > abs(dy)) 1 else 2
                if (gestureAxis == 1) {
                    val target = gestureStartPage + if (dx < 0f) 1 else -1
                    if (target in 0 until pageCount) beginCurl(target, downY)
                }
            }
            if (gestureAxis == 1 && turning && curlAnimator == null) {
                val rect = sheetRect(turnFrom)
                val direction = if (turnTo > turnFrom) 1f else -1f
                // C reflects exactly onto the actual touch, not onto a translated card edge.
                val travel = ((curlCorner.x - event.x) * direction).coerceIn(.5f, rect.width() * 2f)
                curlFinger.x = curlCorner.x - direction * travel
                val top = max(0f, rect.top)
                val bottom = min(height.toFloat(), rect.bottom)
                curlFinger.y = event.y.coerceIn(top + .5f, max(top + .5f, bottom - .5f))
                invalidate()
            }
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (turning && curlAnimator == null) {
                settleCurl(event.actionMasked != MotionEvent.ACTION_CANCEL && turnProgress() > .32f)
            }
            invalidate() // Schedule the sharp render after a pinch ends.
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = pageCount > 1
        if (pageCount > 0) {
            info.contentDescription = UiText.t(context,"PDF-Dokument, Seite ${currentPage + 1} von $pageCount")
            if (currentPage > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (currentPage < pageCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> currentPage + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> currentPage - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (target !in 0 until pageCount) return false
        turnToPage(target)
        return true
    }

    override fun computeScroll() {
        if (turning) return
        if (scroller.computeScrollOffset()) {
            scrollXFloat = scroller.currX.toFloat()
            scrollYFloat = scroller.currY.toFloat()
            constrainScroll()
            positionChanged()
            postInvalidateOnAnimation()
        } else if (settling) {
            settling = false
        }
    }

    private fun animateToPage(page: Int) {
        if (pageCount == 0) return
        val destination = page.coerceIn(0, pageCount - 1)
        if (destination == currentPage) return
        beginCurl(destination, height.toFloat())
        settleCurl(true)
    }

    private fun beginCurl(destination: Int, touchY: Float) {
        stopAnimations()
        turnFrom = currentPage
        turnTo = destination
        val rect = sheetRect(turnFrom)
        // Use a viewport corner on a vertically scrolled tall sheet, not an offscreen corner.
        curlCorner.set(if (destination > turnFrom) rect.right else rect.left,
            if (touchY < height / 2f) max(0f, rect.top) else min(height.toFloat(), rect.bottom))
        curlFinger.set(curlCorner.x + if (destination > turnFrom) -.5f else .5f,
            curlCorner.y + if (touchY < height / 2f) .5f else -.5f)
        turning = true
        invalidate()
    }

    private fun settleCurl(complete: Boolean) {
        if (!turning || curlAnimator != null) return
        val rect = sheetRect(turnFrom)
        val sx = curlFinger.x
        val sy = curlFinger.y
        val direction = if (turnTo > turnFrom) 1f else -1f
        // A flat crease beyond the far edge removes the entire front and backside.
        val ex = if (complete) curlCorner.x - direction * (rect.width() * 2f + gap) else curlCorner.x
        val ey = curlCorner.y
        val animator = ValueAnimator.ofFloat(0f, 1f)
        curlAnimator = animator
        animator.duration = if (complete) 320L else 220L
        animator.interpolator = DecelerateInterpolator(1.5f)
        animator.addUpdateListener {
            val t = it.animatedValue as Float
            val lift = if (complete) sin(Math.PI * t).toFloat() * min(height * .12f, gap * 6f) else 0f
            val inward = if (curlCorner.y < height / 2f) 1f else -1f
            curlFinger.set(sx + (ex - sx) * t, sy + (ey - sy) * t + inward * lift)
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) { canceled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (canceled) return
                curlAnimator = null
                turning = false
                if (complete) setPosition(turnTo, 0f) else invalidate()
            }
        })
        animator.start()
    }

    private fun stopAnimations() {
        scroller.abortAnimation()
        curlAnimator?.cancel()
        curlAnimator = null
        turning = false
        settling = false
    }
}
