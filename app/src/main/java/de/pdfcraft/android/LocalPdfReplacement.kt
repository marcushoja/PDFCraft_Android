package de.pdfcraft.android

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Stages off the UI thread. A failed metadata commit restores the previous private PDF. */
internal class LocalPdfReplacement(private val destination: File, source: File) : Closeable {
    private val staged = File.createTempFile("edit-", ".tmp", destination.parentFile)
    val contentHash: String

    init {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(1024)
            var headerSize = 0
            var total = 0L
            source.inputStream().use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > 500L * 1024 * 1024) throw IOException("PDF exceeds 500 MiB")
                        val prefix = minOf(count, header.size - headerSize)
                        buffer.copyInto(header, headerSize, 0, prefix)
                        headerSize += prefix
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (!Regex("%PDF-[12]\\.[0-9]").containsMatchIn(String(header, 0, headerSize, Charsets.ISO_8859_1)))
                throw IOException("Invalid PDF header")
            contentHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }

    fun <T> commit(saveMetadata: () -> T): T {
        if (!destination.isFile) throw IOException("Local PDF is missing")
        // All renderer/text/thumbnail cache identities include modification time and size.
        val stamp = maxOf(System.currentTimeMillis(), destination.lastModified() + 1000L)
        if (!staged.setLastModified(stamp)) throw IOException("Could not update PDF revision")
        val backup = File.createTempFile("edit-backup-", ".tmp", destination.parentFile)
        var replaced = false
        var keepBackup = false
        try {
            Files.delete(backup.toPath())
            try { Files.createLink(backup.toPath(), destination.toPath()) }
            catch (_: Exception) {
                // Some filesystems do not support hard links. Never modify the old file in place.
                Files.deleteIfExists(backup.toPath())
                destination.inputStream().use { input ->
                    FileOutputStream(backup).use { output -> input.copyTo(output); output.fd.sync() }
                }
            }
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            replaced = true
            return saveMetadata()
        } catch (failure: Throwable) {
            if (replaced) {
                try { Files.move(backup.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (restore: Throwable) {
                    keepBackup = true
                    failure.addSuppressed(restore)
                }
            }
            throw failure
        } finally {
            if (!keepBackup) backup.delete()
            staged.delete()
        }
    }

    override fun close() { staged.delete() }
}
