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
    @Test fun backsideTextureRetainsPageDetailsWithMutedColorsAndCachesPerTheme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val reader = PdfReaderView(instrumentation.targetContext)
            val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.WHITE)
            Canvas(source).drawRect(0f, 0f, 40f, 80f, android.graphics.Paint().apply { color = Color.BLACK })
            Canvas(source).drawRect(40f, 0f, 80f, 80f, android.graphics.Paint().apply { color = Color.RED })
            val texture = PdfReaderView::class.java.getDeclaredMethod("backTexture", Bitmap::class.java)
                .apply { isAccessible = true }
            try {
                val back = texture.invoke(reader, source) as Bitmap
                assertSame("Texture should be reused during animation", back, texture.invoke(reader, source))
                val ink = back.getPixel(20, 40)
                val paper = back.getPixel(100, 40)
                val colored = back.getPixel(60, 40)
                assertTrue("Dark print must stay visible, but with reduced contrast", Color.red(ink) in 40..100)
                assertTrue("Paper should be darker than the white front", Color.red(paper) in 200..245)
                assertTrue("Print and paper must remain clearly distinguishable", Color.red(paper) - Color.red(ink) > 100)
                assertTrue("Colors should be desaturated", Color.red(colored) - Color.green(colored) in 20..80)
                assertEquals("Source texture must remain untouched", Color.BLACK, source.getPixel(20, 40))
                reader.setDarkPages(true)
                val night = texture.invoke(reader, source) as Bitmap
                assertNotSame(back, night)
                assertTrue("Night paper must remain dark", Color.red(night.getPixel(100, 40)) < 40)
                assertTrue("Night print must remain visible", Color.red(night.getPixel(20, 40)) > 150)
                reader.setDarkPages(false)
                val restored = texture.invoke(reader, source) as Bitmap
                assertEquals(paper, restored.getPixel(100, 40))
            } finally {
                reader.close()
                source.recycle()
            }
        }
    }

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
            event(MotionEvent.ACTION_DOWN,0,w*.96f,h*.78f)
            event(MotionEvent.ACTION_MOVE,100,w*.70f,h*.74f)
            event(MotionEvent.ACTION_MOVE,200,w*.48f,h*.70f)
            assertEquals("Page must not commit while the finger holds the fold",0,reader.currentPage)
            val image=Bitmap.createBitmap(reader.width,reader.height,Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(image))
            var front=0;var backside=0
            for(y in image.height/4 until image.height*4/5 step 8) for(x in image.width/4 until image.width*4/5 step 8) {
                val c=image.getPixel(x,y)
                if(Color.red(c)>180 && Color.green(c)<60 && Color.blue(c)<60) front++
                if(Color.red(c)>70 && Color.green(c)>35 && Color.blue(c)>35 &&
                    Color.red(c)>=Color.green(c) && kotlin.math.abs(Color.green(c)-Color.blue(c))<25) backside++
            }
            if(backside<=30) File(context.cacheDir,"integration-curl-debug-${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
            assertTrue("Original colored front should remain visible",front>30)
            assertTrue("A separate paper backside must be visible, not a translated card",backside>30)
            event(MotionEvent.ACTION_CANCEL,300,w*.48f,h*.70f)
        }
        Thread.sleep(450)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(0,reader.currentPage)
            reader.setDarkPages(true)
            val w=reader.width.toFloat();val h=reader.height.toFloat()
            event(MotionEvent.ACTION_DOWN,400,w*.96f,h*.78f)
            event(MotionEvent.ACTION_MOVE,500,w*.70f,h*.74f)
            event(MotionEvent.ACTION_MOVE,600,w*.48f,h*.70f)
            val image=Bitmap.createBitmap(reader.width,reader.height,Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(image))
            var cyan=0
            for(y in image.height/4 until image.height*4/5 step 8) for(x in image.width/4 until image.width*4/5 step 8) {
                val pixel=image.getPixel(x,y)
                if(Color.red(pixel)<80 && Color.green(pixel)>180 && Color.blue(pixel)>180) cyan++
            }
            image.recycle()
            assertTrue("Night inversion must also apply to curved mesh pixels on older Android",cyan>30)
            event(MotionEvent.ACTION_CANCEL,700,w*.48f,h*.70f)
            reader.setDarkPages(false)
            reader.turnToPage(1)
        }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(1,reader.currentPage)
            reader.goToPage(0)
            val w=reader.width.toFloat();val h=reader.height.toFloat()
            event(MotionEvent.ACTION_DOWN,1000,w*.96f,h*.75f)
            event(MotionEvent.ACTION_MOVE,1100,w*.81f,h*.75f)
            // Simulate a coalesced event stream: no final MOVE reaches the application.
            event(MotionEvent.ACTION_UP,1500,w*.20f,h*.75f)
        }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Final release position must complete the swipe",1,reader.currentPage)
            reader.setRightToLeft(true)
            reader.goToPage(0)
            val w = reader.width.toFloat(); val h = reader.height.toFloat()
            event(MotionEvent.ACTION_DOWN,2000,w*.04f,h*.75f)
            event(MotionEvent.ACTION_MOVE,2200,w*.52f,h*.70f)
            assertEquals("RTL fold still holds the logical source page",0,reader.currentPage)
            val cornerField = PdfReaderView::class.java.getDeclaredField("curlCorner").apply { isAccessible = true }
            val corner = cornerField.get(reader) as android.graphics.PointF
            assertTrue("RTL forward must curl from the left edge", corner.x < w * .5f)
            event(MotionEvent.ACTION_UP,2500,w*.80f,h*.70f)
        }
        Thread.sleep(500)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Physical right swipe must advance RTL in document order",1,reader.currentPage)
            reader.close();activity.finish()
        }
        file.delete()
    }

    @Test fun onlyEdgesTurnSidePullsStayHorizontalAndZoomCannotLeavePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("edge-zoom-test-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            repeat(5) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(480, 720, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rendered = CountDownLatch(1)
        lateinit var reader: PdfReaderView
        val turning = PdfReaderView::class.java.getDeclaredField("turning").apply { isAccessible = true }
        val corner = PdfReaderView::class.java.getDeclaredField("curlCorner").apply { isAccessible = true }
        val finger = PdfReaderView::class.java.getDeclaredField("curlFinger").apply { isAccessible = true }
        val zoom = PdfReaderView::class.java.getDeclaredMethod("changeZoom", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
        instrumentation.runOnMainSync {
            reader = PdfReaderView(activity)
            activity.setContentView(reader)
            reader.setReadingMode(ReadingMode.SINGLE)
            reader.setFitPage(true)
            reader.onFirstPageRendered = { rendered.countDown() }
            reader.open(file, initialPage = 2)
        }
        try {
            assertTrue(rendered.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            fun drag(from: Float, to: Float, vertical: Boolean = false) {
                val start = SystemClock.uptimeMillis()
                for ((action, x, dt) in listOf(Triple(MotionEvent.ACTION_DOWN, from, 0L),
                    Triple(MotionEvent.ACTION_MOVE, to, 40L),
                    Triple(MotionEvent.ACTION_UP, to, 60L))) {
                    val event = MotionEvent.obtain(start, start + dt, action,
                        reader.width * if (vertical) .5f else x,
                        reader.height * if (vertical) x else .5f, 0)
                    reader.onTouchEvent(event)
                    event.recycle()
                }
            }
            instrumentation.runOnMainSync {
                for ((from, to) in listOf(.20f to .55f, .80f to .45f)) {
                    reader.goToPage(2)
                    val start = SystemClock.uptimeMillis()
                    for ((action, x) in listOf(MotionEvent.ACTION_DOWN to from, MotionEvent.ACTION_MOVE to to)) {
                        val event = MotionEvent.obtain(start, start + if (action == MotionEvent.ACTION_DOWN) 0 else 80,
                            action, reader.width * x, reader.height * .5f, 0)
                        reader.onTouchEvent(event); event.recycle()
                    }
                    assertTrue("A pull at the 20% screen boundary must start a fold", turning.getBoolean(reader))
                    val cancel = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_CANCEL,
                        reader.width * to, reader.height * .5f, 0)
                    reader.onTouchEvent(cancel); cancel.recycle()
                }
                reader.goToPage(2)
                drag(.21f, .8f)
                assertFalse("Left-side pulls outside the 20% zone must not turn", turning.getBoolean(reader))
                drag(.79f, .2f)
                assertFalse("Right-side pulls outside the 20% zone must not turn", turning.getBoolean(reader))
                drag(.5f, .02f)
                assertFalse("Center-origin swipe must not start a turn", turning.getBoolean(reader))
                drag(.5f, .98f)
                assertFalse("Center-origin reverse swipe must not start a turn", turning.getBoolean(reader))
                val start = SystemClock.uptimeMillis()
                for ((action, x) in listOf(MotionEvent.ACTION_DOWN to .96f, MotionEvent.ACTION_MOVE to .6f)) {
                    val event = MotionEvent.obtain(start, start + if (action == MotionEvent.ACTION_DOWN) 0 else 80,
                        action, reader.width * x, reader.height * .5f, 0)
                    reader.onTouchEvent(event); event.recycle()
                }
                assertTrue("Right-edge pull must start a fold", turning.getBoolean(reader))
                val c = corner.get(reader) as android.graphics.PointF
                val f = finger.get(reader) as android.graphics.PointF
                assertEquals("Middle-edge fold must be anchored at touch height", reader.height * .5f, c.y, 1f)
                assertEquals("Middle-edge fold must roll sideways, not from a corner", c.y, f.y, .001f)
                val cancel = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_CANCEL,
                    reader.width * .6f, reader.height * .5f, 0)
                reader.onTouchEvent(cancel); cancel.recycle()
            }
            Thread.sleep(400)
            for (mode in listOf(ReadingMode.SINGLE, ReadingMode.SPREAD, ReadingMode.BOOK, ReadingMode.CONTINUOUS)) {
                for (rtl in listOf(false, true)) {
                    instrumentation.runOnMainSync {
                        reader.resetZoom()
                        reader.setReadingMode(mode)
                        reader.setRightToLeft(rtl)
                        reader.goToPage(2)
                        zoom.invoke(reader, 2.5f, reader.width * .5f, reader.height * .5f)
                        assertEquals(2, reader.currentPage)
                        repeat(4) { drag(.96f, -2f) }
                        repeat(4) { drag(.96f, -2f, vertical = true) }
                        assertEquals("Long zoomed pan must stay on page: $mode RTL=$rtl", 2, reader.currentPage)
                    }
                    Thread.sleep(400)
                    instrumentation.runOnMainSync {
                        reader.computeScroll()
                        assertEquals("Zoomed fling must stay on page: $mode RTL=$rtl", 2, reader.currentPage)
                        repeat(4) { drag(.04f, 3f) }
                        repeat(4) { drag(.04f, 3f, vertical = true) }
                        assertEquals("Reverse zoomed pan must stay on page: $mode RTL=$rtl", 2, reader.currentPage)
                    }
                    Thread.sleep(400)
                    instrumentation.runOnMainSync {
                        reader.computeScroll()
                        assertEquals("Reverse zoomed fling must stay on page", 2, reader.currentPage)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { reader.close(); activity.finish() }
            file.delete()
        }
    }
}
