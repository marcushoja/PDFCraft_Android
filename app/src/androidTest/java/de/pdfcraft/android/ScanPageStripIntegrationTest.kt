package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScanPageStripIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun pages() = List(3) { ScanStripPage("page-$it", File(context.cacheDir, "missing-strip-$it.jpg")) }
    private fun deleteButton(strip: ScanPageStrip, index: Int): ImageButton {
        val item = strip.row.getChildAt(index) as FrameLayout
        return (0 until item.childCount).map { item.getChildAt(it) }.filterIsInstance<ImageButton>().single()
    }

    @Test fun selectionAndDeleteAddressClickedPageNotSelectedPage() {
        instrumentation.runOnMainSync {
            val strip = ScanPageStrip(context, false)
            var selected = -1
            var deleted = -1
            strip.bind(pages(), 2, onSelect = { selected = it }, onDelete = { deleted = it }, onMove = { _, _ -> fail("Unexpected move") })
            assertSame(strip.row, strip.findViewById<LinearLayout>(R.id.scan_strip))
            strip.row.getChildAt(1).performClick()
            assertEquals(1, selected)
            deleteButton(strip, 0).performClick()
            assertEquals(0, deleted)
            assertEquals(context.getString(R.string.scan_delete_page_number, 1), deleteButton(strip, 0).contentDescription)
            assertEquals(3, strip.row.childCount)
            assertTrue(strip.row.getChildAt(2).isSelected)
            assertEquals(View.LAYOUT_DIRECTION_LTR, strip.row.layoutDirection)
        }
    }

    @Test fun disabledStripRejectsSelectionDeletionAndDragStart() {
        instrumentation.runOnMainSync {
            val strip = ScanPageStrip(context, true)
            strip.bind(pages(), 1, enabled = false,
                onSelect = { fail("Disabled selection") }, onDelete = { fail("Disabled deletion") },
                onMove = { _, _ -> fail("Disabled reorder") })
            for (index in 0 until strip.row.childCount) {
                val item = strip.row.getChildAt(index)
                assertFalse(item.isEnabled)
                assertFalse(deleteButton(strip, index).isEnabled)
                // performClick bypasses touch dispatch, so callback guards must still hold.
                item.performClick()
                deleteButton(strip, index).performClick()
                assertFalse(item.performLongClick())
            }
        }
    }

    @Test fun deleteVisualIsSmallInsideAccessibleFortyDpTarget() {
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val strip = ScanPageStrip(context, false)
            strip.bind(pages(), 0, onSelect = {}, onDelete = {}, onMove = { _, _ -> })
            val button = deleteButton(strip, 0)
            assertEquals(dp(40), button.layoutParams.width)
            assertEquals(dp(40), button.layoutParams.height)
            assertEquals(dp(11), button.paddingLeft)
            assertEquals(dp(11), button.paddingTop)
            assertEquals(dp(11), button.paddingRight)
            assertEquals(dp(11), button.paddingBottom)
            assertTrue(button.isClickable)
            assertFalse(button.contentDescription.isNullOrEmpty())
            button.measure(View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY))
            button.layout(0, 0, dp(40), dp(40))
            assertEquals(dp(40) - 2 * dp(11), button.width - button.paddingLeft - button.paddingRight)
            val rendered = Bitmap.createBitmap(dp(40), dp(40), Bitmap.Config.ARGB_8888)
            try {
                button.draw(Canvas(rendered))
                val circle = (button.background as InsetDrawable).drawable as GradientDrawable
                assertEquals(dp(8), circle.bounds.left)
                assertEquals(dp(8), circle.bounds.top)
                assertEquals(dp(40) - 2 * dp(8), circle.bounds.width())
                assertEquals(dp(40) - 2 * dp(8), circle.bounds.height())
                assertEquals(dp(12).toFloat(), circle.cornerRadius, .01f)
                assertEquals(0xcc, Color.alpha(requireNotNull(circle.color).defaultColor))
                assertEquals(Color.TRANSPARENT, rendered.getPixel(0, 0))
            } finally { rendered.recycle() }
        }
    }

    @Test fun footerShowsDragIndicatorWithoutInterceptingItemTouches() {
        val activity=instrumentation.startActivitySync(android.content.Intent(context,MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var selected=-1
        try {
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val strip = ScanPageStrip(context, false)
            strip.bind(pages(), 0, onSelect = { selected = it }, onDelete = {}, onMove = { _, _ -> })
            activity.setContentView(strip)
            val item = strip.row.getChildAt(1) as FrameLayout
            val footer = (0 until item.childCount).map { item.getChildAt(it) }.filterIsInstance<LinearLayout>().single()
            assertEquals(LinearLayout.HORIZONTAL, footer.orientation)
            assertEquals(android.view.Gravity.CENTER, footer.gravity)
            assertEquals(dp(24), footer.layoutParams.height)
            assertEquals(2, footer.childCount)
            val label = footer.getChildAt(0) as TextView
            val indicator = footer.getChildAt(1) as ImageView
            assertEquals(context.getString(R.string.scan_page, 2), label.text.toString())
            assertNotNull(indicator.drawable)
            val params = indicator.layoutParams as LinearLayout.LayoutParams
            assertEquals(dp(18), params.width)
            assertEquals(dp(18), params.height)
            assertEquals(dp(3), params.leftMargin)
            for (child in listOf(footer, label, indicator)) {
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, child.importantForAccessibility)
                assertFalse(child.isClickable)
                assertFalse(child.isLongClickable)
                assertFalse(child.isFocusable)
            }
            assertTrue(item.isLongClickable)
            assertTrue(item.contentDescription.toString().contains(context.getString(R.string.scan_reorder_hint)))
            item.measure(View.MeasureSpec.makeMeasureSpec(dp(80), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(96), View.MeasureSpec.EXACTLY))
            item.layout(0, 0, dp(80), dp(96))
            val x = footer.left + indicator.left + indicator.width / 2f
            val y = footer.top + indicator.top + indicator.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                try { assertTrue(item.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
        assertEquals(1,selected)
        } finally { instrumentation.runOnMainSync { activity.finish() };instrumentation.waitForIdleSync() }
    }

    @Test fun dropResolverUsesCurrentOrderAndClampsBeyondLastPage() {
        val pages = pages()
        assertEquals(0 to 2, ScanPageStrip.resolveMove(pages, "page-0", 99))
        assertEquals(2 to 0, ScanPageStrip.resolveMove(pages, "page-2", -5))
        assertNull(ScanPageStrip.resolveMove(pages, "page-1", 1))
        assertNull(ScanPageStrip.resolveMove(pages, "external", 1))
        assertNull(ScanPageStrip.resolveMove(emptyList(), "page-0", 0))
        assertEquals(2 to 0, ScanPageStrip.resolveMove(pages.reversed(), "page-0", 0))
    }

    @Test fun onlySmallThumbnailBuffersAreDecoded() {
        val tiny = File.createTempFile("strip-tiny-", ".jpg", context.cacheDir)
        val large = File.createTempFile("strip-large-", ".jpg", context.cacheDir)
        fun write(file: File, size: Int) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            write(tiny, 160)
            write(large, 161)
            instrumentation.runOnMainSync {
                val strip = ScanPageStrip(context, false)
                strip.bind(listOf(ScanStripPage("tiny", tiny), ScanStripPage("large", large)), 0,
                    onSelect = {}, onDelete = {}, onMove = { _, _ -> })
                fun image(index: Int) = (strip.row.getChildAt(index) as FrameLayout).getChildAt(0) as ImageView
                assertEquals(160, (image(0).drawable as BitmapDrawable).bitmap.width)
                assertNull(image(1).drawable)
                assertTrue(tiny.exists())
                assertTrue(large.exists())
            }
        } finally {
            tiny.delete()
            large.delete()
        }
    }
}
