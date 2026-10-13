package de.pdfcraft.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InterruptedIOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Each job owns its PDFBox document; never shares a renderer's file descriptor. */
class PdfTextService(context: Context) {
    private val temporaryDirectory = context.applicationContext.cacheDir
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PDFCraft-text").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val searchGeneration = AtomicInteger()
    private val extractGeneration = AtomicInteger()
    private val outlineGeneration = AtomicInteger()
    private val lock = Any()
    @Volatile private var closed = false
    private data class PageKey(val path: String, val size: Long, val modified: Long, val page: Int)
    private val cache = object : LinkedHashMap<PageKey, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, String>?): Boolean = size > 8
    }

    fun extract(file: File, page: Int, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val generation = extractGeneration.incrementAndGet()
        val active = { !closed && generation == extractGeneration.get() }
        submit(active, onError) {
            val key = PageKey(file.absolutePath, file.length(), file.lastModified(), page)
            val cached = synchronized(lock) { cache[key] }
            val text = cached ?: open(file).use { document ->
                checkExtractionPermission(document)
                require(page in 0 until document.numberOfPages) { "This page is not available." }
                extractPage(document, page, active).also { value ->
                    synchronized(lock) { if (active()) cache[key] = value }
                }
            }
            deliver(active) { onResult(text) }
        }
    }

    fun search(file: File, query: String, onResult: (List<SearchHit>) -> Unit, onError: (String) -> Unit) {
        val generation = searchGeneration.incrementAndGet()
        val active = { !closed && generation == searchGeneration.get() }
        submit(active, onError) {
            val hits = ArrayList<SearchHit>()
            if (query.isNotBlank()) open(file).use { document ->
                checkExtractionPermission(document)
                for (page in 0 until document.numberOfPages) {
                    if (!active()) return@submit
                    // Search streams pages and deliberately does not populate the reflow cache.
                    val text = extractPage(document, page, active)
                    TextSearch.snippets(text, query, 200 - hits.size).forEach {
                        hits += SearchHit(page, it)
                    }
                    if (hits.size >= 200) break
                }
            }
            deliver(active) { onResult(hits) }
        }
    }

    fun outline(file: File, onResult: (List<OutlineEntry>) -> Unit, onError: (String) -> Unit) {
        val generation = outlineGeneration.incrementAndGet()
        val active = { !closed && generation == outlineGeneration.get() }
        submit(active, onError) {
            val entries = ArrayList<OutlineEntry>()
            open(file).use { document ->
                val stack = java.util.ArrayDeque<Pair<PDOutlineItem, Int>>()
                document.documentCatalog.documentOutline?.firstChild?.let { stack.push(it to 0) }
                val visited = Collections.newSetFromMap(IdentityHashMap<PDOutlineItem, Boolean>())
                var inspected = 0
                while (stack.isNotEmpty() && inspected++ < 10000) {
                    if (!active()) return@submit
                    val (item, depth) = stack.pop()
                    if (!visited.add(item)) continue
                    val destination = try { item.findDestinationPage(document) } catch (_: Exception) { null }
                    val page = destination?.let { document.pages.indexOf(it) } ?: -1
                    if (page >= 0) entries += OutlineEntry(item.title.orEmpty().ifBlank { "Untitled" }, page, depth)
                    item.nextSibling?.let { stack.push(it to depth) }
                    if (depth < 64) item.firstChild?.let { stack.push(it to depth + 1) }
                }
            }
            deliver(active) { onResult(entries) }
        }
    }

    private fun open(file: File): PDDocument = PDDocument.load(
        file, MemoryUsageSetting.setupTempFileOnly().setTempDir(temporaryDirectory)
    )

    private fun checkExtractionPermission(document: PDDocument) {
        if (!document.currentAccessPermission.canExtractContent()) {
            throw SecurityException("This PDF does not permit text extraction.")
        }
    }

    private fun extractPage(document: PDDocument, page: Int, active: () -> Boolean): String {
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String) {
                if (!active() || Thread.currentThread().isInterrupted) throw InterruptedIOException()
                super.writeString(text)
            }
        }
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.sortByPosition = true
        return TextSearch.normalizeExtractedText(stripper.getText(document))
    }

    private fun submit(active: () -> Boolean, onError: (String) -> Unit, job: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            try {
                worker.execute {
                    if (!active()) return@execute
                    try { job() } catch (_: InterruptedIOException) {
                        // Superseded requests and close are silent.
                    } catch (_: InvalidPasswordException) {
                        deliver(active) { onError("This PDF requires a password to read its text.") }
                    } catch (failure: Exception) {
                        deliver(active) { onError(failure.message ?: "Unable to read PDF text.") }
                    }
                }
            } catch (_: RejectedExecutionException) { }
        }
    }

    private fun deliver(active: () -> Boolean, callback: () -> Unit) {
        if (active()) handler.post { if (active()) callback() }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            worker.shutdownNow()
            cache.clear()
            handler.removeCallbacksAndMessages(null)
        }
    }
}
