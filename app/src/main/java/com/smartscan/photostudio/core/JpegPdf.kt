package com.smartscan.photostudio.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/** Minimal multi-page PDF writer that embeds JPEG data as-is, so file size follows the chosen JPEG quality. */
object JpegPdf {
    class Page(
        val jpeg: ByteArray, val imgW: Int, val imgH: Int,
        val pageW: Float, val pageH: Float,          // page size in points
        val x: Float, val y: Float, val w: Float, val h: Float, // image rectangle in points
    )

    fun write(pages: List<Page>, out: OutputStream) {
        val buf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        val latin = Charsets.ISO_8859_1
        fun str(s: String) = buf.write(s.toByteArray(latin))
        fun f(v: Float) = String.format(Locale.US, "%.2f", v)
        fun obj(n: Int, body: () -> Unit) {
            while (offsets.size < n) offsets.add(0)
            offsets[n - 1] = buf.size()
            str("$n 0 obj\n"); body(); str("endobj\n")
        }
        str("%PDF-1.4\n")
        buf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val n = pages.size
        obj(1) { str("<< /Type /Catalog /Pages 2 0 R >>\n") }
        obj(2) { str("<< /Type /Pages /Kids [" + pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" } + "] /Count $n >>\n") }
        pages.forEachIndexed { i, p ->
            val pg = 3 + 3 * i; val ct = pg + 1; val im = pg + 2
            obj(pg) {
                str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(p.pageW)} ${f(p.pageH)}] " +
                    "/Resources << /XObject << /Im0 $im 0 R >> >> /Contents $ct 0 R >>\n")
            }
            val content = "q\n${f(p.w)} 0 0 ${f(p.h)} ${f(p.x)} ${f(p.y)} cm\n/Im0 Do\nQ\n".toByteArray(latin)
            obj(ct) { str("<< /Length ${content.size} >>\nstream\n"); buf.write(content); str("\nendstream\n") }
            obj(im) {
                str("<< /Type /XObject /Subtype /Image /Width ${p.imgW} /Height ${p.imgH} /ColorSpace /DeviceRGB " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n")
                buf.write(p.jpeg); str("\nendstream\n")
            }
        }
        val xrefPos = buf.size()
        val total = 3 + 3 * n
        str("xref\n0 $total\n0000000000 65535 f \n")
        offsets.forEach { str(String.format(Locale.US, "%010d 00000 n \n", it)) }
        str("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.write(buf.toByteArray())
    }
}
