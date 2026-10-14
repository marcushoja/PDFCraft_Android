package de.pdfcraft.android

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

/** Inline copies of the real local toolbar icons, with a readable screen-reader fallback. */
object ScanIntroText {
    private val icons=mapOf("camera" to R.string.scan_take_photo,"image" to R.string.scan_choose_image,
        "check" to R.string.scan_accept,"scan_export" to R.string.scan_export_action)
    fun text(context:Context,color:Int):CharSequence {
        val result=SpannableStringBuilder(context.getString(R.string.scan_empty))
        val size=(18*context.resources.displayMetrics.density*context.resources.configuration.fontScale).toInt().coerceAtLeast(1)
        for((key,_) in icons) {
            val marker="{$key}"
            var index=result.indexOf(marker)
            while(index>=0) {
                val drawable=requireNotNull(MaterialSymbols.drawable(context,key,color)).apply { setBounds(0,0,size,size) }
                result.replace(index,index+marker.length,"\uFFFC")
                result.setSpan(ImageSpan(drawable,ImageSpan.ALIGN_BASELINE),index,index+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                index=result.indexOf(marker)
            }
        }
        return result
    }
    fun description(context:Context):String {
        var text=context.getString(R.string.scan_empty)
        icons.forEach { (key,label) -> text=text.replace("{$key}",context.getString(label)) }
        return text
    }
}
