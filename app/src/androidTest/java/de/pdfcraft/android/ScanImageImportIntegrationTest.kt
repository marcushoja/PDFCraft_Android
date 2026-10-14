package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class ScanImageImportIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun jpegAndPngAreCopiedWithoutChangingTheSource() {
        for(format in listOf(Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.PNG)) {
            val source=File.createTempFile("gallery-source-",".img",context.cacheDir)
            val output=File(context.cacheDir,"gallery-copy-${System.nanoTime()}.img")
            val bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                source.outputStream().use { bitmap.compress(format,95,it) }
                val original=source.readBytes()
                ScanImageImport.copy(context,Uri.fromFile(source),output)
                assertArrayEquals(original,output.readBytes());assertArrayEquals(original,source.readBytes())
                ScanProcessing.loadPhoto(output).also { assertEquals(120,it.width);assertEquals(180,it.height) }.recycle()
            } finally { bitmap.recycle();source.delete();output.delete() }
        }
    }
    @Test fun invalidImagesAndCancellationDoNotLeavePartialCopies() {
        val source=File.createTempFile("gallery-invalid-",".img",context.cacheDir)
        val output=File(context.cacheDir,"gallery-failure-${System.nanoTime()}.img")
        try {
            source.writeText("not an image")
            try { ScanImageImport.copy(context,Uri.fromFile(source),output);fail("Invalid image accepted") } catch(_:IOException) {}
            assertFalse(output.exists());assertEquals("not an image",source.readText())
            try { ScanImageImport.copy(context,Uri.fromFile(source),output) { true };fail("Cancellation ignored") } catch(_:CancellationException) {}
            assertFalse(output.exists());assertTrue(source.exists())
        } finally { source.delete();output.delete() }
    }
    @Test fun existingDestinationIsNeverOverwritten() {
        val file=File.createTempFile("gallery-existing-",".img",context.cacheDir)
        try {
            file.writeText("keep this file")
            try { ScanImageImport.copy(context,Uri.fromFile(file),file);fail("Existing output accepted") } catch(_:IllegalArgumentException) {}
            assertEquals("keep this file",file.readText())
        } finally { file.delete() }
    }
}
