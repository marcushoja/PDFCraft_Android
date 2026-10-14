package de.pdfcraft.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Established project link from README; no document, account or device data is attached. */
object ProjectSupport {
    const val URL="https://buy.stripe.com/5kQ00c1yP9PQfDP6vV9bO04?client_reference_id=pdfcraft_android"
    fun intent()=Intent(Intent.ACTION_VIEW,Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE)
}

object SupportDialog {
    fun create(context:Context,onSupport:()->Unit={
        try {
            context.startActivity(ProjectSupport.intent().apply { if(context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch(_:android.content.ActivityNotFoundException) {
            Toast.makeText(context,R.string.support_browser_error,Toast.LENGTH_LONG).show()
        }
    }):AlertDialog=AlertDialog.Builder(context)
        .setTitle(R.string.support_title)
        .setIcon(MaterialSymbols.drawable(context,"support",0xff4f7d53.toInt()))
        .setMessage(R.string.support_message)
        .setPositiveButton(R.string.support_action) { _,_ -> onSupport() }
        .setNegativeButton(R.string.support_close,null)
        .create()
}
