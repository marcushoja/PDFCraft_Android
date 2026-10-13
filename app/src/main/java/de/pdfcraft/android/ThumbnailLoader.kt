package de.pdfcraft.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

class ThumbnailLoader(context: Context) {
    init { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }
    private val directory = File(context.applicationContext.cacheDir, "thumbs")
    private val cacheDirectory=context.applicationContext.cacheDir
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var closed = false

    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(file: File, page: Int, width: Int, target: ImageView) {
        val renderWidth = width.coerceAtLeast(1)
        val request = Any()
        val reference = WeakReference(target)
        val key = cacheKey(file, page, renderWidth)
        synchronized(lock) {
            if (closed) return
            target.tag = request
            target.adjustViewBounds = true
            target.scaleType = ImageView.ScaleType.FIT_CENTER
            target.setImageDrawable(null)
            memory.get(key)?.let {
                target.setImageBitmap(it)
                return
            }
            try {
                executor.execute {
                    if (closed || Thread.currentThread().isInterrupted) return@execute
                    val bitmap = try {
                        readDisk(key) ?: render(file, page, renderWidth)?.also {
                            if (!closed) writeDisk(key, it)
                        }
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    } ?: return@execute

                    synchronized(lock) {
                        if (closed || Thread.currentThread().isInterrupted) return@synchronized
                        memory.put(key, bitmap)
                        handler.post {
                            synchronized(lock) {
                                val view = reference.get()
                                if (!closed && view != null && view.tag === request) {
                                    view.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Closing the loader can reject work that has not yet started.
            }
        }
    }

    private fun cacheKey(file: File, page: Int, width: Int): String {
        val source = "annotations-v2:${file.name.length}:${file.name}:${file.lastModified()}:$page:$width"
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun render(file: File, index: Int, width: Int): Bitmap? {
        PdfAnnotationRendering.prepare(file,cacheDirectory).use { prepared ->
        ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (index !in 0 until renderer.pageCount || closed) return null
                renderer.openPage(index).use { page ->
                    val height = (width.toDouble() * page.height / page.width)
                        .roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    } catch (failure: Exception) {
                        bitmap.recycle()
                        throw failure
                    } catch (failure: OutOfMemoryError) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }
        }
    }

    private fun readDisk(key: String): Bitmap? {
        return try {
            val file = File(directory, "$key.png")
            if (!file.isFile) return null
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(key: String, bitmap: Bitmap) {
        var temporary: File? = null
        try {
            if (closed || (!directory.isDirectory && !directory.mkdirs())) return
            temporary = File.createTempFile("thumb-", ".tmp", directory)
            val saved = FileOutputStream(temporary).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (saved && !closed) temporary.renameTo(File(directory, "$key.png"))
            trimDisk()
        } catch (_: Exception) {
            // Disk caching is optional (including when storage is full).
        } finally {
            temporary?.delete()
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "png" }
            ?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 40L * 1024 * 1024 || closed) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            executor.shutdownNow()
            handler.removeCallbacksAndMessages(null)
            // Views may still display these bitmaps, so do not recycle them.
            memory.evictAll()
        }
    }
}
