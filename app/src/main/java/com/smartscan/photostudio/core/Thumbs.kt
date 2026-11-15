package com.smartscan.photostudio.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class PdfDoc(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, width: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val h = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    @Synchronized
    override fun close() { runCatching { renderer.close() }; runCatching { pfd.close() } }
}

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, targetPx: Int): Bitmap? {
        val key = "${file.path}:${file.lastModified()}:$targetPx"
        cache.get(key)?.let { return it }
        val bmp = runCatching {
            if (file.extension.equals("pdf", true)) PdfDoc(file).use { it.render(0, targetPx) }
            else decodeSampled(file, targetPx)
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    fun decodeSampled(file: File, target: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var s = 1
        while (max(b.outWidth, b.outHeight) / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
