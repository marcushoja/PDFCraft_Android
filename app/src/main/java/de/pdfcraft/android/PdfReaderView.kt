package de.pdfcraft.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.Bundle
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

enum class ReadingMode { CONTINUOUS, SINGLE }

/** File-based PDF reader. Public methods and callbacks belong to the UI thread. */
class PdfReaderView(context: Context) : View(context) {
    var onPositionChanged: ((page: Int, offset: Float, zoom: Float) -> Unit)? = null
    var onDocumentLoaded: ((pageCount: Int) -> Unit)? = null
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
        fun closeDocument() {
            try { renderer?.close() } catch (_: Exception) { }
            renderer = null
            try { descriptor?.close() } catch (_: Exception) { }
            descriptor = null
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
    private val turnScroller = OverScroller(context, DecelerateInterpolator(2f))
    private val turnMatrix = Matrix()
    private val turnCorners = FloatArray(8)
    private val cardCorners = FloatArray(8)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var turning = false
    private var turnFrom = 0
    private var turnTo = 0
    private var turnStartX = 0f
    private var turnEndX = 0f
    private var settling = false
    private var scaledThisGesture = false
    private var gestureStartPage = 0
    private var pendingPosition: Triple<Int, Float, Float>? = null
    private var lastReported: Triple<Int, Float, Float>? = null
    private var documentReady = false
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
                    scrollXFloat += dx
                    if (mode == ReadingMode.SINGLE && zoom <= 1.01f && pageCount > 0) {
                        // A gesture may turn only one sheet, even during a long drag.
                        scrollXFloat = scrollXFloat.coerceIn(
                            starts[max(0, gestureStartPage - 1)] * zoom,
                            starts[min(pageCount - 1, gestureStartPage + 1)] * zoom)
                    }
                    scrollYFloat += dy
                    constrainScroll()
                    positionChanged()
                    invalidate()
                }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaledThisGesture || scaleDetector.isInProgress) return false
                if (mode == ReadingMode.SINGLE && zoom <= 1.01f && abs(vx) > abs(vy)) {
                    animateToPage(gestureStartPage + if (vx < 0) 1 else -1)
                    return true
                }
                scroller.fling(scrollXFloat.toInt(), scrollYFloat.toInt(),
                    (-vx).toInt(), (-vy).toInt(), 0, maxScrollX().toInt(), 0, maxScrollY().toInt())
                settling = true
                postInvalidateOnAnimation()
                return true
            }
        })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "PDF-Dokument"
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
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                document.descriptor = fd
                val pdf = PdfRenderer(fd)
                document.renderer = pdf
                val count = pdf.pageCount
                require(count > 0) { "Das PDF enthält keine Seiten." }
                val pw = FloatArray(count)
                val ph = FloatArray(count)
                for (i in 0 until count) {
                    if (generation.get() != token) return@execute
                    pdf.openPage(i).use { page ->
                        pw[i] = max(1, page.width).toFloat()
                        ph[i] = max(1, page.height).toFloat()
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
        // Arbitrary button navigation still displays just two sheets, not every intervening page.
        if (turning && abs(turnTo - turnFrom) > 1) {
            return if (turnProgress() < .5f) turnFrom else turnTo
        }
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
        canvas.drawColor(if (darkPages) Color.rgb(24, 24, 24) else Color.rgb(65, 65, 65))
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
        canvas.drawText(label, bounds.centerX(), bounds.centerY() - (metrics.ascent + metrics.descent) / 2f,
            loadingPaint)
    }

    private fun turnProgress(): Float = if (turnEndX == turnStartX) 1f
        else ((scrollXFloat - turnStartX) / (turnEndX - turnStartX)).coerceIn(0f, 1f)

    private fun drawSinglePages(canvas: Canvas) {
        val snapshots = cache.snapshot()
        val first: Int
        val second: Int
        val progress: Float
        val direction: Float
        if (turning && abs(turnTo - turnFrom) > 1) {
            first = turnFrom
            second = turnTo
            progress = turnProgress()
            direction = if (second > first) 1f else -1f
        } else {
            first = pageAt(scrollXFloat, zoom)
            second = min(pageCount - 1, first + 1)
            progress = ((scrollXFloat - starts[first] * zoom) / max(1f, width * zoom)).coerceIn(0f, 1f)
            direction = 1f
        }
        fun drawCard(page: Int, displacement: Float, depth: Float) {
            val rect = pageRect(page)
            // Center each sheet independently; layout scroll is only the transition's progress.
            rect.offset((width - rect.width()) / 2f - rect.left, 0f)
            val key = renderKey(page)
            val bitmap = cache.get(key) ?: snapshots.entries.filter { it.key.page == page }
                .minByOrNull { abs(it.key.width - key.width) }?.value
            val inset = rect.height() * .025f * depth
            val scale = 1f - .045f * depth
            val left = rect.centerX() - rect.width() * scale / 2f + displacement * width
            val right = left + rect.width() * scale
            val top = rect.top + rect.height() * (1f - scale) / 2f
            val bottom = top + rect.height() * scale
            cardCorners[0] = rect.left; cardCorners[1] = rect.top
            cardCorners[2] = rect.right; cardCorners[3] = rect.top
            cardCorners[4] = rect.right; cardCorners[5] = rect.bottom
            cardCorners[6] = rect.left; cardCorners[7] = rect.bottom
            val tilt = if (displacement < 0f) inset else -inset
            turnCorners[0] = left; turnCorners[1] = top + inset + tilt
            turnCorners[2] = right; turnCorners[3] = top + inset - tilt
            turnCorners[4] = right; turnCorners[5] = bottom - inset + tilt
            turnCorners[6] = left; turnCorners[7] = bottom - inset - tilt
            turnMatrix.reset()
            turnMatrix.setPolyToPoly(cardCorners, 0, turnCorners, 0, 4)
            canvas.save()
            canvas.concat(turnMatrix)
            // Soft cast shadow follows the moving sheet rather than the viewport.
            val shadowWidth = gap * (1f + depth)
            for (step in 8 downTo 1) {
                val spread = shadowWidth * step / 8f
                shadowPaint.color = Color.BLACK
                shadowPaint.alpha = (3 + 4 * depth).toInt()
                canvas.drawRoundRect(rect.left - spread, rect.top - spread / 2f,
                    rect.right + spread, rect.bottom + spread, spread, spread, shadowPaint)
            }
            placeholderPaint.color = if (darkPages) Color.BLACK else Color.WHITE
            canvas.drawRect(rect, placeholderPaint)
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, pagePaint)
            else {
                loadingPaint.color = if (darkPages) Color.LTGRAY else Color.DKGRAY
                canvas.save()
                canvas.clipRect(rect)
                val visible = RectF(rect)
                if (visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                    drawCenteredLabel(canvas, if (key in failedRenders) "Seite nicht verfügbar"
                        else "Seite wird geladen …", visible)
                }
                canvas.restore()
            }
            // Gentle fold-like edge shading gives motion depth without distorting the text heavily.
            if (depth > .001f) {
                val edge = if (displacement < 0f) rect.right else rect.left
                val shadeWidth = min(rect.width() * .12f, gap * 5f) * depth
                for (step in 0 until 12) {
                    shadowPaint.alpha = (32f * depth * (1f - step / 12f)).toInt()
                    val a = shadeWidth * step / 12f
                    val b = shadeWidth * (step + 1) / 12f
                    canvas.drawRect(if (displacement < 0f) edge - b else edge + a,
                        rect.top, if (displacement < 0f) edge - a else edge + b, rect.bottom, shadowPaint)
                }
            }
            canvas.restore()
        }
        if (second != first && progress > 0f) drawCard(second, direction * (1f - progress), 1f - progress)
        if (progress < 1f || second == first) drawCard(first, -direction * progress, progress)

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
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (!settling && !turning && !scaledThisGesture) snapPage()
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
            info.contentDescription = "PDF-Dokument, Seite ${currentPage + 1} von $pageCount"
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
        if (turning) {
            if (turnScroller.computeScrollOffset()) {
                scrollXFloat = turnScroller.currX.toFloat()
                constrainScroll()
                positionChanged()
                postInvalidateOnAnimation()
            } else {
                scrollXFloat = turnEndX
                turning = false
                constrainScroll()
                positionChanged()
                invalidate()
            }
            return
        }
        if (scroller.computeScrollOffset()) {
            scrollXFloat = scroller.currX.toFloat()
            scrollYFloat = scroller.currY.toFloat()
            constrainScroll()
            positionChanged()
            postInvalidateOnAnimation()
        } else if (settling) {
            settling = false
            snapPage()
        }
    }

    private fun snapPage() {
        if (mode != ReadingMode.SINGLE || zoom > 1.01f || pageCount == 0) return
        animateToPage(currentPage.coerceIn(max(0, gestureStartPage - 1), min(pageCount - 1, gestureStartPage + 1)))
    }

    private fun animateToPage(page: Int) {
        if (pageCount == 0) return
        val destination = page.coerceIn(0, pageCount - 1)
        val target = (starts[destination] * zoom).coerceIn(0f, maxScrollX())
        settling = false
        scroller.abortAnimation()
        turnScroller.abortAnimation()
        turning = false
        if (abs(target - scrollXFloat) > 1f) {
            turnFrom = currentPage
            turnTo = destination
            turnStartX = scrollXFloat
            turnEndX = target
            turning = true
            turnScroller.startScroll(scrollXFloat.toInt(), 0,
                target.toInt() - scrollXFloat.toInt(), 0, 280)
            postInvalidateOnAnimation()
        } else {
            scrollXFloat = target
            positionChanged()
            invalidate()
        }
    }

    private fun stopAnimations() {
        if (turning && abs(turnTo - turnFrom) > 1) {
            scrollXFloat = starts[positionPage()] * zoom
        }
        scroller.abortAnimation()
        turnScroller.abortAnimation()
        turning = false
        settling = false
    }
}
