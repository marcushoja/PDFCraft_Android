package de.pdfcraft.android

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** All methods run on a worker. Archives never control a filesystem path or preference filename. */
class LibraryBackup(private val context: Context, private val store: DocumentStore) {
    class Prepared internal constructor(internal val directory: File, internal val metadata: JSONObject,
        val documents: List<PdfDocument>, val folders: List<LibraryFolder>, val createdAt: Long) : Closeable {
        override fun close() { directory.deleteRecursively() }
    }

    private fun staging(): File = File.createTempFile("library-backup-", ".stage", context.cacheDir).let {
        if (!it.delete() || !it.mkdir()) throw IOException("Der Backup-Zwischenspeicher konnte nicht erstellt werden.")
        it
    }

    fun export(output: OutputStream, password: CharArray = charArrayOf(), cancelled: () -> Boolean = { false },
               progress: (Int, Int) -> Unit = { _, _ -> }) {
        val temp = staging()
        fun check() { checkCancelled(cancelled) }
        try {
            val archive = File(temp, "library.zip")
            store.locked {
                check()
                val metadata = store.exportMetadata()
                val documents = store.decodeMetadata(metadata).first
                require(documents.size <= MAX_DOCUMENTS) { "Zu viele Dokumente für eine Sicherung." }
                metadata.put("format", "PDFPauli").put("version", 1).put("createdAt", System.currentTimeMillis())
                val settings = JSONObject()
                SETTING_GROUPS.forEach { group ->
                    settings.put(group, JSONObject(context.getSharedPreferences(group, Context.MODE_PRIVATE).all.filterValues {
                        it is Boolean || it is Number || it is String
                    }.filterKeys { it in SETTING_TYPES.getValue(group) }))
                }
                metadata.put("settings", settings)
                val hashes = JSONObject()
                FileOutputStream(archive).use { file ->
                    ZipOutputStream(file).use { zip ->
                        zip.setLevel(0) // PDFs are already compressed; avoid a slow second compression pass.
                        var total = 0L
                        documents.forEachIndexed { index, document ->
                            check(); progress(index, documents.size)
                            val source = store.file(document)
                            if (!source.isFile) throw IOException("PDF fehlt: ${document.name}")
                            val size = source.length()
                            if (size > DocumentStore.MAX_BYTES || size <= 0 || total + size > MAX_TOTAL) throw IOException("Die Bibliothek ist zu groß für eine Sicherung.")
                            ensureSpace(size + SAFETY_SPACE)
                            total += size
                            zip.putNextEntry(ZipEntry("documents/${document.id}.pdf"))
                            val digest = MessageDigest.getInstance("SHA-256")
                            source.inputStream().use { copy(it, zip, size, cancelled, digest) }
                            val hash = hex(digest.digest())
                            hashes.put(document.id, hash)
                            metadata.getJSONArray("documents").getJSONObject(index).put("contentHash", hash)
                            zip.closeEntry()
                        }
                        metadata.put("hashes", hashes)
                        val manifest = metadata.toString().toByteArray(Charsets.UTF_8)
                        if (manifest.size > MAX_MANIFEST) throw IOException("Die Backup-Metadaten sind zu groß.")
                        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                        zip.finish(); file.fd.sync()
                    }
                }
            }
            check()
            progress(0, 0) // Provider writing/encryption is a separate, indeterminate phase.
            archive.inputStream().use { BackupEnvelope.write(it, output, password, ::check) }
            check()
            progress(1, 1)
        } finally { temp.deleteRecursively() }
    }

    /** Fully authenticates, validates and extracts before the library can be changed. */
    fun prepare(input: InputStream, password: CharArray = charArrayOf(), cancelled: () -> Boolean = { false },
                progress: (Int, Int) -> Unit = { _, _ -> }): Prepared {
        val temp = staging()
        try {
            val archive = File(temp, "library.zip")
            FileOutputStream(archive).use { file ->
                val bounded = object : OutputStream() {
                    var size = 0L
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        checkCancelled(cancelled)
                        size += count
                        if (size > MAX_TOTAL + 128L * 1024 * 1024) throw IOException("Die Sicherung ist zu groß.")
                        ensureSpace(count.toLong() + SAFETY_SPACE)
                        file.write(bytes, offset, count)
                    }
                }
                BackupEnvelope.read(input, bounded, password) { checkCancelled(cancelled) }
                file.fd.sync()
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().take(MAX_DOCUMENTS + 2).toList()
                require(entries.size <= MAX_DOCUMENTS + 1 && entries.map { it.name }.distinct().size == entries.size) { "Ungültiges Backup-Archiv." }
                require(entries.all { it.name == "manifest.json" || Regex("documents/[0-9a-f]{64}\\.pdf").matches(it.name) }) { "Unerlaubter Pfad im Backup." }
                val manifest = zip.getEntry("manifest.json") ?: throw IOException("Backup-Metadaten fehlen.")
                require(manifest.size in 1..MAX_MANIFEST.toLong()) { "Ungültige Backup-Metadaten." }
                val json = JSONObject(zip.getInputStream(manifest).use { source ->
                    val bytes = java.io.ByteArrayOutputStream()
                    copy(source, bytes, MAX_MANIFEST.toLong(), cancelled)
                    bytes.toString("UTF-8")
                })
                require(json.getString("format") == "PDFPauli" && json.getInt("version") == 1) { "Nicht unterstützte Backup-Version." }
                val (documents, folders) = store.decodeMetadata(json)
                require(documents.size <= MAX_DOCUMENTS && entries.size == documents.size + 1) { "Unvollständige Sicherung." }
                validateSettings(json.getJSONObject("settings"))
                require(json.getLong("createdAt") >= 0) { "Ungültiges Sicherungsdatum." }
                val hashes = json.getJSONObject("hashes")
                require(hashes.length() == documents.size) { "Ungültige Prüfsummenliste." }
                var total = 0L
                documents.forEachIndexed { index, document ->
                    checkCancelled(cancelled); progress(index, documents.size)
                    val entry = zip.getEntry("documents/${document.id}.pdf") ?: throw IOException("PDF fehlt im Backup.")
                    require(entry.size in 1..DocumentStore.MAX_BYTES) { "Ungültige PDF-Größe." }
                    total += entry.size
                    require(total <= MAX_TOTAL) { "Die Sicherung ist zu groß." }
                    ensureSpace(entry.size + SAFETY_SPACE)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val destination = File(temp, document.fileName)
                    FileOutputStream(destination).use { out ->
                        val copied = zip.getInputStream(entry).use { copy(it, out, entry.size, cancelled, digest) }
                        require(copied == entry.size) { "Unvollständige PDF im Backup." }
                        out.fd.sync()
                    }
                    require(hex(digest.digest()) == hashes.getString(document.id) && hashes.getString(document.id) == document.contentHash) { "PDF-Prüfsumme stimmt nicht überein." }
                    val header = ByteArray(1024)
                    val count = destination.inputStream().use { it.read(header) }
                    require(count > 0 && Regex("%PDF-[12]\\.[0-9]").containsMatchIn(String(header, 0, count, Charsets.ISO_8859_1))) { "Ungültige PDF im Backup." }
                }
                checkCancelled(cancelled)
                archive.delete()
                progress(documents.size, documents.size)
                return Prepared(temp, json, documents, folders, json.getLong("createdAt"))
            }
        } catch (failure: Exception) { temp.deleteRecursively(); throw failure }
    }

    /** New files are installed first; one metadata commit publishes the complete result. */
    fun restore(prepared: Prepared, replace: Boolean, cancelled: () -> Boolean = { false }): String? = store.locked {
        checkCancelled(cancelled)
        val existing = store.list()
        val existingFolders = store.folders()
        val created = mutableListOf<File>()
        var committed = false
        try {
            if (!store.directory.isDirectory && !store.directory.mkdirs()) throw IOException("Bibliotheksordner fehlt.")
            var collection: LibraryFolder? = null
            val folderMap = if (replace) prepared.folders.associate { it.id to it.id } else prepared.folders.associate { it.id to LibraryFolders.newId() }
            if (!replace) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.ROOT).format(java.util.Date())
                val base = LanguageContext.wrap(context).getString(R.string.backup_collection_name, date)
                var name = base; var suffix = 2
                while (existingFolders.any { it.parentId == null && it.name.equals(name, true) }) { name = "$base (${suffix++})" }
                collection = LibraryFolder(LibraryFolders.newId(), name)
            }
            val folders = prepared.folders.map { folder -> folder.copy(id = folderMap.getValue(folder.id),
                parentId = folder.parentId?.let { folderMap.getValue(it) } ?: collection?.id) }
            val newFolders = folders + listOfNotNull(collection)
            LibraryFolders.validate(if (replace) newFolders else existingFolders + newFolders)
            val documents = prepared.documents.map { document ->
                checkCancelled(cancelled)
                // Never overwrite an existing file, even for replacement; publish only after all moves succeed.
                var id = document.id
                while (!replace || File(store.directory, "$id.pdf").exists() || existing.any { it.id == id }) {
                    id = hex(MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray()))
                    if (!File(store.directory, "$id.pdf").exists() && existing.none { it.id == id }) break
                }
                val destination = File(store.directory, "$id.pdf")
                if (!File(prepared.directory, document.fileName).renameTo(destination)) throw IOException("Die PDF konnte nicht wiederhergestellt werden.")
                created.add(destination)
                document.copy(id = id, fileName = "$id.pdf", folderId = document.folderId?.let { folderMap.getValue(it) } ?: collection?.id)
            }
            checkCancelled(cancelled)
            store.installLibrary(if (replace) documents else existing + documents,
                if (replace) newFolders else existingFolders + newFolders,
                if (replace) prepared.metadata.getJSONObject("settings") else null)
            committed = true
            if (replace) existing.forEach { store.file(it).delete() }
            collection?.id
        } finally {
            if (!committed) created.forEach { it.delete() }
        }
    }

    private fun validateSettings(settings: JSONObject) {
        require(settings.keys().asSequence().all { it in SETTING_GROUPS }) { "Unbekannte Einstellungen im Backup." }
        SETTING_GROUPS.forEach { group ->
            val values = settings.getJSONObject(group)
            require(values.length() <= 100) { "Zu viele Einstellungen." }
            for (key in values.keys()) {
                val value = values.get(key)
                val type = SETTING_TYPES.getValue(group)[key] ?: throw IOException("Unbekannte Einstellung im Backup.")
                require(when (type) {
                    "boolean" -> value is Boolean
                    "int" -> value is Int
                    else -> value is String && value.length <= 256
                }) { "Ungültiger Einstellungstyp." }
                if (key == "font") require((value as Int) in 14..34) { "Ungültige Schriftgröße." }
                if (key == "sort") require((value as Int) in 0..3) { "Ungültige Sortierung." }
                if (key == "language") require(value in listOf("system", "de", "en", "fr", "es")) { "Unbekannte Sprache." }
                if (key == "layout") require(ReadingMode.entries.any { it.name == value }) { "Unbekannte Seitenansicht." }
                if (key == "markup_style") require(TextMarkupStyle.entries.any { it.name == value }) { "Unbekannte Markierung." }
            }
        }
    }

    private fun ensureSpace(bytes: Long) {
        if (context.cacheDir.usableSpace < bytes) throw IOException("Nicht genügend freier Speicher für die Sicherung.")
    }

    companion object {
        /** Remove abandoned, private stages after process death, never a currently active stage. */
        internal fun cleanAbandonedStages(context: Context) {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            context.cacheDir.listFiles()?.filter {
                it.isDirectory && it.name.startsWith("library-backup-") && it.name.endsWith(".stage") && it.lastModified() < cutoff
            }?.forEach { it.deleteRecursively() }
        }
        internal val SETTING_GROUPS = listOf("reader_settings", "library_settings")
        private val SETTING_TYPES = mapOf(
            "reader_settings" to (listOf("single", "rtl", "fit", "dark", "dark_pages", "awake", "serif").associateWith { "boolean" } +
                listOf("font", "markup_color").associateWith { "int" } + listOf("language", "layout", "markup_style").associateWith { "string" }),
            "library_settings" to mapOf("list" to "boolean", "sort" to "int"),
        )
        private const val MAX_DOCUMENTS = 10_000
        private const val MAX_MANIFEST = 8 * 1024 * 1024
        private const val MAX_TOTAL = 32L * 1024 * 1024 * 1024
        private const val SAFETY_SPACE = 16L * 1024 * 1024

        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Vorgang abgebrochen.")
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun copy(input: InputStream, output: OutputStream, limit: Long, cancelled: () -> Boolean,
                         digest: MessageDigest? = null): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) return total
                if (count == 0) continue
                total += count
                if (total > limit) throw IOException("Die Datei überschreitet die zulässige Größe.")
                digest?.update(buffer, 0, count); output.write(buffer, 0, count)
            }
        }
    }
}
