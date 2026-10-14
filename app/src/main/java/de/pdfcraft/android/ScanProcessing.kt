package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.ExifInterface
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanColorMode { COLOR, GRAYSCALE, DOCUMENT }

enum class ScanTonePreset(val brightness: Float, val contrast: Float) {
    ORIGINAL(0f, 1f),
    BRIGHTER(28f, 1f),
    DARKER(-28f, 1f),
    HIGH_CONTRAST(0f, 1.35f)
}

/** Blocking, entirely local operations. Use a worker thread; callers own returned bitmaps/files. */
object ScanProcessing {
    private const val MAX_EDGE = 2200
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_PDF_BYTES = 500L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 200_000_000L

    /** Decode with bounded memory, then apply all eight EXIF orientations (including reflections). */
    fun loadPhoto(file: File, maxEdge: Int = 2200): Bitmap {
        require(maxEdge in 16..MAX_EDGE) { "Photo edge limit must be between 16 and 2200 pixels" }
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) {
            throw IOException("Photo is missing, empty, or exceeds the 64 MiB limit")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) {
            throw IOException("Photo dimensions are invalid or exceed the 200 megapixel limit")
        }
        var sample = 1
        while (ceil(max(bounds.outWidth, bounds.outHeight).toDouble() / sample) > maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Photo could not be decoded")
        try {
            val orientation = try {
                ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) {
                // Some decodable image formats have no EXIF support.
                ExifInterface.ORIENTATION_NORMAL
            }
            val values = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
                ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
                else -> null
            }
            if (values == null) return decoded
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { setValues(values) }, true)
            if (result !== decoded) decoded.recycle()
            return result
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
    }

    /** Shared linear preview/export filter; native rendering clamps RGB to 0..255 and preserves alpha.
     * DOCUMENT previews grayscale plus tone; its additional black/white threshold is export-only.
     */
    fun previewFilter(mode: ScanColorMode, tone: ScanTonePreset, binaryPreview:Boolean=false): ColorMatrixColorFilter? {
        if (mode == ScanColorMode.COLOR && tone == ScanTonePreset.ORIGINAL) return null
        val contrast = tone.contrast
        val offset = tone.brightness + 128f * (1f - contrast)
        val adjustment = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        val matrix = ColorMatrix().apply {
            if (mode != ScanColorMode.COLOR) setSaturation(0f)
            // Apply grayscale first, then contrast around mid-gray and the RGB brightness offset.
            postConcat(adjustment)
            if(binaryPreview && mode==ScanColorMode.DOCUMENT) {
                // Display a near-step threshold; export still uses the exact pixel threshold.
                val gain=256f;val bias=-169f*gain
                postConcat(ColorMatrix(floatArrayOf(gain,0f,0f,0f,bias,0f,gain,0f,0f,bias,
                    0f,0f,gain,0f,bias,0f,0f,0f,1f,0f)))
            }
        }
        return ColorMatrixColorFilter(matrix)
    }

    /** Corners are source pixel coordinates, clockwise TL/TR/BR/BL; image outer edges are allowed. */
    fun correctPerspective(
        source: Bitmap,
        corners: List<PointF>,
        mode: ScanColorMode = ScanColorMode.COLOR,
        tone: ScanTonePreset = ScanTonePreset.ORIGINAL
    ): Bitmap {
        require(!source.isRecycled) { "Source photo has been recycled" }
        require(corners.size == 4) { "Exactly four scan corners are required" }
        // Snapshot mutable PointF values so validation and rendering use the same coordinates.
        val points = corners.map { PointF(it.x, it.y) }
        require(points.all { it.x.isFinite() && it.y.isFinite() &&
            it.x >= 0f && it.y >= 0f && it.x <= source.width && it.y <= source.height }) {
            "Scan corners must be finite and inside the photo"
        }
        var twiceArea = 0.0
        repeat(4) { index ->
            val a = points[index]
            val b = points[(index + 1) % 4]
            val c = points[(index + 2) % 4]
            val cross = (b.x.toDouble() - a.x) * (c.y.toDouble() - b.y) -
                (b.y.toDouble() - a.y) * (c.x.toDouble() - b.x)
            require(cross > 0.001) { "Scan corners must form a convex clockwise quadrilateral" }
            twiceArea += a.x.toDouble() * b.y - a.y.toDouble() * b.x
        }
        require(twiceArea >= 512.0) { "Selected scan area is too small (minimum 256 square pixels)" }
        fun edge(a: Int, b: Int) = hypot(points[a].x.toDouble() - points[b].x,
            points[a].y.toDouble() - points[b].y)
        val width = max(edge(0, 1), edge(3, 2))
        val height = max(edge(0, 3), edge(1, 2))
        val scale = min(1.0, MAX_EDGE / max(width, height))
        val outWidth = (width * scale).roundToInt().coerceIn(16, MAX_EDGE)
        val outHeight = (height * scale).roundToInt().coerceIn(16, MAX_EDGE)
        val from = FloatArray(8) { if (it % 2 == 0) points[it / 2].x else points[it / 2].y }
        val to = floatArrayOf(0f, 0f, outWidth.toFloat(), 0f, outWidth.toFloat(), outHeight.toFloat(), 0f, outHeight.toFloat())
        val transform = Matrix()
        require(transform.setPolyToPoly(from, 0, to, 0, 4)) { "Scan perspective transform is singular" }
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = previewFilter(mode, tone)
            }
            Canvas(result).apply {
                drawColor(Color.WHITE)
                // setPolyToPoly maps source -> destination; Canvas performs inverse sampling itself.
                drawBitmap(source, transform, paint)
            }
            if (mode == ScanColorMode.DOCUMENT) {
                // Deterministic high-contrast document mode, using only one row of extra memory.
                val row = IntArray(outWidth)
                repeat(outHeight) { y ->
                    result.getPixels(row, 0, outWidth, 0, y, outWidth, 1)
                    repeat(outWidth) { x -> row[x] = if (Color.red(row[x]) < 170) Color.BLACK else Color.WHITE }
                    result.setPixels(row, 0, outWidth, 0, y, outWidth, 1)
                }
            }
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        }
    }

    /** Write 1..100 processed JPEG pages to centered portrait A4, publishing only a complete PDF <=500 MiB.
     * Cancellation throws CancellationException, preserving any existing output. No source is modified.
     */
    fun writePdf(pages: List<File>, output: File, isCancelled: () -> Boolean = { false }) {
        require(pages.size in 1..100) { "A scanned PDF must contain between 1 and 100 pages" }
        fun checkCancelled() {
            if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Scan PDF creation cancelled")
        }
        checkCancelled()
        val destination = output.absoluteFile
        require(pages.none { it.canonicalFile == destination.canonicalFile }) { "PDF output must not replace a source photo" }
        val parent = destination.parentFile ?: throw IOException("PDF output directory is missing")
        if (!parent.isDirectory) throw IOException("PDF output directory does not exist")
        val staged = File.createTempFile(".scan-pdf-", ".tmp", parent)
        try {
            // Keep PDF streams on disk; native PdfDocument would retain every page's raster data.
            // Stream corrected RGB JPEGs directly: JPEGFactory would first allocate the whole file.
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(parent)).use { document ->
                var jpegBytes = 0L
                pages.forEach { file ->
                    checkCancelled()
                    if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) {
                        throw IOException("Processed page is missing, empty, or exceeds the 64 MiB limit")
                    }
                    jpegBytes += file.length()
                    if (jpegBytes > MAX_PDF_BYTES) throw IOException("Scanned PDF exceeds the 500 MiB import limit")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..MAX_EDGE || bounds.outHeight !in 1..MAX_EDGE) {
                        throw IOException("Processed pages must be JPEG images with edges no larger than 2200 pixels")
                    }
                    val image = file.inputStream().use { input ->
                        val cancellable = object : FilterInputStream(input) {
                            private var readBytes = 0L
                            private fun account(count: Int): Int {
                                if (count > 0) readBytes += count
                                if (readBytes > MAX_FILE_BYTES) throw IOException("Processed page exceeds the 64 MiB limit")
                                return count
                            }
                            override fun read(): Int {
                                checkCancelled()
                                val value = input.read()
                                account(if (value < 0) 0 else 1)
                                return value
                            }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                checkCancelled()
                                return account(input.read(bytes, offset, length))
                            }
                        }
                        PDImageXObject(document, cancellable, COSName.DCT_DECODE,
                            bounds.outWidth, bounds.outHeight, 8, PDDeviceRGB.INSTANCE)
                    }
                    checkCancelled()
                    val page = PDPage(PDRectangle(595f, 842f))
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.setNonStrokingColor(255, 255, 255)
                        content.addRect(0f, 0f, 595f, 842f)
                        content.fill()
                        val scale = min(595f / image.width, 842f / image.height)
                        val width = image.width * scale
                        val height = image.height * scale
                        content.drawImage(image, (595f - width) / 2f, (842f - height) / 2f, width, height)
                    }
                    checkCancelled()
                }
                staged.outputStream().use { stream ->
                    var written = 0L
                    fun reserve(length: Int) {
                        checkCancelled()
                        if (length.toLong() > MAX_PDF_BYTES - written) {
                            throw IOException("Scanned PDF exceeds the 500 MiB import limit")
                        }
                        written += length
                    }
                    document.save(object : OutputStream() {
                        override fun write(value: Int) { reserve(1); stream.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            reserve(length)
                            stream.write(bytes, offset, length)
                        }
                    })
                    stream.flush()
                    stream.fd.sync()
                }
            }
            checkCancelled()
            // Same-directory atomic rename: never delete the destination first or fall back to copying.
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
    }
}
