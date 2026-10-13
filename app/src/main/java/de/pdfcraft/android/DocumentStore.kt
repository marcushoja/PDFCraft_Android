package de.pdfcraft.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class PdfDocument(
    val id: String,
    val name: String,
    val fileName: String,
    val addedAt: Long,
    val lastOpened: Long,
    val pageCount: Int,
    val page: Int,
    val offset: Float,
    val zoom: Float,
    val bookmarks: List<Int>,
    val reflowOffset: Float = 0f,
    val lastView: String = "pdf",
)

/** Imports must run on a worker thread. No persistent URI permissions are needed. */
class DocumentStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val directory = File(this.context.filesDir, "pdf_documents")

    // Shared across instances so read/modify/write operations cannot lose updates.
    fun list(): List<PdfDocument> = synchronized(LOCK) {
        readDocuments().sortedWith(
            compareByDescending<PdfDocument> { it.lastOpened }
                .thenByDescending { it.addedAt }
                .thenBy { it.id },
        )
    }

    fun get(id: String): PdfDocument? = synchronized(LOCK) {
        readDocuments().firstOrNull { it.id == id }
    }

    fun file(document: PdfDocument): File {
        require(ID_PATTERN.matches(document.id) && document.fileName == "${document.id}.pdf") {
            "Ungültiger Dokumentpfad."
        }
        return File(directory, document.fileName)
    }

    /** Copies at most 500 MiB, checks the header, and atomically renames the copy. */
    fun importDocument(uri: Uri, onProgress: ((copied: Long, total: Long) -> Unit)? = null, isCancelled: () -> Boolean = { false }): PdfDocument {
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Der Speicherordner konnte nicht erstellt werden.")
            }
            val name = displayName(uri)
            val total = try {
                if (uri.scheme == "file") File(uri.path.orEmpty()).length()
                else context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
            } catch (_: Exception) { -1L }
            val copy = File.createTempFile("import-", ".tmp", directory)
            temporary = copy
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(1024)
            var headerLength = 0
            var size = 0L
            var lastProgress = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Die ausgewählte Datei konnte nicht geöffnet werden.")
            input.use { source ->
                FileOutputStream(copy).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Import abgebrochen.")
                        val length = source.read(buffer)
                        if (length < 0) break
                        if (length == 0) continue
                        size += length
                        if (size > MAX_BYTES) {
                            throw IOException("Die PDF-Datei ist zu groß (maximal 500 MiB).")
                        }
                        if (headerLength < header.size) {
                            val count = minOf(length, header.size - headerLength)
                            buffer.copyInto(header, headerLength, 0, count)
                            headerLength += count
                        }
                        digest.update(buffer, 0, length)
                        target.write(buffer, 0, length)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProgress >= 100) { onProgress?.invoke(size, total); lastProgress = now }
                    }
                    target.fd.sync()
                }
            }
            val prefix = String(header, 0, headerLength, Charsets.ISO_8859_1)
            if (isCancelled()) throw java.io.InterruptedIOException("Import abgebrochen.")
            onProgress?.invoke(size, total)
            if (!PDF_HEADER.containsMatchIn(prefix)) {
                throw IOException("Die ausgewählte Datei ist keine gültige PDF-Datei (PDF-Kopf fehlt).")
            }
            val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return synchronized(LOCK) {
                val documents = readDocuments()
                val existing = documents.firstOrNull { it.id == id }
                // An import of identical bytes retains all metadata and reading progress.
                if (existing != null && file(existing).isFile) return@synchronized existing
                val document = existing ?: PdfDocument(
                    id = id,
                    name = name,
                    fileName = "$id.pdf",
                    addedAt = System.currentTimeMillis(),
                    lastOpened = 0L,
                    pageCount = 0,
                    page = 0,
                    offset = 0f,
                    zoom = 1f,
                    bookmarks = emptyList(),
                )
                val destination = file(document)
                val created = !destination.exists()
                if (created && !copy.renameTo(destination)) {
                    throw IOException("Die PDF-Datei konnte nicht gespeichert werden.")
                }
                try {
                    if (existing == null) writeDocuments(documents + document)
                } catch (error: Exception) {
                    if (created) destination.delete()
                    throw error
                }
                document
            }
        } catch (error: SecurityException) {
            throw IOException("Kein Zugriff auf die ausgewählte Datei. Bitte erneut auswählen.", error)
        } catch (error: IOException) {
            throw IOException("PDF-Import fehlgeschlagen: ${error.message ?: "Datei konnte nicht gelesen werden."}", error)
        } catch (error: Exception) {
            throw IOException("Die PDF-Datei konnte nicht importiert werden.", error)
        } finally {
            temporary?.let { if (it.exists()) it.delete() }
        }
    }

    fun updateProgress(id: String, page: Int, offset: Float, zoom: Float, pageCount: Int) {
        val progress = ReadingProgress.sanitize(page, offset, zoom, pageCount)
        update(id) { document ->
            document.copy(
                page = progress.page,
                offset = progress.offset,
                zoom = progress.zoom,
                pageCount = progress.pageCount,
                lastView = "pdf",
                bookmarks = document.bookmarks.filter {
                    progress.pageCount == 0 || it < progress.pageCount
                },
            )
        }
    }

    fun markOpened(id: String) {
        update(id) { it.copy(lastOpened = System.currentTimeMillis()) }
    }

    fun rename(id: String, name: String) {
        val cleaned = name.filterNot { it.isISOControl() }.trim().take(256)
        require(cleaned.isNotEmpty()) { "Bitte einen Dokumentnamen eingeben." }
        update(id) { it.copy(name = cleaned) }
    }

    fun updateTextProgress(id: String, page: Int, offset: Float, pageCount: Int) {
        val normalized = ReadingProgress.sanitize(page, offset, 1f, pageCount)
        update(id) { doc -> doc.copy(page = normalized.page, pageCount = normalized.pageCount,
            offset = if (doc.page == normalized.page) doc.offset else 0f,
            reflowOffset = normalized.offset, lastView = "reflow") }
    }

    fun toggleBookmark(id: String, page: Int) {
        update(id) { document ->
            if (page < 0 || (document.pageCount > 0 && page >= document.pageCount)) {
                document
            } else {
                val bookmarks = document.bookmarks.toMutableSet()
                if (!bookmarks.add(page)) bookmarks.remove(page)
                document.copy(bookmarks = bookmarks.sorted())
            }
        }
    }

    /** Removes only the app's imported copy, never the original URI. */
    fun remove(id: String) = synchronized(LOCK) {
        val documents = readDocuments()
        val document = documents.firstOrNull { it.id == id } ?: return@synchronized
        writeDocuments(documents.filterNot { it.id == id })
        val copy = file(document)
        if (copy.exists() && !copy.delete()) {
            // Restore the record so the user can retry removing the copy.
            writeDocuments(documents)
            throw IOException("Die importierte PDF-Kopie konnte nicht gelöscht werden.")
        }
    }

    private fun update(id: String, transform: (PdfDocument) -> PdfDocument) = synchronized(LOCK) {
        val documents = readDocuments()
        val index = documents.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val updated = transform(documents[index])
        if (updated != documents[index]) {
            writeDocuments(documents.toMutableList().apply { this[index] = updated })
        }
    }

    private fun displayName(uri: Uri): String {
        val queried = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        } catch (_: Exception) {
            null
        }
        return (queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokument.pdf")
            .filterNot { it.isISOControl() }.trim().take(256).ifEmpty { "Dokument.pdf" }
    }

    private fun readDocuments(): List<PdfDocument> {
        val array = try {
            JSONArray(preferences.getString(DOCUMENTS, "[]") ?: "[]")
        } catch (error: Exception) {
            Log.w(TAG, "Dokumentliste konnte nicht gelesen werden.", error)
            return emptyList()
        }
        val documents = mutableListOf<PdfDocument>()
        for (index in 0 until array.length()) {
            try {
                val json = array.getJSONObject(index)
                val id = json.getString("id")
                val fileName = json.getString("fileName")
                if (!ID_PATTERN.matches(id) || fileName != "$id.pdf") continue
                val progress = ReadingProgress.sanitize(
                    json.optInt("page", 0),
                    json.optDouble("offset", 0.0).toFloat(),
                    json.optDouble("zoom", 1.0).toFloat(),
                    json.optInt("pageCount", 0),
                )
                val bookmarks = json.optJSONArray("bookmarks") ?: JSONArray()
                val pages = (0 until bookmarks.length()).map { bookmarks.optInt(it, -1) }
                    .filter { it >= 0 && (progress.pageCount == 0 || it < progress.pageCount) }
                    .distinct().sorted()
                documents.add(
                    PdfDocument(
                        id, json.optString("name", "Dokument.pdf"), fileName,
                        json.optLong("addedAt", 0L).coerceAtLeast(0L),
                        json.optLong("lastOpened", 0L).coerceAtLeast(0L),
                        progress.pageCount, progress.page, progress.offset, progress.zoom, pages,
                        json.optDouble("reflowOffset", 0.0).toFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f },
                        if (json.optString("lastView") == "reflow") "reflow" else "pdf",
                    ),
                )
            } catch (error: Exception) {
                Log.w(TAG, "Ungültiger Dokumenteintrag wurde übersprungen.", error)
            }
        }
        return documents.distinctBy { it.id }
    }

    private fun writeDocuments(documents: List<PdfDocument>) {
        val array = JSONArray()
        documents.forEach { document ->
            array.put(JSONObject().apply {
                put("id", document.id)
                put("name", document.name)
                put("fileName", document.fileName)
                put("addedAt", document.addedAt)
                put("lastOpened", document.lastOpened)
                put("pageCount", document.pageCount)
                put("page", document.page)
                put("offset", document.offset.toDouble())
                put("zoom", document.zoom.toDouble())
                put("bookmarks", JSONArray(document.bookmarks))
                put("reflowOffset", document.reflowOffset.toDouble())
                put("lastView", document.lastView)
            })
        }
        if (!preferences.edit().putString(DOCUMENTS, array.toString()).commit()) {
            throw IOException("Die Dokumentdaten konnten nicht gespeichert werden.")
        }
    }

    private companion object {
        val LOCK = Any()
        const val TAG = "DocumentStore"
        const val PREFERENCES = "pdfcraft_documents"
        const val DOCUMENTS = "documents"
        const val MAX_BYTES = 500L * 1024 * 1024
        val ID_PATTERN = Regex("[0-9a-f]{64}")
        val PDF_HEADER = Regex("%PDF-[12]\\.[0-9]")
    }
}
