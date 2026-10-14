package de.pdfcraft.android

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Copy a provider image privately before editing; never change the user's original. */
object ScanImageImport {
    fun copy(context:Context,uri:Uri,output:File,isCancelled:()->Boolean={false}) {
        require(!output.exists())
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                output.outputStream().use { target ->
                    val buffer=ByteArray(64*1024);var bytes=0L
                    while(true) {
                        if(isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
                        val size=source.read(buffer);if(size<0) break
                        bytes+=size;if(bytes>64L*1024*1024) throw IOException("Image exceeds 64 MiB")
                        target.write(buffer,0,size)
                    }
                    if(bytes==0L) throw IOException("Empty image")
                    target.fd.sync()
                }
            } ?: throw IOException("Image cannot be opened")
            if(isCancelled()) throw CancellationException()
            ScanProcessing.loadPhoto(output,160).recycle()
        } catch(error:Throwable) { output.delete();throw error }
    }
}
