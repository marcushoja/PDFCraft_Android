package de.pdfcraft.android

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Private viewing-only cache. Never use its PDFs for export, editing, search or sharing.
 * Sources are immutable library files: identity is canonical path + length + modification time.
 * Same-size/same-mtime replacements cannot be detected without reading the entire source.
 */
internal object PdfDocumentRenderCache {
    private const val VERSION = 1
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val guard = Any()
    private class KeyLock { val monitor = Any(); var users = 0 }
    private val locks = mutableMapOf<String, KeyLock>()
    private val leases = mutableMapOf<String, Int>()

    internal fun identity(source: File, printing: Boolean): String =
        "$VERSION\n${source.canonicalPath}\n${source.length()}\n${source.lastModified()}\n$printing"

    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    internal fun isPrivateUnlocked(source: File): Boolean =
        source.name.startsWith("unlocked-", ignoreCase = true) ||
            source.canonicalFile.name.startsWith("unlocked-", ignoreCase = true)

    fun peek(source: File, cacheDir: File, printing: Boolean): PreparedPdf? {
        return try {
            if (isPrivateUnlocked(source)) return null
            val root = File(cacheDir, "pdf-render-v$VERSION").canonicalFile
            val key = digest(identity(source, printing))
            synchronized(guard) { lookup(root, key, source) }
        } catch (_: IOException) { null }
    }

    fun prepare(source: File, cacheDir: File, printing: Boolean, build: () -> PreparedPdf): PreparedPdf {
        // Do not persist either the checked status or a flattened copy of decrypted documents.
        if (isPrivateUnlocked(source)) return build()
        val root = File(cacheDir, "pdf-render-v$VERSION").canonicalFile
        val stamp = identity(source, printing)
        val key = digest(stamp)
        val lockId = "${root.canonicalPath}/$key"
        val lock = synchronized(guard) {
            locks.getOrPut(lockId) { KeyLock() }.also { it.users++ }
        }
        try {
            return synchronized(lock.monitor) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF preparation cancelled")
                synchronized(guard) { lookup(root, key, source) }?.let { return@synchronized it }
                // No global monitor is held while PDFBox parses/scans/saves a large document.
                val result = build()
                if (Thread.currentThread().isInterrupted) {
                    result.close()
                    throw InterruptedIOException("PDF preparation cancelled")
                }
                if (identity(source, printing) != stamp) return@synchronized result
                try {
                    // Flush data before publishing metadata, without blocking unrelated cache hits.
                    if (result.temporary) FileOutputStream(result.file, true).use { it.fd.sync() }
                    synchronized(guard) { publish(root, key, result, source) }
                } catch (_: IOException) {
                    // Cache availability must not make an otherwise renderable PDF fail.
                    result
                }
            }
        } finally {
            synchronized(guard) {
                if (--lock.users == 0) locks.remove(lockId)
            }
        }
    }

    private fun lookup(root: File, key: String, source: File): PreparedPdf? {
        val metadata = File(root, "$key.json")
        if (!metadata.isFile || metadata.length() > 4096) return null
        return try {
            val json = JSONObject(metadata.readText())
            if (json.getInt("version") != VERSION) return null
            val name = json.getString("rendered")
            if (name.isEmpty()) {
                metadata.setLastModified(System.currentTimeMillis())
                PreparedPdf(source, false)
            } else {
                // Metadata is untrusted cache input; never follow paths outside this directory.
                if (!name.startsWith("$key-") || !name.endsWith(".pdf") || File(name).name != name) return null
                val file = File(root, name)
                if (!file.isFile || file.length() != json.getLong("bytes") || !hasPdfEnvelope(file)) return null
                metadata.setLastModified(System.currentTimeMillis())
                lease(file)
            }
        } catch (_: Exception) { null }
    }

    private fun hasPdfEnvelope(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 12) return@use false
        val header = ByteArray(5)
        input.readFully(header)
        if (header.toString(Charsets.US_ASCII) != "%PDF-") return@use false
        val tail = ByteArray(minOf(input.length(), 1024L).toInt())
        input.seek(input.length() - tail.size)
        input.readFully(tail)
        tail.toString(Charsets.US_ASCII).contains("%%EOF")
    }

    private fun lease(file: File, temporary: Boolean = false): PreparedPdf {
        val path = file.absolutePath
        leases[path] = (leases[path] ?: 0) + 1
        return PreparedPdf(file, temporary) {
            synchronized(guard) {
                val count = (leases[path] ?: 1) - 1
                if (count == 0) leases.remove(path) else leases[path] = count
            }
        }
    }

    private fun publish(root: File, key: String, result: PreparedPdf, source: File): PreparedPdf {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create render cache")
        // Unique filenames let a corrupt/stale entry be replaced without touching live readers.
        var rendered: File? = null
        var pending: File? = null
        try {
            if (result.temporary) {
                rendered = File.createTempFile("$key-", ".pdf", root)
                if (!result.file.renameTo(rendered)) throw IOException("Cannot publish rendered PDF")
            } else if (result.file != source) {
                return result
            }
            val json = JSONObject().put("version", VERSION)
                .put("rendered", rendered?.name ?: "").put("bytes", rendered?.length() ?: 0)
            pending = File.createTempFile("$key-", ".pending", root)
            FileOutputStream(pending).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!pending.renameTo(File(root, "$key.json"))) throw IOException("Cannot publish render metadata")
            val prepared = rendered?.let { lease(it) } ?: PreparedPdf(source, false)
            result.close()
            // Lease before trimming. Active entries may temporarily exceed the disk budget.
            trim(root)
            return prepared
        } catch (error: IOException) {
            // If moving succeeded but metadata failed, return a still-owned unique temp file.
            if (rendered != null && rendered.isFile && !result.file.exists()) {
                // It lives in the cache directory, so it still needs trim protection.
                return lease(rendered, temporary = true)
            }
            rendered?.delete()
            throw error
        } finally {
            pending?.delete()
        }
    }

    private fun trim(root: File) {
        val files = root.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        val now = System.currentTimeMillis()
        // Group a PDF with its metadata; touch only the compact metadata on cache hits.
        val groups = files.groupBy { it.name.take(64) }.values.sortedBy { group ->
            group.firstOrNull { it.extension == "json" }?.lastModified() ?: group.maxOf { it.lastModified() }
        }
        for (group in groups) {
            if (group.any { leases.containsKey(it.absolutePath) }) continue
            val used = group.firstOrNull { it.extension == "json" }?.lastModified()
                ?: group.maxOf { it.lastModified() }
            if (total <= MAX_BYTES && now - used <= MAX_AGE_MS) continue
            for (file in group) {
                val bytes = file.length()
                if (file.delete()) total -= bytes
            }
        }
    }
}

/** Compact dimensions only, in native PdfRenderer coordinates; never stores document content. */
object PdfPageSizeCache {
    private val guard = Any()
    private const val MAX_PAGES = 100_000
    private const val MAX_BYTES = 16L * 1024 * 1024

    private fun key(source: File): String = PdfDocumentRenderCache.digest(
        "page-sizes-v1\n" + PdfDocumentRenderCache.identity(source, false))

    fun read(source: File, cacheDir: File): Pair<FloatArray, FloatArray>? {
        if (PdfDocumentRenderCache.isPrivateUnlocked(source)) return null
        return synchronized(guard) {
            try {
                val file = File(File(cacheDir, "pdf-page-sizes-v1"), "${key(source)}.json")
                if (!file.isFile || file.length() > MAX_BYTES) return@synchronized null
                val json = JSONObject(file.readText())
                val widths = json.getJSONArray("widths")
                val heights = json.getJSONArray("heights")
                if (widths.length() !in 1..MAX_PAGES || widths.length() != heights.length()) return@synchronized null
                val w = FloatArray(widths.length()) { widths.getDouble(it).toFloat() }
                val h = FloatArray(heights.length()) { heights.getDouble(it).toFloat() }
                if (!valid(w, h)) return@synchronized null
                file.setLastModified(System.currentTimeMillis())
                w to h
            } catch (_: Exception) { null }
        }
    }

    fun write(source: File, cacheDir: File, widths: FloatArray, heights: FloatArray) {
        if (PdfDocumentRenderCache.isPrivateUnlocked(source) || !valid(widths, heights)) return
        synchronized(guard) {
            var pending: File? = null
            try {
                val root = File(cacheDir, "pdf-page-sizes-v1")
                if (!root.isDirectory && !root.mkdirs()) return
                val target = File(root, "${key(source)}.json")
                val w = JSONArray()
                val h = JSONArray()
                widths.forEach { w.put(it.toDouble()) }
                heights.forEach { h.put(it.toDouble()) }
                val bytes = JSONObject().put("widths", w).put("heights", h)
                    .toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) return
                pending = File.createTempFile("dimensions-", ".pending", root)
                FileOutputStream(pending).use { stream -> stream.write(bytes); stream.fd.sync() }
                if (!pending.renameTo(target)) return
                val files = root.listFiles()?.filter { it.isFile } ?: return
                var total = files.sumOf { it.length() }
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                for (file in files.sortedBy { it.lastModified() }) {
                    if (file == target || (total <= MAX_BYTES && file.lastModified() >= cutoff)) continue
                    val size = file.length()
                    if (file.delete()) total -= size
                }
            } catch (_: Exception) {
                // Best effort only; dimensions can always be obtained from the native renderer.
            } finally { pending?.delete() }
        }
    }

    private fun valid(widths: FloatArray, heights: FloatArray): Boolean =
        widths.size in 1..MAX_PAGES && widths.size == heights.size &&
            widths.all { it.isFinite() && it > 0f } && heights.all { it.isFinite() && it > 0f }
}
