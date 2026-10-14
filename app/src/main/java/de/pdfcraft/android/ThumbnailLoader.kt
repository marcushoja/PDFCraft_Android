package de.pdfcraft.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bind on UI; cancel recycled cells. All native operations run on one worker. */
class ThumbnailLoader internal constructor(context: Context, private val worker: ExecutorService) : AutoCloseable {
    constructor(context: Context) : this(context, Executors.newSingleThreadExecutor())
    private val cacheDirectory = context.applicationContext.cacheDir
    private val directory = File(cacheDirectory, "thumbs-native-v3")
    private val handler = Handler(Looper.getMainLooper())
    private val guard = Any()
    private var closed = false
    private var draining = false
    private var sequence = 0L
    private val stopped = CountDownLatch(1)
    private val targets = WeakHashMap<ImageView, Request>()
    private val completed = WeakHashMap<ImageView, Binding>()
    private val jobs = LinkedHashMap<String, Job>()
    private val sessions = LinkedHashMap<String, Session>(4, .75f, true)
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var opened = 0L
    private var rendered = 0L
    private var cancelled = 0L
    private var hits = 0L
    private var nextTrim = 0L
    private var bytesSinceTrim = 0L

    private class Request(val target: WeakReference<ImageView>) { var job: Job? = null }
    // Weak drawable identity detects externally replaced images without retaining displayed content.
    private class Binding(val key: String, val content: String, val path: String,
                          val drawable: WeakReference<Drawable>)
    private class Job(val file: File, val source: String, val page: Int, val width: Int,
                      val key: String, var priority: Int, var order: Long) {
        val requests = mutableListOf<Request>()
        var running = false
    }
    private class Session(val renderer: PdfRenderer, val descriptor: ParcelFileDescriptor,
                          val prepared: PreparedPdf?, val path: String) : AutoCloseable {
        override fun close() {
            try { renderer.close() } finally {
                try { descriptor.close() } finally { prepared?.close() }
            }
        }
    }
    data class Stats(val rendererOpens: Long, val renderedPages: Long, val cacheHits: Long,
                     val cancelledRequests: Long, val outstandingRequests: Int, val queuedJobs: Int)
    fun stats(): Stats = synchronized(guard) {
        Stats(opened, rendered, hits, cancelled, targets.size, jobs.values.count { !it.running })
    }

    /** Higher priority first, then newest bind first. Width buckets avoid resize cache churn. */
    @JvmOverloads
    fun load(file: File, page: Int, width: Int, target: ImageView, priority: Int = 0) {
        val source = try { PdfDocumentRenderCache.identity(file, false) } catch (_: Exception) { return }
        val pixels = ((width.coerceIn(32, 512) + 31) / 32) * 32
        val content = "$source\n$page"
        val key = "$content\n$pixels"
        synchronized(guard) {
            if (closed || page < 0) return
            targets[target]?.job?.let { pending ->
                if (pending.key == key) {
                    pending.priority = maxOf(pending.priority, priority)
                    return // Includes completed work whose UI delivery is still pending.
                }
            }
            val binding = completed[target]
            val displayed = target.drawable
            if (binding?.key == key && displayed != null && binding.drawable.get() === displayed) {
                // Immutable source binding: cancel/rebind explicitly to discover prepared revisions.
                return
            }
            val keepImage = displayed != null &&
                (binding?.content == content || targets[target]?.job?.let {
                    "${it.source}\n${it.page}" == content
                } == true)
            cancelLocked(target)
            if (!keepImage && displayed != null) target.setImageDrawable(null)
            val request = Request(WeakReference(target))
            val job = jobs.getOrPut(key) { Job(file, source, page, pixels, key, priority, ++sequence) }
            job.priority = maxOf(job.priority, priority)
            job.order = ++sequence
            request.job = job
            job.requests.add(request)
            targets[target] = request
            // Virtualized grids normally have 10–20 requests; bound accidental eager callers too.
            while (jobs.values.count { !it.running } > 64) {
                val oldest = jobs.values.filter { !it.running }.minWithOrNull(
                    compareBy<Job> { it.priority }.thenBy { it.order }) ?: break
                abandonLocked(oldest)
            }
            if (!draining) {
                draining = true
                worker.execute { drain() }
            }
        }
    }
    fun cancel(target: ImageView) = synchronized(guard) { cancelLocked(target) }
    private fun cancelLocked(target: ImageView) {
        completed.remove(target)
        val request = targets.remove(target) ?: return
        cancelled++
        request.job?.let { job ->
            job.requests.remove(request)
            if (!job.running && job.requests.isEmpty()) jobs.remove(job.key)
        }
    }
    private fun abandonLocked(job: Job) {
        job.requests.toList().forEach { request ->
            request.target.get()?.let { if (targets[it] === request) targets.remove(it) }
            cancelled++
        }
        job.requests.clear()
        jobs.remove(job.key)
    }
    fun cancelAll() = synchronized(guard) {
        jobs.values.toList().forEach { abandonLocked(it) }
        targets.clear()
        completed.clear()
    }
    /** Cancels a source and closes its renderer leases on the native worker. */
    fun releaseDocument(file: File) {
        val path = try { file.canonicalPath } catch (_: Exception) { return }
        synchronized(guard) {
            if (closed) return
            completed.entries.removeAll { it.value.path == path }
            targets.entries.filter { it.value.job?.file?.canonicalPath == path }
                .map { it.key }.forEach { cancelLocked(it) }
            jobs.values.filter { it.file.canonicalPath == path }.toList().forEach { abandonLocked(it) }
            worker.execute {
                if(PdfDocumentRenderCache.isPrivateUnlocked(file)) memory.evictAll()
                val iterator = sessions.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.path == path) { entry.value.close(); iterator.remove() }
                }
            }
        }
    }
    private fun active(job: Job): Boolean = synchronized(guard) {
        job.requests.removeAll { it.target.get() == null }
        !closed && jobs[job.key] === job && job.requests.isNotEmpty()
    }
    private fun drain() {
        while (true) {
            val job = synchronized(guard) {
                val next = if (closed) null else jobs.values.filter { !it.running }
                    .maxWithOrNull(compareBy<Job> { it.priority }.thenBy { it.order })
                if (next == null) { draining = false; return }
                next.running = true
                next
            }
            val bitmap = try { if (active(job)) obtain(job) else null }
                catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            synchronized(guard) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                val requests = job.requests.toList()
                job.requests.clear()
                // Posted callbacks also retain only weak target references.
                if (requests.isNotEmpty()) handler.post {
                    synchronized(guard) {
                        for (request in requests) {
                            val target = request.target.get() ?: continue
                            if (targets[target] !== request) continue
                            targets.remove(target)
                            if (!closed && bitmap != null) {
                                target.setImageBitmap(bitmap)
                                val drawable = target.drawable
                                if (drawable != null) completed[target] = Binding(job.key,
                                    "${job.source}\n${job.page}", job.file.canonicalPath, WeakReference(drawable))
                            }
                        }
                    }
                }
            }
        }
    }
    private fun obtain(job: Job): Bitmap? {
        if (!active(job) || PdfDocumentRenderCache.identity(job.file, false) != job.source) return null
        // Peek only: never parse/flatten a PDF on the critical thumbnail path.
        var prepared = PdfDocumentRenderCache.peek(job.file, cacheDirectory, false)
        try {
            val input = prepared?.file ?: job.file
            // A checked annotation-free PDF is still the same original file. Do not reopen
            // its renderer or invalidate all thumbnails merely because inspection finished.
            val variant = if (input.canonicalPath==job.file.canonicalPath) "raw"
                else "prepared:${input.canonicalPath}:${input.length()}:${input.lastModified()}"
            val key = PdfDocumentRenderCache.digest("native-color-v3\n${job.key}\n$variant")
            memory.get(key)?.let { synchronized(guard) { hits++ }; return it }
            val persistent = !PdfDocumentRenderCache.isPrivateUnlocked(job.file)
            if (persistent) readDisk(key)?.let {
                memory.put(key, it)
                synchronized(guard) { hits++ }
                return it
            }
            if (!active(job)) return null
            val sessionKey = "${job.source}\n$variant"
            val session = sessions[sessionKey] ?: run {
                // A newly published annotation revision supersedes the raw session immediately.
                val stale = sessions.iterator()
                while (stale.hasNext()) {
                    val entry = stale.next()
                    if (entry.value.path == job.file.canonicalPath) {
                        entry.value.close()
                        stale.remove()
                    }
                }
                // Evict before opening: never allocate a third native document.
                if (sessions.size >= 2) {
                    val oldest = sessions.entries.iterator()
                    oldest.next().value.close()
                    oldest.remove()
                }
                val descriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
                    descriptor.close(); throw error
                }
                Session(renderer, descriptor, prepared, job.file.canonicalPath).also {
                    prepared = null // Session now owns the cached-PDF lease.
                    sessions[sessionKey] = it
                    synchronized(guard) { opened++ }
                }
            }
            if (!active(job) || job.page !in 0 until session.renderer.pageCount) return null
            val bitmap = session.renderer.openPage(job.page).use { page ->
                val ratio = page.height.toDouble() / page.width.coerceAtLeast(1)
                var w = job.width.toDouble()
                var h = w * ratio
                val scale = minOf(1.0, 1024.0 / h.coerceAtLeast(1.0), sqrt(512.0 * 1024 / (w * h).coerceAtLeast(1.0)))
                w *= scale; h *= scale
                val result = Bitmap.createBitmap(w.roundToInt().coerceIn(1, 512), h.roundToInt().coerceIn(1, 1024), Bitmap.Config.ARGB_8888)
                try {
                    result.eraseColor(Color.WHITE)
                    page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result
                } catch (failure: Throwable) { result.recycle(); throw failure }
            }
            synchronized(guard) { rendered++ }
            if (!active(job)) return null
            memory.put(key, bitmap)
            if (persistent) writeDisk(key, bitmap)
            return bitmap
        } finally { prepared?.close() }
    }
    private fun readDisk(key: String): Bitmap? = try {
        val file = File(directory, "$key.jpg")
        if (!file.isFile) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..1024) {
                file.delete(); null
            } else BitmapFactory.decodeFile(file.path)?.also { file.setLastModified(System.currentTimeMillis()) }
        }
    } catch (_: Exception) { null }
    private fun writeDisk(key: String, bitmap: Bitmap) {
        var pending: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            pending = File.createTempFile("thumb-", ".tmp", directory)
            if (FileOutputStream(pending).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }) {
                bytesSinceTrim += pending.length()
                pending.renameTo(File(directory, "$key.jpg"))
            }
            val now = android.os.SystemClock.uptimeMillis()
            // Scan/sort once per batch, not per image; leave growth headroom below 64 MB.
            if (now >= nextTrim || bytesSinceTrim >= 4L * 1024 * 1024) {
                val files = directory.listFiles()?.filter { it.extension == "jpg" }.orEmpty()
                var bytes = files.sumOf { it.length() }
                if (bytes > 60L * 1024 * 1024) for (file in files.sortedBy { it.lastModified() }) {
                    if (bytes <= 56L * 1024 * 1024) break
                    val size = file.length()
                    if (file.delete()) bytes -= size
                }
                nextTrim = now + 30_000
                bytesSinceTrim = 0
            }
        } catch (_: Exception) { /* Disk caching is optional. */ }
        finally { pending?.delete() }
    }
    /** Nonblocking on UI; native cleanup is ordered after any in-flight page render. */
    override fun close() {
        synchronized(guard) {
            if (closed) return
            closed = true
            cancelAll()
            handler.removeCallbacksAndMessages(null)
            worker.execute {
                try {
                    sessions.values.forEach { session -> runCatching { session.close() } }
                    sessions.clear()
                    memory.evictAll() // Never recycle displayed bitmaps.
                } finally { stopped.countDown() }
            }
            worker.shutdown()
        }
    }
    /** Diagnostics/tests only; never wait on UI. */
    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)
    internal fun awaitIdle(timeout: Long, unit: TimeUnit): Boolean {
        val idle = CountDownLatch(1)
        synchronized(guard) { if (closed) return false; worker.execute { idle.countDown() } }
        return idle.await(timeout, unit)
    }
}
