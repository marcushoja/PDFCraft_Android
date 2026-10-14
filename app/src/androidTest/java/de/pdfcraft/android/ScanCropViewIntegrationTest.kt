package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanCropViewIntegrationTest {
    @Test fun releaseCoordinatesAreAppliedWhenMovesAreCoalesced() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val density=i.targetContext.resources.displayMetrics.density
            val size=(400*density).toInt();val padding=24f*density;val span=size-2*padding
            val photo=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
            try {
                val view=ScanCropView(i.targetContext);view.layout(0,0,size,size);view.setPhoto(photo)
                assertTrue(touch(view,MotionEvent.ACTION_DOWN,padding,padding))
                assertNotNull(view.magnifierBounds())
                assertTrue(touch(view,MotionEvent.ACTION_UP,padding+.12f*span,padding+.10f*span))
                assertEquals(.12f,view.corners()[0].x,.001f);assertEquals(.10f,view.corners()[0].y,.001f)
                assertNull(view.magnifierBounds())
            } finally { photo.recycle() }
        }
    }
    private fun touch(view: ScanCropView, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 1, action, x, y, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun palette(x: Int, y: Int): Int = Color.rgb(30 + (x / 20) * 9, 25 + (y / 20) * 10, 60)

    private fun assertColor(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toFloat(), Color.red(actual).toFloat(), 2f)
        assertEquals(Color.green(expected).toFloat(), Color.green(actual).toFloat(), 2f)
        assertEquals(Color.blue(expected).toFloat(), Color.blue(actual).toFloat(), 2f)
    }

    @Test fun magnifierShowsLocalSourcePixelsCrosshairAndCurrentAppearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val size = (400 * density).toInt()
            val padding = 24f * density
            val span = size - 2f * padding
            val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until 400) for (x in 0 until 400) source.setPixel(x, y, palette(x, y))
                val view = ScanCropView(context)
                view.layout(0, 0, size, size)
                view.setPhoto(source, ScanCropView.defaultCorners().toMutableList().apply {
                    this[0] = PointF(.26f, .26f)
                })
                assertNull(view.magnifierBounds())
                assertTrue(touch(view, MotionEvent.ACTION_DOWN, padding + .26f * span, padding + .26f * span))
                fun verify(brighter: Boolean = false) {
                    val bounds = requireNotNull(view.magnifierBounds())
                    assertEquals(112f * density, bounds.width(), .01f)
                    view.draw(Canvas(rendered))
                    assertColor(view.accentColor, rendered.getPixel(bounds.centerX().toInt(), bounds.centerY().toInt()))
                    // Two different local palette tiles: a whole-photo fit cannot pass these samples.
                    for (dx in listOf(24f, 44f)) {
                        val x = (bounds.centerX() + dx * density).toInt()
                        val y = (bounds.centerY() + 12f * density).toInt()
                        val sourceX = (.26f * 400 + (x + .5f - bounds.centerX()) * 400 / (3f * span)).toInt()
                        val sourceY = (.26f * 400 + (y + .5f - bounds.centerY()) * 400 / (3f * span)).toInt()
                        val color = palette(sourceX, sourceY)
                        val expected = if (brighter) Color.rgb(Color.red(color) + 28, Color.green(color) + 28, Color.blue(color) + 28) else color
                        assertColor(expected, rendered.getPixel(x, y))
                    }
                }
                verify()
                val snapshot = requireNotNull(view.magnifierBounds())
                snapshot.setEmpty()
                assertFalse(requireNotNull(view.magnifierBounds()).isEmpty)
                // A rejected crossing move must magnify the accepted corner, not the finger.
                assertTrue(touch(view, MotionEvent.ACTION_MOVE, size.toFloat(), size.toFloat()))
                assertEquals(.26f, view.corners()[0].x, .001f)
                verify()
                view.setAppearance(ScanColorMode.COLOR, ScanTonePreset.BRIGHTER)
                verify(brighter = true)
                val lastBounds = requireNotNull(view.magnifierBounds())
                assertTrue(touch(view, MotionEvent.ACTION_UP, size.toFloat(), size.toFloat()))
                assertNull(view.magnifierBounds())
                rendered.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(rendered))
                assertNotEquals(view.accentColor, rendered.getPixel(lastBounds.centerX().toInt(), lastBounds.centerY().toInt()))
            } finally {
                rendered.recycle()
                source.recycle()
            }
        }
    }

    @Test fun allFourCornersKeepMagnifierAtOppositeTopRegardlessOfFingerPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val size = (400 * density).toInt()
            val padding = 24f * density
            val span = size - 2f * padding
            val margin = 12f * density
            val diameter = 112f * density
            val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            try {
                val view = ScanCropView(context)
                view.layout(0, 0, size, size)
                val corners = ScanCropView.defaultCorners()
                corners.forEachIndexed { index, point ->
                    view.setPhoto(source)
                    assertTrue(touch(view, MotionEvent.ACTION_DOWN, padding + point.x * span, padding + point.y * span))
                    val expectedLeft = if (index == 0 || index == 3) size - margin - diameter else margin
                    fun verify() {
                        val bounds = requireNotNull(view.magnifierBounds())
                        assertEquals(expectedLeft, bounds.left, .01f)
                        assertEquals(margin, bounds.top, .01f)
                        assertEquals(diameter, bounds.width(), .01f)
                        assertEquals(diameter, bounds.height(), .01f)
                    }
                    verify()
                    // Moving across either axis (including rejected moves) never switches sides or rows.
                    for (finger in corners + PointF(.5f, .5f)) {
                        assertTrue(touch(view, MotionEvent.ACTION_MOVE, padding + finger.x * span, padding + finger.y * span))
                        verify()
                    }
                    assertTrue(touch(view, MotionEvent.ACTION_CANCEL, padding, padding))
                    assertNull(view.magnifierBounds())
                }
            } finally { source.recycle() }
        }
    }

    @Test fun magnifierAtAllImageEdgesUsesWhiteOutsideAndHidesAfterCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val size = (400 * density).toInt()
            val padding = 24f * density
            val span = size - 2f * padding
            val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(45, 90, 135)) }
            val rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val view = ScanCropView(context)
                view.layout(0, 0, size, size)
                view.setPhoto(source)
                for (point in ScanCropView.defaultCorners()) {
                    val x = padding + point.x * span
                    val y = padding + point.y * span
                    assertTrue(touch(view, MotionEvent.ACTION_DOWN, x, y))
                    val bounds = requireNotNull(view.magnifierBounds())
                    assertFalse(bounds.contains(x, y))
                    assertTrue(bounds.left >= 12f * density - .01f)
                    assertTrue(bounds.right <= size - 12f * density + .01f)
                    assertEquals(12f * density, bounds.top, .01f)
                    view.draw(Canvas(rendered))
                    val dx = if (point.x == 0f) 12f * density else -12f * density
                    val dy = if (point.y == 0f) 12f * density else -12f * density
                    assertColor(Color.rgb(45, 90, 135), rendered.getPixel((bounds.centerX() + dx).toInt(), (bounds.centerY() + dy).toInt()))
                    assertColor(Color.WHITE, rendered.getPixel((bounds.centerX() - dx).toInt(), (bounds.centerY() - dy).toInt()))
                    assertTrue(touch(view, MotionEvent.ACTION_MOVE, x, y))
                    assertNotNull(view.magnifierBounds())
                    assertTrue(touch(view, MotionEvent.ACTION_CANCEL, x, y))
                    assertNull(view.magnifierBounds())
                }
            } finally {
                rendered.recycle()
                source.recycle()
            }
        }
    }

    @Test fun magnifierClearsOnPhotoReplacementDisableAndDetachAndFitsSmallViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                val view = ScanCropView(context)
                val size = (80 * density).toInt()
                view.layout(0, 0, size, size)
                view.setPhoto(source)
                fun start(): RectF {
                    assertTrue(touch(view, MotionEvent.ACTION_DOWN, 24f * density, 24f * density))
                    return requireNotNull(view.magnifierBounds())
                }
                val bounds = start()
                assertTrue(bounds.width() < 112f * density)
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= size && bounds.bottom <= size)
                view.setPhoto(source)
                assertNull(view.magnifierBounds())
                start()
                view.editable = false
                assertNull(view.magnifierBounds())
                view.editable = true
                assertNull(view.magnifierBounds())
                start()
                ScanCropView::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }.invoke(view)
                assertNull(view.magnifierBounds())
                start()
                view.setPhoto(null)
                assertNull(view.magnifierBounds())
                assertFalse(source.isRecycled)
            } finally { source.recycle() }
        }
    }

    @Test fun cornersRejectCrossingCollapsedAndNonFiniteGeometry() {
        assertTrue(ScanCropView.validCorners(ScanCropView.defaultCorners()))
        assertFalse(ScanCropView.validCorners(listOf(PointF(0f, 0f), PointF(1f, 1f), PointF(1f, 0f), PointF(0f, 1f))))
        assertFalse(ScanCropView.validCorners(List(4) { PointF(.5f, .5f) }))
        assertFalse(ScanCropView.validCorners(ScanCropView.defaultCorners().toMutableList().apply { this[0] = PointF(Float.NaN, 0f) }))
        assertFalse(ScanCropView.validCorners(ScanCropView.defaultCorners().toMutableList().apply { this[0] = PointF(-.1f, 0f) }))
    }

    @Test fun draggingUpdatesNormalizedCornersAndRejectsCrossing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val size = (400 * density).toInt()
            val padding = 24 * density
            val span = size - 2 * padding
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val view = ScanCropView(context)
            view.layout(0, 0, size, size)
            view.setPhoto(bitmap)
            var changes = 0
            view.onCornersChanged = { changes++ }
            fun touch(action: Int, x: Float, y: Float): Boolean {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                return try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            assertTrue(touch(MotionEvent.ACTION_DOWN, padding, padding))
            assertTrue(touch(MotionEvent.ACTION_MOVE, padding + .1f * span, padding + .15f * span))
            assertEquals(.1f, view.corners()[0].x, .001f)
            assertEquals(.15f, view.corners()[0].y, .001f)
            assertEquals(1, changes)
            touch(MotionEvent.ACTION_MOVE, padding + span, padding + span)
            assertEquals(.1f, view.corners()[0].x, .001f)
            assertEquals(1, changes)
            assertTrue(touch(MotionEvent.ACTION_UP, padding + span, padding + span))
            val snapshot = view.corners()
            snapshot[0].x = .9f
            assertEquals(.1f, view.corners()[0].x, .001f)
            view.editable = false
            assertFalse(touch(MotionEvent.ACTION_DOWN, padding, padding))
            view.setPhoto(null)
            // The view never recycles caller-owned/displayed bitmaps.
            assertFalse(bitmap.isRecycled)
            bitmap.recycle()
        }
    }
}
