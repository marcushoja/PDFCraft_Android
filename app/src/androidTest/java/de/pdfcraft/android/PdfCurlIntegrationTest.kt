package de.pdfcraft.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfCurlIntegrationTest {
    @Test fun fingerFoldShowsBacksideAndCancelKeepsPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("curl-test-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(480,720,index+1).create())
                page.canvas.drawColor(if(index==0) Color.RED else Color.BLUE)
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rendered = CountDownLatch(1)
        lateinit var reader: PdfReaderView
        instrumentation.runOnMainSync {
            reader=PdfReaderView(activity)
            activity.setContentView(reader)
            reader.setReadingMode(ReadingMode.SINGLE)
            reader.setFitPage(true)
            reader.onFirstPageRendered={rendered.countDown()}
            reader.onError={rendered.countDown()}
            reader.open(file)
        }
        assertTrue(rendered.await(15,TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val start=SystemClock.uptimeMillis()
        fun event(action:Int,dt:Long,x:Float,y:Float) {
            val e=MotionEvent.obtain(start,start+dt,action,x,y,0)
            reader.onTouchEvent(e);e.recycle()
        }
        instrumentation.runOnMainSync {
            val w=reader.width.toFloat();val h=reader.height.toFloat()
            event(MotionEvent.ACTION_DOWN,0,w*.84f,h*.78f)
            event(MotionEvent.ACTION_MOVE,100,w*.70f,h*.74f)
            event(MotionEvent.ACTION_MOVE,200,w*.48f,h*.70f)
            assertEquals("Page must not commit while the finger holds the fold",0,reader.currentPage)
            val image=Bitmap.createBitmap(reader.width,reader.height,Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(image))
            var front=0;var backside=0
            for(y in image.height/4 until image.height*4/5 step 8) for(x in image.width/4 until image.width*4/5 step 8) {
                val c=image.getPixel(x,y)
                if(Color.red(c)>180 && Color.green(c)<60 && Color.blue(c)<60) front++
                if(Color.red(c)>140 && Color.green(c)>130 && Color.blue(c)>110) backside++
            }
            image.recycle()
            assertTrue("Original colored front should remain visible",front>30)
            assertTrue("A separate paper backside must be visible, not a translated card",backside>30)
            event(MotionEvent.ACTION_CANCEL,300,w*.48f,h*.70f)
        }
        Thread.sleep(450)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals(0,reader.currentPage);reader.turnToPage(1) }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(1,reader.currentPage)
            reader.goToPage(0)
            val w=reader.width.toFloat();val h=reader.height.toFloat()
            event(MotionEvent.ACTION_DOWN,1000,w*.84f,h*.75f)
            event(MotionEvent.ACTION_MOVE,1100,w*.81f,h*.75f)
            // Simulate a coalesced event stream: no final MOVE reaches the application.
            event(MotionEvent.ACTION_UP,1500,w*.20f,h*.75f)
        }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals("Final release position must complete the swipe",1,reader.currentPage);reader.close();activity.finish() }
        file.delete()
    }
}
