package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
