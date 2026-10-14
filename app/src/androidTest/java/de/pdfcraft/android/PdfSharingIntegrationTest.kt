package de.pdfcraft.android

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfSharingIntegrationTest {
    @Test fun originalUnicodeNameAndBytesAreSharedWithoutRenamingThePrivateFile() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("generated-id-",".pdf",context.cacheDir)
        try {
            val bytes="%PDF-1.4 synthetic sharing fixture".toByteArray();file.writeBytes(bytes)
            val name="Mein Buch – über Größen.pdf"
            val intent=PdfSharing.intent(context,file,name)
            @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst());assertEquals(name,cursor.getString(0));assertEquals(bytes.size.toLong(),cursor.getLong(1))
            }
            assertArrayEquals(bytes,context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(file.name.startsWith("generated-id-"));assertTrue(file.exists())
            assertEquals(name,intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals("application/pdf",context.contentResolver.getType(uri))
        } finally { file.delete() }
    }
}
