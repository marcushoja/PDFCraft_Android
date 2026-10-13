package de.pdfcraft.android

import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional real 150MiB image fixture; not a claim about every PDF/device. */
@RunWith(AndroidJUnit4::class)
class PdfLargeLoadBenchmark {
    @Test fun largeImagePdfFirstPageAndWarmReopen() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val source=File(context.cacheDir,"images-150mb.pdf")
        assumeTrue("Install synthetic large-image fixture to benchmark",source.length()>140L*1024*1024)
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            repeat(2) { pass ->
                val first=CountDownLatch(1);val annotations=CountDownLatch(1)
                var metadataMs=0L;var firstMs=0L;var annotationsMs=0L
                lateinit var reader:PdfReaderView
                val start=SystemClock.elapsedRealtime()
                i.runOnMainSync {
                    reader=PdfReaderView(activity);activity.setContentView(reader)
                    reader.onDocumentLoaded={metadataMs=SystemClock.elapsedRealtime()-start}
                    reader.onFirstPageRendered={firstMs=SystemClock.elapsedRealtime()-start;first.countDown()}
                    reader.onAnnotationsPrepared={annotationsMs=SystemClock.elapsedRealtime()-start;annotations.countDown()}
                    reader.open(source)
                }
                assertTrue("150MiB PDF should render without waiting for annotation inspection",first.await(60,TimeUnit.SECONDS))
                assertTrue(annotations.await(90,TimeUnit.SECONDS))
                assertTrue("Native page must be available before background annotation preparation",firstMs<=annotationsMs)
                println("PDF_LARGE_BENCH pass=$pass bytes=${source.length()} metadata_ms=$metadataMs first_page_ms=$firstMs annotation_ready_ms=$annotationsMs pss_kb=${Debug.getPss()}")
                i.runOnMainSync { reader.close() }
                i.waitForIdleSync()
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }
}
