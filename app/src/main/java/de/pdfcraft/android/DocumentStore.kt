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
    val contentHash: String = id,
    val viewRotation: Int = 0,
    val folderId: String? = null,
)

/** Imports must run on a worker thread. No persistent URI permissions are needed. */
class DocumentStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    internal val directory = File(this.context.filesDir, "pdf_documents")

    fun folders(): List<LibraryFolder> = synchronized(LOCK) { readFolders() }

    fun createFolder(name: String, parentId: String? = null): LibraryFolder = synchronized(LOCK) {
        val folder = LibraryFolder(LibraryFolders.newId(), LibraryFolders.cleanName(name), parentId)
        writeLibrary(readDocuments(), readFolders() + folder)
        folder
    }

    fun editFolder(id: String, name: String, parentId: String?) = synchronized(LOCK) {
        val folders = readFolders()
        require(folders.any { it.id == id }) { "Ordner nicht gefunden." }
        writeLibrary(readDocuments(), folders.map { if (it.id == id) it.copy(name = LibraryFolders.cleanName(name), parentId = parentId) else it })
    }

    fun moveDocuments(ids: Set<String>, folderId: String?) = synchronized(LOCK) {
        val folders = readFolders()
        require(folderId == null || folders.any { it.id == folderId }) { "Ordner nicht gefunden." }
        val documents = readDocuments()
        require(ids.all { id -> documents.any { it.id == id } }) { "Dokument nicht gefunden." }
        writeLibrary(documents.map { if (it.id in ids) it.copy(folderId = folderId) else it }, folders)
    }

    /** Dissolve promotes direct children; destructive deletion removes the entire subtree. */
    fun deleteFolder(id: String, deleteContents: Boolean) = synchronized(LOCK) {
        val folders = readFolders()
        val folder = folders.firstOrNull { it.id == id } ?: return@synchronized
        val subtree = LibraryFolders.descendants(folders, id)
        val documents = readDocuments()
        val removed = if (deleteContents) documents.filter { it.folderId in subtree } else emptyList()
        val updated = if (deleteContents) documents - removed.toSet() else documents.map {
            if (it.folderId == id) it.copy(folderId = folder.parentId) else it
        }
        val remaining = if (deleteContents) folders.filterNot { it.id in subtree } else folders.filterNot { it.id == id }.map {
            if (it.parentId == id) it.copy(parentId = folder.parentId) else it
        }
        // Validation also prevents duplicate sibling names when dissolving a folder.
        writeLibrary(updated, remaining)
        removed.forEach { file(it).delete() } // Only unreferenced private copies, never external files.
    }

    internal fun <T> locked(action: () -> T): T = synchronized(LOCK) { action() }

    internal fun exportMetadata(): JSONObject = synchronized(LOCK) {
        JSONObject().put("documents", encodeDocuments(readDocuments())).put("folders", encodeFolders(readFolders()))
    }

    internal fun decodeMetadata(json: JSONObject): Pair<List<PdfDocument>, List<LibraryFolder>> {
        val documents = readDocuments(json.getJSONArray("documents").toString(), strict = true)
        val folders = decodeFolders(json.getJSONArray("folders"))
        validateLibrary(documents, folders)
        return documents to folders
    }

    internal fun installLibrary(documents: List<PdfDocument>, folders: List<LibraryFolder>, settings: JSONObject? = null) = synchronized(LOCK) {
        writeLibrary(documents, folders, settings)
    }

    /** Settings are journaled in the same commit as the restored library and replayed after restart. */
    internal fun applyRestoredSettings() = synchronized(LOCK) {
        val raw = preferences.getString("pendingSettings", null) ?: return@synchronized
        val settings = JSONObject(raw)
        for (group in LibraryBackup.SETTING_GROUPS) {
            val values = settings.optJSONObject(group) ?: continue
            val editor = context.getSharedPreferences(group, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) when (val value = values.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Number -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
            }
            if (!editor.commit()) throw IOException("Die Einstellungen konnten nicht wiederhergestellt werden.")
        }
        if (!preferences.edit().remove("pendingSettings").commit()) throw IOException("Die Einstellungen konnten nicht abgeschlossen werden.")
    }

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
    fun importDocument(uri: Uri, onProgress: ((copied: Long, total: Long) -> Unit)? = null, isCancelled: () -> Boolean = { false }, folderId: String? = null): PdfDocument {
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
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return synchronized(LOCK) {
                val documents = readDocuments()
                val existing = documents.firstOrNull { it.contentHash == hash }
                // An import of identical bytes retains all metadata and reading progress.
                if (existing != null && file(existing).isFile) return@synchronized existing
                // Edited documents retain their ID, so their original hash may already be occupied.
                val id = existing?.id ?: if (documents.none { it.id == hash }) hash else
                    MessageDigest.getInstance("SHA-256").digest(java.util.UUID.randomUUID().toString().toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
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
                    contentHash = hash,
                    folderId = folderId?.takeIf { target -> readFolders().any { it.id == target } },
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

    fun updateProgress(id: String, page: Int, offset: Float, zoom: Float, pageCount: Int, viewRotation: Int? = null) {
        val progress = ReadingProgress.sanitize(page, offset, zoom, pageCount)
        update(id) { document ->
            document.copy(
                page = progress.page,
                offset = progress.offset,
                zoom = progress.zoom,
                pageCount = progress.pageCount,
                lastView = "pdf",
                viewRotation = viewRotation?.let { PdfViewRotation.normalize(it) } ?: document.viewRotation,
                bookmarks = document.bookmarks.filter {
                    progress.pageCount == 0 || it < progress.pageCount
                },
            )
        }
    }

    /** Replaces only the private library copy, retaining ID, name and reading metadata. */
    fun replaceContent(id: String, source: File, pageCount: Int, expectedHash: String,
                       pageOrder: List<Int>? = null): PdfDocument {
        require(pageCount > 0)
        require(pageOrder == null || pageOrder.size == pageCount)
        val initial = get(id) ?: throw IOException("Dokument ist nicht mehr in der Bibliothek.")
        require(pageOrder == null || (pageOrder.distinct().size == pageOrder.size &&
            pageOrder.all { it >= 0 && (initial.pageCount == 0 || it < initial.pageCount) }))
        LocalPdfReplacement(file(initial), source).use { staged ->
            return synchronized(LOCK) {
                val documents = readDocuments()
                val current = documents.firstOrNull { it.id == id }
                    ?: throw IOException("Dokument ist nicht mehr in der Bibliothek.")
                if (current.contentHash != expectedHash) throw IOException("Die lokale PDF wurde inzwischen geändert. Bitte erneut öffnen.")
                val mappedPage = pageOrder?.indexOf(current.page)?.takeIf { it >= 0 }
                    ?: current.page.coerceIn(0, pageCount - 1)
                val updated = current.copy(
                    contentHash = staged.contentHash,
                    pageCount = pageCount,
                    page = mappedPage,
                    offset = if (pageOrder == null || pageOrder.getOrNull(mappedPage) == current.page) current.offset else 0f,
                    reflowOffset = if (pageOrder == null || pageOrder.getOrNull(mappedPage) == current.page) current.reflowOffset else 0f,
                    bookmarks = if (pageOrder == null) current.bookmarks.filter { it < pageCount }
                        else pageOrder.mapIndexedNotNull { index, old -> index.takeIf { old in current.bookmarks } },
                )
                staged.commit {
                    try { writeDocuments(documents.map { if (it.id == id) updated else it }) }
                    catch (failure: Exception) {
                        runCatching { writeDocuments(documents) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                        throw failure
                    }
                    updated
                }
            }
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

    fun updateTextProgress(id: String, page: Int, offset: Float, pageCount: Int, viewRotation: Int? = null) {
        val normalized = ReadingProgress.sanitize(page, offset, 1f, pageCount)
        update(id) { doc -> doc.copy(page = normalized.page, pageCount = normalized.pageCount,
            offset = if (doc.page == normalized.page) doc.offset else 0f,
            reflowOffset = normalized.offset, lastView = "reflow",
            viewRotation = viewRotation?.let { PdfViewRotation.normalize(it) } ?: doc.viewRotation) }
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

    private fun readDocuments(raw: String = preferences.getString(DOCUMENTS, "[]") ?: "[]", strict: Boolean = false): List<PdfDocument> {
        val array = try {
            JSONArray(raw)
        } catch (error: Exception) {
            if (strict) throw IOException("Ungültige Dokumentliste.", error)
            Log.w(TAG, "Dokumentliste konnte nicht gelesen werden.", error)
            return emptyList()
        }
        val documents = mutableListOf<PdfDocument>()
        for (index in 0 until array.length()) {
            try {
                val json = array.getJSONObject(index)
                val id = json.getString("id")
                val fileName = json.getString("fileName")
                if (strict) {
                    require(json.getString("name").length in 1..256) { "Ungültiger Dokumentname." }
                    require(json.getInt("pageCount") in 0..1_000_000 && json.getInt("page") >= 0) { "Ungültige Seitenzahl." }
                    require(json.getDouble("offset").isFinite() && json.getDouble("zoom").isFinite()) { "Ungültiger Lesestand." }
                    require(json.getJSONArray("bookmarks").length() <= 1_000_000) { "Zu viele Lesezeichen." }
                }
                if (!ID_PATTERN.matches(id) || fileName != "$id.pdf") {
                    if (strict) throw IOException("Ungültiger Dokumentpfad.")
                    continue
                }
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
                        json.optString("contentHash", id).takeIf { ID_PATTERN.matches(it) } ?: id,
                        PdfViewRotation.normalize(json.optInt("viewRotation", 0)),
                        if (json.isNull("folderId")) null else json.optString("folderId").takeIf { it.isNotEmpty() },
                    ),
                )
            } catch (error: Exception) {
                if (strict) throw IOException("Ungültiger Dokumenteintrag.", error)
                Log.w(TAG, "Ungültiger Dokumenteintrag wurde übersprungen.", error)
            }
        }
        if (strict && documents.map { it.id }.distinct().size != documents.size) throw IOException("Doppelte Dokument-ID.")
        return documents.distinctBy { it.id }
    }

    private fun writeDocuments(documents: List<PdfDocument>) {
        writeLibrary(documents, readFolders())
    }

    private fun encodeDocuments(documents: List<PdfDocument>): JSONArray {
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
                put("contentHash", document.contentHash)
                put("viewRotation", PdfViewRotation.normalize(document.viewRotation))
                put("folderId", document.folderId ?: JSONObject.NULL)
            })
        }
        return array
    }

    private fun readFolders(): List<LibraryFolder> = decodeFolders(JSONArray(preferences.getString("folders", "[]") ?: "[]"))

    private fun decodeFolders(array: JSONArray): List<LibraryFolder> = (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        LibraryFolder(json.getString("id"), json.getString("name"), if (json.isNull("parentId")) null else json.getString("parentId"))
    }.also { LibraryFolders.validate(it) }

    private fun encodeFolders(folders: List<LibraryFolder>) = JSONArray().apply {
        folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL)) }
    }

    private fun validateLibrary(documents: List<PdfDocument>, folders: List<LibraryFolder>) {
        LibraryFolders.validate(folders)
        val ids = folders.map { it.id }.toSet()
        require(documents.all { it.folderId == null || it.folderId in ids }) { "Dokument verweist auf einen fehlenden Ordner." }
    }

    private fun writeLibrary(documents: List<PdfDocument>, folders: List<LibraryFolder>, settings: JSONObject? = null) {
        validateLibrary(documents, folders)
        val oldDocuments = preferences.getString(DOCUMENTS, "[]")
        val oldFolders = preferences.getString("folders", "[]")
        val oldSettings = preferences.getString("pendingSettings", null)
        val editor = preferences.edit().putString(DOCUMENTS, encodeDocuments(documents).toString())
            .putString("folders", encodeFolders(folders).toString())
        if (settings != null) editor.putString("pendingSettings", settings.toString())
        if (!editor.commit()) {
            preferences.edit().putString(DOCUMENTS, oldDocuments).putString("folders", oldFolders)
                .putString("pendingSettings", oldSettings).commit()
            throw IOException("Die Bibliotheksdaten konnten nicht gespeichert werden.")
        }
    }

    internal companion object {
        val LOCK = Any()
        const val TAG = "DocumentStore"
        const val PREFERENCES = "pdfcraft_documents"
        const val DOCUMENTS = "documents"
        const val MAX_BYTES = 500L * 1024 * 1024
        val ID_PATTERN = Regex("[0-9a-f]{64}")
        val PDF_HEADER = Regex("%PDF-[12]\\.[0-9]")
    }
}
