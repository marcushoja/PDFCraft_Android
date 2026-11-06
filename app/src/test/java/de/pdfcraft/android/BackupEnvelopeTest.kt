package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

class BackupEnvelopeTest {
    private val password = "a good backup password".toCharArray()
    private fun encode(bytes: ByteArray, password: CharArray = this.password) = ByteArrayOutputStream().also {
        BackupEnvelope.write(ByteArrayInputStream(bytes), it, password)
    }.toByteArray()
    private fun decode(bytes: ByteArray, password: CharArray = this.password) = ByteArrayOutputStream().also {
        BackupEnvelope.read(ByteArrayInputStream(bytes), it, password)
    }.toByteArray()

    @Test fun plainAndEncryptedRoundTripsCoverEmptyExactAndMultipleRecords() {
        for (length in listOf(0, 1, 65536, 150000)) {
            val bytes = ByteArray(length) { (it * 37).toByte() }
            for (key in listOf(charArrayOf(), password)) assertArrayEquals(bytes, decode(encode(bytes, key), key))
        }
    }

    @Test fun samePasswordUsesIndependentSaltsAndNonces() {
        val a = encode("private document".toByteArray())
        val b = encode("private document".toByteArray())
        assertFalse(a.contentEquals(b))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("private document"))
    }

    @Test fun wrongPasswordTamperingTruncationAndTrailingBytesAreRejected() {
        val encoded = encode(ByteArray(80000) { (it * 13).toByte() })
        assertThrows(IOException::class.java) { decode(encoded, "wrong password".toCharArray()) }
        assertThrows(IOException::class.java) { decode(encoded, charArrayOf()) }
        for (index in listOf(0, 9, 25, 40, encoded.lastIndex)) {
            val changed = encoded.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { decode(changed) }
        }
        // Removing even the entire final authenticated record must fail.
        for (size in listOf(8, 32, encoded.size - 1, encoded.size - 20))
            assertThrows(IOException::class.java) { decode(encoded.copyOf(size)) }
        assertThrows(IOException::class.java) { decode(encoded + byteArrayOf(0)) }
    }

    @Test fun reorderedRecordsAndOversizedFramesAreRejected() {
        val encoded = encode(ByteArray(140000) { (it * 19).toByte() })
        val header = 8 + 1 + 16 + 8
        val firstEnd = header + 4 + ByteBuffer.wrap(encoded, header, 4).int
        val secondEnd = firstEnd + 4 + ByteBuffer.wrap(encoded, firstEnd, 4).int
        val swapped = encoded.copyOfRange(0, header) + encoded.copyOfRange(firstEnd, secondEnd) +
            encoded.copyOfRange(header, firstEnd) + encoded.copyOfRange(secondEnd, encoded.size)
        assertThrows(IOException::class.java) { decode(swapped) }
        val oversized = encoded.copyOf()
        ByteBuffer.wrap(oversized, header, 4).putInt(Int.MAX_VALUE)
        assertThrows(IOException::class.java) { decode(oversized) }
    }

    @Test fun cancellationIsObservedWhileReadingAndWriting() {
        val bytes = ByteArray(200000)
        assertThrows(InterruptedIOException::class.java) {
            BackupEnvelope.write(ByteArrayInputStream(bytes), ByteArrayOutputStream(), password) { throw InterruptedIOException() }
        }
        val encoded = encode(bytes)
        assertThrows(InterruptedIOException::class.java) {
            BackupEnvelope.read(ByteArrayInputStream(encoded), ByteArrayOutputStream(), password) { throw InterruptedIOException() }
        }
    }
}
