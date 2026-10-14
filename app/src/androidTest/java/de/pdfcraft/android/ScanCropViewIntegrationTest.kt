package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanCropViewIntegrationTest {
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
