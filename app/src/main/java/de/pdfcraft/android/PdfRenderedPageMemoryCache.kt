package de.pdfcraft.android

import android.graphics.Bitmap
import android.util.LruCache

/** Reuse sharp page bitmaps across reader opens; private RAM only, never decrypted documents. */
object PdfRenderedPageMemoryCache {
    private val pages=object:LruCache<String,Bitmap>(32*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
        // A hardware Canvas may retain a bitmap after eviction: never recycle it here.
    }
    fun get(key:String):Bitmap?=pages.get(key)?.takeUnless { it.isRecycled }
    fun put(key:String,bitmap:Bitmap) { if(!bitmap.isRecycled) pages.put(key,bitmap) }
    fun clear()=pages.evictAll()
}
