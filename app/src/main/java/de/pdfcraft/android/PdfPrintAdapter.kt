package de.pdfcraft.android

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class PdfPrintAdapter(private val context: Context, private val file: File, private val name: String, private val count: Int) : PrintDocumentAdapter() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal.isCanceled) callback.onLayoutCancelled()
        else callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(), true)
    }
    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        executor.execute {
            try {
                PDDocument.load(file).use { require(it.currentAccessPermission.canPrint()) { "Der Autor erlaubt das Drucken dieser PDF nicht." } }
                file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { output ->
                    val bytes = ByteArray(65536)
                    while (!cancellationSignal.isCanceled) {
                        val size = input.read(bytes)
                        if (size < 0) break
                        output.write(bytes, 0, size)
                    }
                } }
                if (cancellationSignal.isCanceled) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (error: Exception) { callback.onWriteFailed(UiText.t(context,error.message ?: "Drucken fehlgeschlagen")) }
        }
    }
    override fun onFinish() { executor.shutdown(); super.onFinish() }
}
