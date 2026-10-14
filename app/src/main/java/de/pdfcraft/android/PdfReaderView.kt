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

enum class ReadingMode { CONTINUOUS, SINGLE, SPREAD, BOOK, COMIC }

/** File-based PDF reader. Public methods and callbacks belong to the UI thread. */
class PdfReaderView(context: Context) : View(context) {
    var onPositionChanged: ((page: Int, offset: Float, zoom: Float) -> Unit)? = null
    var onDocumentLoaded: ((pageCount: Int) -> Unit)? = null
    var onLoadProgress: ((loaded: Int, total: Int) -> Unit)? = null
    var onFirstPageRendered: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onSelectionChanged: ((page: Int, selection: PdfTextSelection?) -> Unit)? = null
    /** Decrypted viewing copies must retain the original document's extraction permission. */
    var textSelectionAllowed:Boolean=true
        set(value) { field=value;if(!value) clearTextSelection() }
    var textSelection: PdfTextSelection? = null
        private set
    var textSelectionPage: Int = -1
        private set

    private var sourceFile: File? = null
    private val textCache = LruCache<Int, PdfPageText>(3)
    private var selectionRequest = 0
    private var requestedSelectionPage = -1
    private var selectionStart = 0
    private var selectionEnd = 0
    private var selectionGesture = false
    private var draggingHandle = 0
    private var consumeSelectionTap = false
    private val handleRadius = 9f * resources.displayMetrics.density
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(75, 35, 120, 245) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 120, 245) }

    val pageCount: Int get() = pageWidths.size
    val currentPage: Int get() = positionPage()

    private var mode = ReadingMode.CONTINUOUS
    val readingMode: ReadingMode get() = mode
    private var rightToLeft = false
    val isRightToLeft: Boolean get() = rightToLeft
    private val vertical: Boolean get() = mode == ReadingMode.CONTINUOUS || mode == ReadingMode.COMIC
    private val spread: Boolean get() = mode == ReadingMode.SPREAD || mode == ReadingMode.BOOK
    private var groups: List<List<Int>> = emptyList()
    private var pageGroups = IntArray(0)
    private var localLefts = FloatArray(0)
    private var localTops = FloatArray(0)
    private var focusedPage = 0
    /** Zero-based document pages of the active group, always in logical reading order. */
    val visiblePages: List<Int> get() = if (pageCount == 0) emptyList()
        else if (spread) groups[pageGroups[currentPage]] else listOf(currentPage)
    fun visiblePageNumbers(): List<Int> = visiblePages
    private val pageGap: Float get() = if (mode == ReadingMode.COMIC) 0f else gap
    private fun physicalForward(forward: Boolean): Boolean = forward != isRightToLeft
    private fun groupTarget(forward: Boolean): Int? {
        if (pageCount == 0) return null
        val group = pageGroups[currentPage] + if (forward) 1 else -1
        return groups.getOrNull(group)?.first()
    }

    fun nextPage() = navigatePages(true)
    fun previousPage() = navigatePages(false)
    /** Forward means next in document reading order; RTL changes physical motion, not page IDs. */
    fun navigatePages(forward: Boolean, animated: Boolean = true) {
        val target = groupTarget(forward) ?: return
        if (animated) turnToPage(target) else goToPage(target)
    }

    fun setRightToLeft(enabled: Boolean) {
        if (isRightToLeft == enabled) return
        val saved = position()
        stopAnimations()
        rightToLeft = enabled
        rebuildLayout()
        setPosition(saved.first, saved.second)
    }
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
        val annotationExecutor:ExecutorService=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"PDFCraft-annotation-cache").apply { isDaemon=true } }
        // Only this worker's serial executor accesses the document and its pages.
        var renderer: PdfRenderer? = null
        var descriptor: ParcelFileDescriptor? = null
        var prepared: PreparedPdf? = null
        var renderIdentity:String=""
        var sharePages:Boolean=false
        fun closeDocument() {
            try { renderer?.close() } catch (_: Exception) { }
            renderer = null
            try { descriptor?.close() } catch (_: Exception) { }
            descriptor = null
            prepared?.close()
            prepared = null
            renderIdentity=""
        }
    }
    private var worker: DocumentWorker? = null
    private var renderInFlight = false
    @Volatile private var pendingAnnotationSource:File?=null
    private var renderRevision=0
    private var preparingAnnotations=false
    var onAnnotationsPrepared:(()->Unit)?=null
    private val failedRenders = HashSet<RenderKey>()
    private data class RenderKey(val page: Int, val width: Int, val height: Int, val revision:Int=0)
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
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val meshPaper by lazy { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } }
    private val meshColumns=64
    private val meshRows=64
    private val meshVertices=FloatArray((meshColumns+1)*(meshRows+1)*2)
    private val meshFrontColors=IntArray((meshColumns+1)*(meshRows+1))
    private val meshBackColors=IntArray(meshFrontColors.size)
    private val meshVertex=FloatArray(5)
    private val backsideInkFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        .025f,.025f,.025f,0f,235f, .025f,.025f,.025f,0f,235f,
        .025f,.025f,.025f,0f,235f, 0f,0f,0f,1f,0f)))
    private var backsideSource:Bitmap?=null
    private var backsideImage:Bitmap?=null
    private var nightMeshSource:Bitmap?=null
    private var nightMeshImage:Bitmap?=null
    private val meshFrontPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun frontTexture(source:Bitmap):Bitmap {
        if(!darkPages) return source
        if(nightMeshSource===source) nightMeshImage?.let { return it }
        val scale=min(1f,1536f/max(source.width,source.height))
        val image=try { Bitmap.createBitmap(max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),Bitmap.Config.ARGB_8888) }
            catch(_:OutOfMemoryError) { nightMeshSource=source;nightMeshImage=source;return source }
        Canvas(image).drawBitmap(source,null,RectF(0f,0f,image.width.toFloat(),image.height.toFloat()),pagePaint)
        nightMeshSource=source;nightMeshImage=image
        return image
    }

    private fun backsideTexture(source:Bitmap?):Bitmap {
        if(source==null) return meshPaper
        if(backsideSource===source) backsideImage?.let { return it }
        val scale=min(1f,512f/max(source.width,source.height))
        val image=try { Bitmap.createBitmap(max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),Bitmap.Config.ARGB_8888) }
            catch(_:OutOfMemoryError) { backsideSource=source;backsideImage=meshPaper;return meshPaper }
        // Pre-tint once: older Canvas mesh implementations ignore color filters with vertex colors.
        Canvas(image).drawBitmap(source,null,RectF(0f,0f,image.width.toFloat(),image.height.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter=backsideInkFilter })
        backsideSource=source;backsideImage=image
        return image
    }
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
                if (consumeSelectionTap) { consumeSelectionTap = false; return true }
                if (gestureAxis == 1) return true
                performClick()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scaledThisGesture = true
                changeZoom(if (zoom > 1.1f) 1f else 2.5f, e.x, e.y)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (scaledThisGesture || scaleDetector.isInProgress || turning) return
                selectionGesture = true
                consumeSelectionTap = true
                stopAnimations()
                selectTextAt(e.x, e.y)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (selectionGesture) return true
                if (!scaleDetector.isInProgress && !scaledThisGesture) {
                    if (!vertical && zoom <= 1.01f) {
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
                if (selectionGesture) return false
                if (scaledThisGesture || scaleDetector.isInProgress) return false
                if (mode == ReadingMode.SINGLE && zoom <= 1.01f && gestureAxis != 2) {
                    if (turning) settleCurl(turnProgress() > .32f ||
                        (if (physicalForward(turnTo > turnFrom)) -vx else vx) > 650f * resources.displayMetrics.density)
                    return true
                }
                if (spread && zoom <= 1.01f && gestureAxis == 1) return true
                val verticalOnly = !vertical && zoom <= 1.01f
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
        sourceFile = file
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
                // Never parse a large image PDF with PDFBox before its first native page.
                val cachedPreparation=PdfAnnotationRendering.peek(file,context.cacheDir)
                val prepared = cachedPreparation ?: PreparedPdf(file,false)
                document.prepared = prepared
                if (generation.get() != token) { document.closeDocument();return@execute }
                pendingAnnotationSource=if(cachedPreparation==null) file else null
                val fd = ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY)
                document.descriptor = fd
                val pdf = PdfRenderer(fd)
                document.renderer = pdf
                document.renderIdentity="${prepared.file.canonicalPath}:${prepared.file.length()}:${prepared.file.lastModified()}"
                document.sharePages=!file.name.startsWith("unlocked-")
                val count = pdf.pageCount
                require(count > 0) { "Das PDF enthält keine Seiten." }
                val cachedSizes=PdfPageSizeCache.read(file,context.cacheDir)?.takeIf { it.first.size==count && it.second.size==count }
                val pw = cachedSizes?.first ?: FloatArray(count)
                val ph = cachedSizes?.second ?: FloatArray(count)
                var lastProgress = SystemClock.uptimeMillis()
                uiHandler.post { if (generation.get() == token) onLoadProgress?.invoke(0, count) }
                for (i in 0 until if(cachedSizes==null) count else 0) {
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
                if(cachedSizes==null) runCatching { PdfPageSizeCache.write(file,context.cacheDir,pw,ph) }
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
        backsideSource=null;backsideImage=null
        nightMeshSource=null;nightMeshImage=null
        pendingAnnotationSource=null
        preparingAnnotations=false
        renderRevision=0
        generation.incrementAndGet()
        clearTextSelection()
        sourceFile = null
        textCache.evictAll()
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
        localLefts = FloatArray(0)
        localTops = FloatArray(0)
        groups = emptyList()
        pageGroups = IntArray(0)
        focusedPage = 0
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
        worker?.annotationExecutor?.shutdownNow()
        worker = null
        super.onDetachedFromWindow()
    }

    fun goToPage(page: Int) {
        if (pageCount == 0) return
        clearTextSelection()
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
        if(mode!=ReadingMode.SINGLE) { backsideSource=null;backsideImage=null;nightMeshSource=null;nightMeshImage=null }
        stopAnimations()
        rebuildLayout()
        lastReported = null // Group labels can change even when the exact saved page does not.
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
        if(!enabled) { nightMeshSource=null;nightMeshImage=null }
        pagePaint.colorFilter = if (enabled) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))) else null
        invalidate()
    }

    fun resetZoom() = changeZoom(1f, width / 2f, height / 2f)

    val selectedTextRange: PdfTextSelection? get() = textSelection

    fun clearTextSelection() {
        selectionRequest++
        requestedSelectionPage = -1
        val previousPage = textSelectionPage
        val hadSelection = textSelection != null
        textSelection = null
        textSelectionPage = -1
        draggingHandle = 0
        if (hadSelection) onSelectionChanged?.invoke(previousPage, null)
        invalidate()
    }

    fun selectAllText(): Boolean {
        val page = textSelectionPage
        val text = textCache.get(page) ?: return false
        if (text.glyphs.isEmpty()) return false
        updateSelection(page, text, 0, text.glyphs.lastIndex)
        return true
    }

    /** Visible selection bounds in view-local coordinates, for a floating ActionMode. */
    fun selectionContentRect(): android.graphics.Rect {
        val union = RectF()
        val selection = textSelection ?: return android.graphics.Rect()
        if (textSelectionPage !in 0 until pageCount) return android.graphics.Rect()
        selection.boxes.forEach { box ->
            val visible = selectionBoxOnScreen(box)
            if (visible.intersect(0f, 0f, width.toFloat(), height.toFloat())) union.union(visible)
        }
        return android.graphics.Rect().also { union.roundOut(it) }
    }

    private fun selectionBoxOnScreen(box: RectF): RectF {
        val page = pageRect(textSelectionPage)
        return RectF(page.left + box.left * page.width(), page.top + box.top * page.height(),
            page.left + box.right * page.width(), page.top + box.bottom * page.height())
    }

    private fun selectTextAt(x: Float, y: Float) {
        if(!textSelectionAllowed) {
            android.widget.Toast.makeText(context,R.string.ui_extraction_denied,android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!documentReady || pageCount == 0) return
        // Hit-test both axes: a page gap or a centered sheet margin is not text.
        val coordinate = if (vertical) scrollYFloat + y else scrollXFloat + x
        val candidate = pageAt(coordinate, zoom)
        val page = (if (spread) groups[pageGroups[candidate]] else listOf(candidate))
            .firstOrNull { pageRect(it).contains(x, y) } ?: return
        val rect = pageRect(page)
        if (!rect.contains(x, y)) return
        clearTextSelection()
        requestedSelectionPage = page
        val nx = (x - rect.left) / rect.width()
        val ny = (y - rect.top) / rect.height()
        val request = selectionRequest
        val token = generation.get()
        fun apply(text: PdfPageText) {
            if (token != generation.get() || request != selectionRequest) return
            requestedSelectionPage = -1
            val index = text.closestGlyph(nx, ny)
            if (index == null) {
                android.widget.Toast.makeText(context, R.string.ui_pdf_select_no_text, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            var start = index
            var end = index
            fun word(i: Int) = text.glyphs[i].text.any { it.isLetterOrDigit() || it == '\'' || it == '’' }
            fun adjacent(a: Int, b: Int): Boolean {
                val first = text.glyphs[a]
                val second = text.glyphs[b]
                return first.line == second.line && word(a) && word(b) &&
                    second.bounds.left - first.bounds.right <= max(first.bounds.height(), second.bounds.height()) * .35f
            }
            if (word(index)) {
                while (start > 0 && adjacent(start - 1, start)) start--
                while (end < text.glyphs.lastIndex && adjacent(end, end + 1)) end++
            }
            updateSelection(page, text, start, end)
        }
        textCache.get(page)?.let { apply(it); return }
        val document = worker ?: return
        val source = sourceFile ?: return
        document.executor.execute {
            if (token != generation.get()) return@execute
            val extraction = runCatching {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                PdfPageTextExtractor.extract(source, page)
            }
            val text = extraction.getOrNull()
            uiHandler.post {
                if (token == generation.get() && request == selectionRequest) {
                    if (text != null) { textCache.put(page, text); apply(text) }
                    else {
                        requestedSelectionPage = -1
                        val message=extraction.exceptionOrNull()?.message?.let { UiText.t(context,it) } ?: context.getString(R.string.ui_pdf_select_no_text)
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateSelection(page: Int, text: PdfPageText, start: Int, end: Int) {
        val selected=text.selection(min(start,end),max(start,end))
        if(selected.text.isBlank()) { clearTextSelection();return }
        selectionStart = min(start, end)
        selectionEnd = max(start, end)
        textSelectionPage = page
        textSelection = selected
        if(spread) focusedPage=page
        invalidate()
        onSelectionChanged?.invoke(page, textSelection)
        if(spread) positionChanged()
    }

    /** Current handle centers in view-local coordinates; also useful for accessibility/tests. */
    fun selectionHandleCenters(): List<PointF> {
        val text = textCache.get(textSelectionPage) ?: return emptyList()
        if (textSelection == null) return emptyList()
        val start = selectionBoxOnScreen(text.glyphs[selectionStart].bounds)
        val end = selectionBoxOnScreen(text.glyphs[selectionEnd].bounds)
        return listOf(PointF(start.left, start.bottom + handleRadius), PointF(end.right, end.bottom + handleRadius))
    }

    private fun drawTextSelection(canvas: Canvas) {
        val selection = textSelection ?: return
        if (textSelectionPage !in 0 until pageCount || turning) return
        canvas.save()
        canvas.clipRect(pageRect(textSelectionPage))
        selection.boxes.forEach { canvas.drawRect(selectionBoxOnScreen(it), selectionPaint) }
        canvas.restore()
        selectionHandleCenters().forEach { canvas.drawCircle(it.x, it.y, handleRadius, handlePaint) }
    }

    /** First draw stays fast; annotation inspection runs independently and is cached. */
    private fun prepareAnnotationsAfterFirstPage(document:DocumentWorker,token:Int) {
        val source=pendingAnnotationSource
        pendingAnnotationSource=null
        if(source==null) { onAnnotationsPrepared?.invoke();return }
        preparingAnnotations=true
        invalidate()
        document.annotationExecutor.execute {
            val result=runCatching { PdfAnnotationRendering.prepare(source,context.cacheDir) }
            val prepared=result.getOrNull()
            if(token!=generation.get()) { prepared?.close();return@execute }
            if(prepared==null || prepared.file==source) {
                prepared?.close()
                uiHandler.post {
                    if(token==generation.get()) {
                        preparingAnnotations=false;invalidate();onAnnotationsPrepared?.invoke()
                        if(result.isFailure) android.widget.Toast.makeText(context,R.string.ui_annotation_prepare_failed,android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                return@execute
            }
            try {
                document.executor.execute switch@ {
                    if(token!=generation.get()) { prepared.close();return@switch }
                    var fd:ParcelFileDescriptor?=null
                    try {
                        fd=ParcelFileDescriptor.open(prepared.file,ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer=PdfRenderer(fd)
                        document.closeDocument()
                        document.descriptor=fd;document.renderer=renderer;document.prepared=prepared
                        document.renderIdentity="${prepared.file.canonicalPath}:${prepared.file.length()}:${prepared.file.lastModified()}"
                        uiHandler.post {
                            if(token==generation.get()) {
                                renderRevision++;failedRenders.clear();preparingAnnotations=false
                                invalidate();onAnnotationsPrepared?.invoke()
                            }
                        }
                    } catch(error:Exception) {
                        fd?.close();prepared.close()
                        uiHandler.post { if(token==generation.get()) { preparingAnnotations=false;invalidate();android.widget.Toast.makeText(context,R.string.ui_annotation_prepare_failed,android.widget.Toast.LENGTH_LONG).show() } }
                    }
                }
            } catch(_:java.util.concurrent.RejectedExecutionException) { prepared.close() }
        }
    }

    private fun drawPreparationStatus(canvas:Canvas) {
        if(!preparingAnnotations) return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ZenColors.of(darkPages).ink;textSize=12f*resources.displayMetrics.scaledDensity }
        val text=context.getString(R.string.ui_annotations_preparing)
        val pad=12f*resources.displayMetrics.density
        val boxWidth=paint.measureText(text)+pad*2
        val boxHeight=pad*3
        paint.color=ZenColors.of(darkPages).card
        canvas.drawRoundRect(width-boxWidth-pad,height-boxHeight-pad,width-pad,height-pad,pad,pad,paint)
        paint.color=ZenColors.of(darkPages).ink
        canvas.drawText(text,width-boxWidth,height-pad*2,paint)
    }

    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            draggingHandle = 0
            val handles = selectionHandleCenters()
            handles.forEachIndexed { index, point ->
                if (abs(point.x - event.x) <= handleRadius * 2.5f && abs(point.y - event.y) <= handleRadius * 2.5f)
                    draggingHandle = index + 1
            }
            if (draggingHandle != 0) {
                stopAnimations()
                selectionGesture = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            if (textSelection != null) {
                // Consume an outside tap without toggling the reader chrome. A drag may still pan.
                consumeSelectionTap = true
                clearTextSelection()
            } else consumeSelectionTap = false
        }
        if (draggingHandle == 0) return false
        if (event.pointerCount > 1) {
            draggingHandle = 0
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            val page = textSelectionPage
            val text = textCache.get(page)
            if (text != null && page in 0 until pageCount) {
                val rect = pageRect(page)
                // Stay on this page, including when the finger moves beyond its edge.
                val x = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
                val y = ((event.y - handleRadius - rect.top) / rect.height()).coerceIn(0f, 1f)
                val index = text.closestGlyph(x, y) ?: text.glyphs.indices.minByOrNull { i ->
                    val box = text.glyphs[i].bounds
                    val dx = max(box.left - x, max(0f, x - box.right))
                    val dy = max(box.top - y, max(0f, y - box.bottom))
                    dx * dx + dy * dy
                }
                if (index != null) {
                    if (draggingHandle == 1) updateSelection(page, text, min(index, selectionEnd), selectionEnd)
                    else updateSelection(page, text, selectionStart, max(index, selectionStart))
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            draggingHandle = 0
            selectionGesture = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback

    private fun changeZoom(value: Float, focusX: Float, focusY: Float) {
        stopAnimations()
        val next = finite(value, zoom).coerceIn(1f, 5f)
        val anchorPage = visiblePages.firstOrNull { pageRect(it).contains(focusX, focusY) } ?: pageAt(if (vertical)
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
        // getWidth() already reports the new viewport here; interpreting old scroll slots
        // with that width would jump to another spread during rotation/folding/fullscreen.
        val saved = lastReported?.let { it.first to it.second } ?: position()
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
        if (pendingPosition != null) restorePendingPosition()
        else setPosition(saved.first, saved.second)
        textSelection?.let { onSelectionChanged?.invoke(textSelectionPage,it) }
    }

    private fun rebuildLayout() {
        widths = FloatArray(pageCount)
        heights = FloatArray(pageCount)
        starts = FloatArray(pageCount + 1)
        localLefts = FloatArray(pageCount)
        localTops = FloatArray(pageCount)
        groups = if (spread) PdfSpreadLayout.groups(pageCount, mode == ReadingMode.BOOK)
            else (0 until pageCount).map { listOf(it) }
        pageGroups = IntArray(pageCount)
        groups.forEachIndexed { group, pages -> pages.forEach { pageGroups[it] = group } }
        maximumPageHeight = 0f
        if (width <= 0 || height <= 0) return
        if (spread) {
            groups.forEachIndexed { index, pages ->
                val slot = if (isRightToLeft) groups.lastIndex - index else index
                PdfSpreadLayout.sheets(pages, pageWidths, pageHeights, width.toFloat(), height.toFloat(), isRightToLeft).forEach { sheet ->
                    widths[sheet.page] = sheet.width
                    heights[sheet.page] = sheet.height
                    starts[sheet.page] = slot * width.toFloat()
                    localLefts[sheet.page] = sheet.left
                    localTops[sheet.page] = sheet.top
                    maximumPageHeight = max(maximumPageHeight, sheet.height)
                }
            }
            starts[pageCount] = groups.size * width.toFloat()
            updateContentSize()
            constrainScroll()
            return
        }
        for (i in 0 until pageCount) {
            val scale = if (fitPage && mode != ReadingMode.COMIC) min(width / pageWidths[i], height / pageHeights[i])
                else width / pageWidths[i]
            widths[i] = pageWidths[i] * scale
            heights[i] = pageHeights[i] * scale
            maximumPageHeight = max(maximumPageHeight, heights[i])
            starts[i + 1] = starts[i] + if (vertical)
                heights[i] + pageGap else max(width.toFloat(), widths[i])
        }
        if (!vertical && isRightToLeft) for (i in 0 until pageCount) starts[i] = (pageCount - 1 - i) * width.toFloat()
        updateContentSize()
        constrainScroll()
    }

    private fun updateContentSize() {
        if (vertical) {
            contentWidth = width * zoom
            // Leave enough trailing space to align even a short final page at the top.
            val lastStart = if (pageCount > 0) starts[pageCount - 1] * zoom else 0f
            val lastEnd = if (pageCount > 0) (starts.last() - pageGap) * zoom else 0f
            contentHeight = max(lastStart + height, lastEnd)
        } else {
            contentWidth = max(width.toFloat(), starts.last() * zoom)
            contentHeight = max(height.toFloat(), maximumPageHeight * zoom)
        }
    }

    private fun maxScrollX() = max(0f, contentWidth - width)
    private fun maxScrollY(): Float = when {
        pageCount == 0 -> 0f
        !vertical -> max(0f, (if (spread) height.toFloat() else heights[positionPage()]) * zoom - height)
        // Avoid rounding (lastStart + viewport) - viewport below lastStart.
        else -> max(starts[pageCount - 1] * zoom, contentHeight - height)
    }
    private fun constrainScroll() {
        scrollXFloat = scrollXFloat.coerceIn(0f, maxScrollX())
        scrollYFloat = scrollYFloat.coerceIn(0f, maxScrollY())
    }

    private fun pageAt(coordinate: Float, scale: Float = 1f): Int {
        if (pageCount == 0) return 0
        if (!vertical) {
            val slot = (coordinate / max(1f, width * scale)).toInt().coerceIn(0, groups.lastIndex)
            val group = if (isRightToLeft) groups.lastIndex - slot else slot
            return if (focusedPage in groups[group]) focusedPage else groups[group].first()
        }
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
        return pageAt(if (vertical)
            scrollYFloat else scrollXFloat + width / 2f, zoom)
    }

    private fun position(): Pair<Int, Float> {
        if (pageCount == 0) return 0 to 0f
        val page = positionPage()
        val origin = if (vertical) starts[page] else 0f
        return page to ((scrollYFloat - origin * zoom) / max(1f, heights[page] * zoom)).coerceIn(0f, 1f)
    }

    private fun setPosition(page: Int, offset: Float) {
        if (pageCount == 0 || width == 0 || height == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        focusedPage = p
        if (vertical) {
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
        if ((textSelectionPage >= 0 && textSelectionPage !in visiblePages) ||
            (requestedSelectionPage >= 0 && requestedSelectionPage !in visiblePages)) clearTextSelection()
        val state = Triple(p.first, p.second, zoom)
        if (state != lastReported) {
            lastReported = state
            textSelection?.let { onSelectionChanged?.invoke(textSelectionPage,it) }
            onPositionChanged?.invoke(state.first, state.second, state.third)
        }
    }

    private fun pageRect(page: Int): RectF {
        val w = widths[page] * zoom
        val h = heights[page] * zoom
        val left = if (vertical) (contentWidth - w) / 2f
            else starts[page] * zoom + if (spread) localLefts[page] * zoom else (width * zoom - w) / 2f
        val top = if (vertical) starts[page] * zoom
            else if (spread) localTops[page] * zoom else max(0f, (height - h) / 2f)
        return RectF(left - scrollXFloat, top - scrollYFloat,
            left + w - scrollXFloat, top + h - scrollYFloat)
    }

    private fun renderKey(page: Int): RenderKey {
        // Quantized zoom avoids rendering every intermediate pinch frame.
        val level = ceil(zoom * 2f) / 2f
        var w = max(1f, widths[page] * level)
        var h = max(1f, heights[page] * level)
        val coordinate = if (vertical) scrollYFloat else scrollXFloat
        val extent = if (vertical) height else width
        val visibleCount = if (spread) 2 else max(1, abs(pageAt(coordinate + extent, zoom) - pageAt(coordinate, zoom)) + 1)
        // Three fit-zoom sheets fit inside the cache, including both preloaded neighbors.
        val pixelBudget = if (spread) 2_000_000.0 else if (mode == ReadingMode.SINGLE && zoom <= 1.01f) 2_000_000.0
            else min(4_000_000.0, 6_000_000.0 / visibleCount)
        val reduction = min(1.0, kotlin.math.sqrt(pixelBudget / (w.toDouble() * h)))
        w *= reduction.toFloat()
        h *= reduction.toFloat()
        return RenderKey(page, max(1, w.toInt()), max(1, h.toInt()),renderRevision)
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
            drawTextSelection(canvas)
            drawPreparationStatus(canvas)
            return
        }
        val start = if (vertical) scrollYFloat else scrollXFloat
        val extent = if (vertical) height else width
        val endpoints = listOf(pageAt(start, zoom), pageAt(start + extent - .01f, zoom))
        val candidates = if (spread) endpoints.flatMap { groups[pageGroups[it]] }.distinct()
            else (endpoints.min()..endpoints.max()).toList()
        var request: RenderKey? = null
        var requestDistance = Int.MAX_VALUE
        val snapshots = cache.snapshot()
        for (page in candidates) {
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
        drawTextSelection(canvas)
        drawPreparationStatus(canvas)
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
            val patch = polygonPath(geometry.clip(polygon, true))
            val nx = geometry.nx
            val ny = geometry.ny
            val mx = geometry.mx
            val my = geometry.my
            val mesh = PdfCurvedMesh(geometry)
            val curvature = mesh.radius * 2f
            // Shadow cast by the lifted sheet onto the exposed underlying page.
            canvas.save()
            canvas.clipRect(rect)
            canvas.clipPath(patch)
            shadowPaint.shader = LinearGradient(mx, my, mx + nx * curvature, my + ny * curvature,
                Color.argb(100, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            shadowPaint.alpha = 255
            canvas.drawRect(rect, shadowPaint)
            canvas.restore()
            // The UV grid bends around an oblique cylinder with actual non-affine vertices.
            // Canvas uses its hardware bitmap-mesh path when this View is hardware accelerated.
            // No GL surface, layer-wide rotation, or reflected flat-page substitute is involved.
            val columns = meshColumns
            val rows = meshRows
            val vertices = meshVertices
            val frontColors = meshFrontColors
            val backColors = meshBackColors
            for (row in 0..rows) for (column in 0..columns) {
                val index = row * (columns + 1) + column
                mesh.projectInto(rect.left + rect.width() * column / columns,
                    rect.top + rect.height() * row / rows,meshVertex)
                vertices[index * 2] = meshVertex[0]
                vertices[index * 2 + 1] = meshVertex[1]
                val shade = (255 * meshVertex[4]).toInt()
                frontColors[index] = Color.argb(if (meshVertex[3]>0f) 0 else 255, shade, shade, shade)
                val base = if (darkPages) 65 else 239
                val red = (base * meshVertex[4]).toInt()
                backColors[index] = Color.argb(if (meshVertex[3]>0f) 255 else 0, red,
                    (red * .97f).toInt(), (red * .90f).toInt())
            }
            canvas.drawBitmapMesh(frontTexture(bitmapFor(first) ?: meshPaper), columns, rows, vertices, 0,
                frontColors, 0, meshFrontPaint)
            backPaint.colorFilter = null
            backPaint.alpha = 255
            canvas.drawBitmapMesh(backsideTexture(bitmapFor(first)), columns, rows, vertices, 0, backColors, 0, backPaint)
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
            var sharedBitmap=false
            try {
                if (token == generation.get()) {
                    val pdf = document.renderer
                    if (pdf != null) {
                        val sharedKey="${document.renderIdentity}:${key.page}:${key.width}:${key.height}"
                        bitmap=if(document.sharePages) PdfRenderedPageMemoryCache.get(sharedKey) else null
                        if(bitmap!=null) sharedBitmap=true
                        else {
                        pdf.openPage(key.page).use { page ->
                            val image = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                            bitmap = image
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        if(document.sharePages) bitmap?.let { PdfRenderedPageMemoryCache.put(sharedKey,it);sharedBitmap=true }
                        }
                    }
                }
            } catch (failure: Exception) { error = failure }
            catch (failure: OutOfMemoryError) { error = failure }
            val result = bitmap
            val failed = error != null
            uiHandler.post {
                if (token != generation.get()) {
                    if(!sharedBitmap) result?.recycle()
                } else {
                    renderInFlight = false
                    if (!failed && result != null) {
                        // Keep one sharp version per page, not every previous zoom level.
                        cache.snapshot().keys.filter { it.page == key.page && it != key }.forEach { cache.remove(it) }
                        cache.put(key, result)
                        if (!firstPageRendered && documentReady && key.page == currentPage) {
                            firstPageRendered = true
                            onFirstPageRendered?.invoke()
                            if(token==generation.get()) prepareAnnotationsAfterFirstPage(document,token)
                        }
                    }
                    else {
                        if(!sharedBitmap) result?.recycle()
                        failedRenders.add(key)
                        onError?.invoke("Diese PDF-Seite konnte nicht dargestellt werden. Möglicherweise ist sie beschädigt oder zu groß.")
                    }
                    invalidate()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleSelectionTouch(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            selectionGesture = false
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
             !selectionGesture && !scaledThisGesture && !vertical && zoom <= 1.01f && pageCount > 0) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (gestureAxis == 0 && max(abs(dx), abs(dy)) > 3f * resources.displayMetrics.density) {
                gestureAxis = if (abs(dx) > abs(dy)) 1 else 2
                if (gestureAxis == 1) {
                    val target = groupTarget((dx < 0f) != isRightToLeft)
                    if (target != null && !spread) beginCurl(target, downY)
                }
            }
            if (gestureAxis == 1 && turning && curlAnimator == null) {
                val rect = sheetRect(turnFrom)
                val direction = if (physicalForward(turnTo > turnFrom)) 1f else -1f
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
            if (spread && zoom <= 1.01f && gestureAxis == 1 && !scaledThisGesture &&
                !selectionGesture && event.actionMasked == MotionEvent.ACTION_UP && abs(event.x - downX) > width * .15f) {
                navigatePages((event.x < downX) != isRightToLeft, false)
            }
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
            if (groupTarget(false) != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (groupTarget(true) != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val forward = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> true
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> false
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (groupTarget(forward) == null) return false
        navigatePages(forward)
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
        clearTextSelection()
        stopAnimations()
        turnFrom = currentPage
        turnTo = destination
        val rect = sheetRect(turnFrom)
        // Use a viewport corner on a vertically scrolled tall sheet, not an offscreen corner.
        curlCorner.set(if (physicalForward(destination > turnFrom)) rect.right else rect.left,
            if (touchY < height / 2f) max(0f, rect.top) else min(height.toFloat(), rect.bottom))
        curlFinger.set(curlCorner.x + if (physicalForward(destination > turnFrom)) -.5f else .5f,
            curlCorner.y + if (touchY < height / 2f) .5f else -.5f)
        turning = true
        invalidate()
    }

    private fun settleCurl(complete: Boolean) {
        if (!turning || curlAnimator != null) return
        val rect = sheetRect(turnFrom)
        val sx = curlFinger.x
        val sy = curlFinger.y
        val direction = if (physicalForward(turnTo > turnFrom)) 1f else -1f
        // Carry the complete curved band beyond the far edge, not just its central crease.
        val ex = if (complete) curlCorner.x - direction * (rect.width() * 2f + gap + 90f * Math.PI.toFloat()) else curlCorner.x
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
