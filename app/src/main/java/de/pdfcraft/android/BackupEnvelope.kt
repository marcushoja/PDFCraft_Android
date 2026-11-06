package de.pdfcraft.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, bounded-memory envelope. Every encrypted record and the final marker authenticate. */
object BackupEnvelope {
    private val MAGIC = "PPAULI01".toByteArray(Charsets.US_ASCII)
    private const val CHUNK = 64 * 1024
    private const val ITERATIONS = 600_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size <= 1024) { "Das Passwort ist zu lang." }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    fun write(input: InputStream, output: OutputStream, password: CharArray, check: () -> Unit = {}) {
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.writeByte(if (password.isEmpty()) 0 else 1)
        if (password.isEmpty()) { copy(input, out, check); out.flush(); return }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        out.write(salt); out.write(prefix)
        val key = key(password, salt)
        val aad = MAGIC + byteArrayOf(1) + salt + prefix
        val buffer = ByteArray(CHUNK)
        var sequence = 0
        while (true) {
            check()
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count > 0) size += count
                check()
            }
            val cipher = cipher(Cipher.ENCRYPT_MODE, key, prefix, sequence++, aad)
            val encrypted = cipher.doFinal(buffer, 0, size)
            out.writeInt(encrypted.size); out.write(encrypted)
            if (size == 0) break // Authenticated end marker: truncation is never a valid backup.
        }
        buffer.fill(0)
        out.flush()
    }

    fun read(input: InputStream, output: OutputStream, password: CharArray, check: () -> Unit = {}) {
        val source = DataInputStream(input)
        val magic = ByteArray(MAGIC.size); source.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Keine unterstützte PDFPauli-Sicherung.")
        when (source.readUnsignedByte()) {
            0 -> { copy(source, output, check); return }
            1 -> if (password.isEmpty()) throw IOException("Diese Sicherung benötigt ein Passwort.")
            else -> throw IOException("Unbekannte Backup-Version.")
        }
        val salt = ByteArray(16); source.readFully(salt)
        val prefix = ByteArray(8); source.readFully(prefix)
        val key = key(password, salt)
        val aad = MAGIC + byteArrayOf(1) + salt + prefix
        var sequence = 0
        try {
            while (true) {
                check()
                val size = source.readInt()
                if (size !in 16..CHUNK + 16) throw IOException("Beschädigte Sicherung.")
                val encrypted = ByteArray(size); source.readFully(encrypted)
                val plain = cipher(Cipher.DECRYPT_MODE, key, prefix, sequence++, aad).doFinal(encrypted)
                if (plain.isEmpty()) {
                    if (source.read() != -1) throw IOException("Unerwartete Daten nach der Sicherung.")
                    break
                }
                output.write(plain); plain.fill(0)
            }
        } catch (error: java.security.GeneralSecurityException) {
            throw IOException("Passwort falsch oder Sicherung beschädigt.", error)
        } catch (error: EOFException) {
            throw IOException("Die Sicherung ist unvollständig.", error)
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, sequence: Int, aad: ByteArray): Cipher {
        if (sequence < 0) throw IOException("Die Sicherung ist zu groß.")
        val nonce = java.nio.ByteBuffer.allocate(12).put(prefix).putInt(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce)); updateAAD(aad)
        }
    }

    private fun copy(input: InputStream, output: OutputStream, check: () -> Unit) {
        val buffer = ByteArray(CHUNK)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) return
            if (count > 0) output.write(buffer, 0, count)
        }
    }
}
