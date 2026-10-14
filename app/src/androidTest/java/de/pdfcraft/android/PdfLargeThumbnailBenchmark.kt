package de.pdfcraft.android

import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfLargeThumbnailBenchmark {
    @Test fun eightImageThumbnailsFrom150MiBPdfReuseOneNativeDocument() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val source=File(context.cacheDir,"images-150mb.pdf")
        assumeTrue(source.length()>140L*1024*1024)
        val cache=File(context.cacheDir,"large-thumb-benchmark-${System.nanoTime()}").apply { check(mkdirs()) }
        val isolated=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir():File=cache
        }
        val loader=ThumbnailLoader(isolated);val cells=mutableListOf<ImageView>()
        val start=SystemClock.elapsedRealtime();var firstMs=0L
        try {
            i.runOnMainSync {
                repeat(8) { index ->
                    val image=object:ImageView(context) {
                        override fun setImageBitmap(bitmap:Bitmap?) {
                            super.setImageBitmap(bitmap)
                            if(bitmap!=null && firstMs==0L) firstMs=SystemClock.elapsedRealtime()-start
                        }
                    }
                    cells.add(image);loader.load(source,index,256,image,10)
                }
            }
            assertTrue(loader.awaitIdle(60,TimeUnit.SECONDS));i.waitForIdleSync()
            i.runOnMainSync { cells.forEach { assertNotNull(it.drawable) } }
            assertEquals(1L,loader.stats().rendererOpens)
            println("PDF_THUMB_BENCH bytes=${source.length()} first_ms=$firstMs eight_ms=${SystemClock.elapsedRealtime()-start} opens=${loader.stats().rendererOpens} renders=${loader.stats().renderedPages}")
            loader.close();assertTrue(loader.awaitClosed(30,TimeUnit.SECONDS))
            val warm=ThumbnailLoader(isolated);val warmStart=SystemClock.elapsedRealtime()
            try {
                i.runOnMainSync { cells.forEachIndexed { index,image -> warm.load(source,index,256,image,10) } }
                assertTrue(warm.awaitIdle(30,TimeUnit.SECONDS));i.waitForIdleSync()
                i.runOnMainSync { cells.forEach { assertNotNull(it.drawable) } }
                assertEquals(0L,warm.stats().rendererOpens);assertEquals(8L,warm.stats().cacheHits)
                println("PDF_THUMB_BENCH warm_eight_ms=${SystemClock.elapsedRealtime()-warmStart} opens=${warm.stats().rendererOpens} cache_hits=${warm.stats().cacheHits}")
            } finally { warm.close();assertTrue(warm.awaitClosed(30,TimeUnit.SECONDS)) }
        } finally { loader.close();assertTrue(loader.awaitClosed(30,TimeUnit.SECONDS));cache.deleteRecursively() }
    }
}
