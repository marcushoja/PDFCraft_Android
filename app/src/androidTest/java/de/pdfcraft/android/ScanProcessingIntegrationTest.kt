package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class ScanProcessingIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "scan-test-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() { directory.listFiles()?.forEach { it.delete() }; directory.delete() }

    private fun corners(width: Int, height: Int) = listOf(PointF(0f, 0f), PointF(width.toFloat(), 0f),
        PointF(width.toFloat(), height.toFloat()), PointF(0f, height.toFloat()))

    private fun jpeg(bitmap: Bitmap, name: String): File = File(directory, name).apply {
        outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 12) {
        assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    private fun invalid(operation: () -> Unit) {
        try { operation(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }

    @Test fun brightnessOffsetsAndContrastPresetAdjustNativeRgbAndClamp() {
        assertNull(ScanProcessing.previewFilter(ScanColorMode.COLOR, ScanTonePreset.ORIGINAL))
        assertEquals(28f, ScanTonePreset.BRIGHTER.brightness, 0f)
        assertEquals(-28f, ScanTonePreset.DARKER.brightness, 0f)
        assertEquals(1.35f, ScanTonePreset.HIGH_CONTRAST.contrast, 0f)
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(10, 110, 245))
            val expected = mapOf(
                ScanTonePreset.ORIGINAL to Color.rgb(10, 110, 245),
                ScanTonePreset.BRIGHTER to Color.rgb(38, 138, 255),
                ScanTonePreset.DARKER to Color.rgb(0, 82, 217),
                ScanTonePreset.HIGH_CONTRAST to Color.rgb(0, 104, 255)
            )
            expected.forEach { (tone, color) ->
                val result = ScanProcessing.correctPerspective(source, corners(64, 32), ScanColorMode.COLOR, tone)
                try { assertColor(color, result.getPixel(32, 16), 1) } finally { result.recycle() }
                assertEquals(Color.rgb(10, 110, 245), source.getPixel(32, 16))
            }
            source.eraseColor(Color.rgb(80, 180, 128))
            val contrast = ScanProcessing.correctPerspective(source, corners(64, 32), tone = ScanTonePreset.HIGH_CONTRAST)
            try {
                val pixel = contrast.getPixel(32, 16)
                assertTrue(Color.red(pixel) < 80)
                assertTrue(Color.green(pixel) > 180)
                assertTrue(Color.green(pixel) - Color.red(pixel) > 100)
                assertEquals(128, Color.blue(pixel))
            } finally { contrast.recycle() }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun previewAndExportShareToneAndGrayscaleMatrixWithoutChangingSource() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            val paint = Paint()
            paint.color = Color.rgb(40, 140, 240); drawRect(0f, 0f, 16f, 32f, paint)
            paint.color = Color.rgb(230, 100, 20); drawRect(16f, 0f, 32f, 32f, paint)
            paint.color = Color.rgb(10, 10, 10); drawRect(32f, 0f, 48f, 32f, paint)
            paint.color = Color.rgb(245, 245, 245); drawRect(48f, 0f, 64f, 32f, paint)
        }
        val before = IntArray(64 * 32).apply { source.getPixels(this, 0, 64, 0, 0, 64, 32) }
        try {
            ScanColorMode.values().forEach { mode ->
                ScanTonePreset.values().forEach { tone ->
                    val preview = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
                    val result = ScanProcessing.correctPerspective(source, corners(64, 32), mode, tone)
                    try {
                        Canvas(preview).drawBitmap(source, 0f, 0f, Paint().apply {
                            colorFilter = ScanProcessing.previewFilter(mode, tone)
                        })
                        listOf(8, 24, 40, 56).forEach { x ->
                            val previewPixel = preview.getPixel(x, 16)
                            if (mode != ScanColorMode.COLOR) {
                                assertEquals(Color.red(previewPixel), Color.green(previewPixel))
                                assertEquals(Color.green(previewPixel), Color.blue(previewPixel))
                            }
                            if (x == 40 && (tone == ScanTonePreset.DARKER || tone == ScanTonePreset.HIGH_CONTRAST)) {
                                assertEquals(Color.BLACK, previewPixel)
                            }
                            if (x == 56 && (tone == ScanTonePreset.BRIGHTER || tone == ScanTonePreset.HIGH_CONTRAST)) {
                                assertEquals(Color.WHITE, previewPixel)
                            }
                            val expected = if (mode == ScanColorMode.DOCUMENT) {
                                if (Color.red(previewPixel) < 170) Color.BLACK else Color.WHITE
                            } else previewPixel
                            assertColor(expected, result.getPixel(x, 16), 0)
                        }
                    } finally { preview.recycle(); result.recycle() }
                }
            }
            val after = IntArray(before.size).apply { source.getPixels(this, 0, 64, 0, 0, 64, 32) }
            assertArrayEquals(before, after)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun documentThresholdRunsAfterToneAndGrayscale() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(150, 150, 150))
        }
        try {
            ScanTonePreset.values().forEach { tone ->
                val result = ScanProcessing.correctPerspective(source, corners(64, 32), ScanColorMode.DOCUMENT, tone)
                try {
                    assertEquals(if (tone == ScanTonePreset.BRIGHTER) Color.WHITE else Color.BLACK,
                        result.getPixel(32, 16))
                } finally { result.recycle() }
            }
            assertEquals(Color.rgb(150, 150, 150), source.getPixel(32, 16))
        } finally { source.recycle() }
    }

    @Test fun trapezoidMapsColoredCornersToFlatRectangleWithoutRecyclingSource() {
        val flat = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val source = Bitmap.createBitmap(400, 450, Bitmap.Config.ARGB_8888)
        val quad = listOf(PointF(80f, 40f), PointF(340f, 80f), PointF(370f, 410f), PointF(30f, 380f))
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                val paint = Paint()
                paint.color = colors[0]; drawRect(0f, 0f, 80f, 100f, paint)
                paint.color = colors[1]; drawRect(160f, 0f, 240f, 100f, paint)
                paint.color = colors[2]; drawRect(160f, 220f, 240f, 320f, paint)
                paint.color = colors[3]; drawRect(0f, 220f, 80f, 320f, paint)
            }
            val mapping = Matrix().apply {
                assertTrue(setPolyToPoly(floatArrayOf(0f, 0f, 240f, 0f, 240f, 320f, 0f, 320f), 0,
                    quad.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0, 4))
            }
            Canvas(source).drawBitmap(flat, mapping, Paint(Paint.FILTER_BITMAP_FLAG))
            val corrected = ScanProcessing.correctPerspective(source, quad)
            try {
                assertEquals(341, corrected.width)
                assertEquals(344, corrected.height)
                val x = corrected.width / 10
                val y = corrected.height / 10
                assertColor(colors[0], corrected.getPixel(x, y))
                assertColor(colors[1], corrected.getPixel(corrected.width - x, y))
                assertColor(colors[2], corrected.getPixel(corrected.width - x, corrected.height - y))
                assertColor(colors[3], corrected.getPixel(x, corrected.height - y))
                assertFalse(source.isRecycled)
            } finally { corrected.recycle() }
        } finally { flat.recycle(); source.recycle() }
    }

    @Test fun rejectsCrossedReversedConcaveOutOfBoundsNonFiniteAndTinyCorners() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val valid = corners(100, 100)
            invalid { ScanProcessing.correctPerspective(source, listOf(valid[0], valid[2], valid[1], valid[3])) }
            invalid { ScanProcessing.correctPerspective(source, valid.reversed()) }
            invalid { ScanProcessing.correctPerspective(source, listOf(valid[0], valid[1], PointF(20f, 20f), valid[3])) }
            invalid { ScanProcessing.correctPerspective(source, listOf(PointF(-1f, 0f)) + valid.drop(1)) }
            invalid { ScanProcessing.correctPerspective(source, listOf(PointF(Float.NaN, 0f)) + valid.drop(1)) }
            invalid { ScanProcessing.correctPerspective(source, listOf(PointF(Float.POSITIVE_INFINITY, 0f)) + valid.drop(1)) }
            invalid { ScanProcessing.correctPerspective(source, corners(10, 10)) }
            invalid { ScanProcessing.correctPerspective(source, valid.take(3)) }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun colorGrayscaleAndDocumentModesAreIndependentAndBounded() {
        val source = Bitmap.createBitmap(2400, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        try {
            ScanColorMode.values().forEach { mode ->
                val result = ScanProcessing.correctPerspective(source, corners(source.width, source.height), mode)
                try {
                    assertEquals(2200, result.width)
                    assertTrue(result.height in 16..2200)
                    val pixel = result.getPixel(result.width / 2, result.height / 2)
                    when (mode) {
                        ScanColorMode.COLOR -> assertColor(Color.RED, pixel)
                        ScanColorMode.GRAYSCALE -> {
                            assertEquals(Color.red(pixel), Color.green(pixel))
                            assertEquals(Color.green(pixel), Color.blue(pixel))
                            assertTrue(Color.red(pixel) in 1..254)
                        }
                        ScanColorMode.DOCUMENT -> assertEquals(Color.BLACK, pixel)
                    }
                } finally { result.recycle() }
            }
            source.eraseColor(Color.WHITE)
            val document = ScanProcessing.correctPerspective(source, corners(source.width, source.height), ScanColorMode.DOCUMENT)
            try { assertEquals(Color.WHITE, document.getPixel(100, 20)) } finally { document.recycle() }
            val narrow = ScanProcessing.correctPerspective(source, corners(4, 100))
            try {
                assertEquals(16, narrow.width)
                assertEquals(100, narrow.height)
            } finally { narrow.recycle() }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun loadsEveryExifOrientationIncludingMirrorsAndDownsamples() {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        Canvas(source).apply {
            val paint = Paint()
            paint.color = colors[0]; drawRect(0f, 0f, 60f, 40f, paint)
            paint.color = colors[1]; drawRect(60f, 0f, 120f, 40f, paint)
            paint.color = colors[2]; drawRect(60f, 40f, 120f, 80f, paint)
            paint.color = colors[3]; drawRect(0f, 40f, 60f, 80f, paint)
        }
        // Expected output TL/TR/BR/BL source quadrant indices for EXIF values 1..8.
        val orders = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(2, 3, 0, 1),
            listOf(3, 2, 1, 0), listOf(0, 3, 2, 1), listOf(3, 0, 1, 2),
            listOf(2, 1, 0, 3), listOf(1, 2, 3, 0))
        try {
            orders.forEachIndexed { index, order ->
                val file = jpeg(source, "orientation-$index.jpg")
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, (index + 1).toString())
                    saveAttributes()
                }
                val loaded = ScanProcessing.loadPhoto(file)
                try {
                    assertEquals(if (index < 4) 120 else 80, loaded.width)
                    assertEquals(if (index < 4) 80 else 120, loaded.height)
                    val x = loaded.width / 4
                    val y = loaded.height / 4
                    listOf(x to y, 3 * x to y, 3 * x to 3 * y, x to 3 * y).forEachIndexed { corner, (px, py) ->
                        assertColor(colors[order[corner]], loaded.getPixel(px, py), 25)
                    }
                } finally { loaded.recycle() }
                val small = ScanProcessing.loadPhoto(file, 32)
                try { assertTrue(small.width <= 32 && small.height <= 32) } finally { small.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun nativePdfRendererConfirmsPageOrderA4AndCenteredWhiteMargins() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val pages = colors.mapIndexed { index, color ->
            val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { jpeg(bitmap, "page-$index.jpg") } finally { bitmap.recycle() }
        }
        val output = File(directory, "scan.pdf")
        output.writeText("old output to replace atomically")
        val originals = pages.map { it.readBytes() }
        ScanProcessing.writePdf(pages, output)
        pages.forEachIndexed { index, file -> assertArrayEquals(originals[index], file.readBytes()) }
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(3, renderer.pageCount)
                colors.forEachIndexed { index, color ->
                    renderer.openPage(index).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertColor(color, bitmap.getPixel(297, 421), 25)
                            assertColor(Color.WHITE, bitmap.getPixel(297, 20))
                            assertColor(Color.WHITE, bitmap.getPixel(297, 820))
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
        assertEquals(listOf("page-0.jpg", "page-1.jpg", "page-2.jpg", "scan.pdf"), directory.list()!!.sorted())
    }

    @Test fun cancellationAndDecodeFailureLeaveNoPartialOutputOrStagedFiles() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val photo = try { jpeg(bitmap, "page.jpg") } finally { bitmap.recycle() }
        val output = File(directory, "scan.pdf")
        val before = directory.list()!!.toSet()
        var checks = 0
        try {
            ScanProcessing.writePdf(listOf(photo, photo), output) { ++checks >= 4 }
            fail("Expected cancellation during page processing")
        } catch (_: CancellationException) { }
        assertFalse(output.exists())
        assertEquals(before, directory.list()!!.toSet())
        output.writeText("previous output")
        val original = output.readBytes()
        try {
            ScanProcessing.writePdf(listOf(photo), output) {
                directory.listFiles()!!.any { it.name.startsWith(".scan-pdf-") && it.length() > 0 }
            }
            fail("Expected cancellation during PDF serialization")
        } catch (_: CancellationException) { }
        assertArrayEquals(original, output.readBytes())
        val bad = File(directory, "bad.jpg").apply { writeText("not an image") }
        try {
            ScanProcessing.writePdf(listOf(photo, bad), output)
            fail("Expected invalid image failure")
        } catch (_: IOException) { }
        assertArrayEquals(original, output.readBytes())
        assertFalse(directory.list()!!.any { it.startsWith(".scan-pdf-") })
        invalid { ScanProcessing.writePdf(emptyList(), output) }
        invalid { ScanProcessing.writePdf(List(101) { photo }, output) }
        invalid { ScanProcessing.writePdf(listOf(photo), photo) }
        invalid { ScanProcessing.loadPhoto(photo, 0) }
    }
}
