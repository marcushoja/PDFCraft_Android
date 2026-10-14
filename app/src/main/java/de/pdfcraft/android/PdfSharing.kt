package de.pdfcraft.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Expose the document's user-facing name without copying large private PDFs. */
object PdfSharing {
    fun intent(context:Context,file:File,displayName:String,mime:String="application/pdf"):Intent {
        val name=displayName.ifBlank { file.name }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file,name)
        return Intent(Intent.ACTION_SEND).apply {
            type=mime
            putExtra(Intent.EXTRA_STREAM,uri)
            putExtra(Intent.EXTRA_TITLE,name)
            putExtra(Intent.EXTRA_SUBJECT,name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri(name,uri)
        }
    }
}
