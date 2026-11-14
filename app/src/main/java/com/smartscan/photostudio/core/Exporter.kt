package com.smartscan.photostudio.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"), JPG("jpg", "image/jpeg"), PNG("png", "image/png")
}

enum class ExportQuality(val label: String, val jpeg: Int) {
    STANDARD("Standard", 85), HIGH("High", 92), MAXIMUM("Maximum", 100)
}

object Exporter {
    fun encode(page: Bitmap, format: ExportFormat, jpegQuality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        when (format) {
            ExportFormat.JPG -> page.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            ExportFormat.PNG -> page.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.PDF -> {
                val doc = PdfDocument()
                val info = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 in points
                val p = doc.startPage(info)
                p.canvas.drawBitmap(page, null, RectF(0f, 0f, 595f, 842f), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(p)
                doc.writeTo(out)
                doc.close()
            }
        }
        return out.toByteArray()
    }
}

/** App-private library; My Files (a later phase) lists these folders. */
object Library {
    fun dir(context: Context, folder: String): File =
        File(context.filesDir, "library/$folder").apply { mkdirs() }
}
