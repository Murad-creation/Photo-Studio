package com.smartscan.photostudio.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class StudioSource(val work: Bitmap, val alpha: Bitmap, val face: RectF) {
    fun scaled(maxSide: Int): StudioSource {
        val l = max(work.width, work.height)
        if (l <= maxSide) return this
        val s = maxSide.toFloat() / l
        val w = (work.width * s).roundToInt(); val h = (work.height * s).roundToInt()
        return StudioSource(
            Bitmap.createScaledBitmap(work, w, h, true), Bitmap.createScaledBitmap(alpha, w, h, true),
            RectF(face.left * s, face.top * s, face.right * s, face.bottom * s),
        )
    }
}

data class StudioParams(
    val bg: Int, val headRatio: Double,
    val brightness: Float, val exposure: Float, val contrast: Float,
    val headScale: Float, val shiftX: Float, val shiftY: Float, val tilt: Float,
)

/** Frames the head per the size preset, adjusts light on the person only, and composites on a flat background colour. */
object PassportRender {
    fun render(src: StudioSource, p: StudioParams, outW: Int, outH: Int): Bitmap {
        check(ImageOps.cvReady()) { "Image engine (OpenCV) could not start." }
        val rgba = Mat(); Utils.bitmapToMat(src.work, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
        val adj = adjust(rgb, p); rgb.release()
        val aRgba = Mat(); Utils.bitmapToMat(src.alpha, aRgba)
        val a8 = Mat(); Imgproc.cvtColor(aRgba, a8, Imgproc.COLOR_RGBA2GRAY); aRgba.release()

        val face = src.face
        val faceH = face.height().toDouble()
        val headTop = face.top - 0.28 * faceH
        val headH = 1.28 * faceH
        val s = (p.headRatio * p.headScale * outH) / headH
        val cx = face.centerX().toDouble(); val cy = face.centerY().toDouble()
        val tX = outW * (0.5 + p.shiftX)
        val tY = outH * (0.07 + p.shiftY) + (cy - headTop) * s
        val th = Math.toRadians(p.tilt.toDouble()); val c = cos(th); val sn = sin(th)
        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, s * c, -s * sn, tX - s * (c * cx - sn * cy), s * sn, s * c, tY - s * (sn * cx + c * cy))

        val size = CvSize(outW.toDouble(), outH.toDouble())
        val fgW = Mat(); Imgproc.warpAffine(adj, fgW, m, size, Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        val aW = Mat(); Imgproc.warpAffine(a8, aW, m, size, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
        adj.release(); a8.release(); m.release()

        val fgF = Mat(); fgW.convertTo(fgF, CvType.CV_32FC3)
        val a3 = Mat(); Core.merge(listOf(aW, aW, aW), a3)
        val aF = Mat(); a3.convertTo(aF, CvType.CV_32FC3, 1.0 / 255.0)
        val bgF = Mat(outH, outW, CvType.CV_32FC3, Scalar(Color.red(p.bg).toDouble(), Color.green(p.bg).toDouble(), Color.blue(p.bg).toDouble()))
        val diff = Mat()
        Core.subtract(fgF, bgF, diff); Core.multiply(diff, aF, diff); Core.add(bgF, diff, diff)
        val out8 = Mat(); diff.convertTo(out8, CvType.CV_8UC3)
        val outRgba = Mat(); Imgproc.cvtColor(out8, outRgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, bmp)
        listOf(fgW, aW, fgF, a3, aF, bgF, diff, out8, outRgba).forEach { it.release() }
        return bmp
    }

    private fun adjust(rgb: Mat, p: StudioParams): Mat {
        var cur = rgb.clone()
        if (p.exposure != 0f) {
            val gamma = 2.0.pow(-p.exposure.toDouble())
            val bytes = ByteArray(256) { i -> (255.0 * (i / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255).toByte() }
            val lut = Mat(1, 256, CvType.CV_8UC1); lut.put(0, 0, bytes)
            val o = Mat(); Core.LUT(cur, lut, o); cur.release(); lut.release(); cur = o
        }
        if (p.brightness != 0f || p.contrast != 0f) {
            val o = Mat(); cur.convertTo(o, -1, 1.0 + p.contrast / 100.0, p.brightness.toDouble()); cur.release(); cur = o
        }
        val blur = Mat()
        Imgproc.GaussianBlur(cur, blur, CvSize(0.0, 0.0), 1.2)
        Core.addWeighted(cur, 1.25, blur, -0.25, 0.0, cur)  // mild sharpening, no skin smoothing
        blur.release()
        return cur
    }
}

object PrintLayout {
    data class Plan(val landscape: Boolean, val cols: Int, val rows: Int) { val capacity: Int get() = cols * rows }

    const val MARGIN_MM = 3.0

    fun plan(sheetWmm: Double, sheetHmm: Double, pw: Double, ph: Double, gap: Double): Plan {
        fun fit(w: Double, h: Double): Pair<Int, Int> =
            max(0, floor((w - 2 * MARGIN_MM + gap) / (pw + gap)).toInt()) to max(0, floor((h - 2 * MARGIN_MM + gap) / (ph + gap)).toInt())
        val lo = min(sheetWmm, sheetHmm); val hi = max(sheetWmm, sheetHmm)
        val (pc, pr) = fit(lo, hi); val (lc, lr) = fit(hi, lo)
        return if (lc * lr > pc * pr) Plan(true, lc, lr) else Plan(false, pc, pr)
    }

    fun sheetMm(sheetWmm: Double, sheetHmm: Double, plan: Plan): Pair<Double, Double> {
        val lo = min(sheetWmm, sheetHmm); val hi = max(sheetWmm, sheetHmm)
        return if (plan.landscape) hi to lo else lo to hi
    }

    fun compose(photo: Bitmap, plan: Plan, sheetWmm: Double, sheetHmm: Double, pw: Double, ph: Double,
                copies: Int, gap: Double, dpi: Int, cutLines: Boolean): Bitmap {
        val (sw, sh) = sheetMm(sheetWmm, sheetHmm, plan)
        fun px(mm: Double) = (mm / 25.4 * dpi).toFloat()
        val page = Bitmap.createBitmap(px(sw).roundToInt(), px(sh).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page); canvas.drawColor(Color.WHITE)
        val n = min(copies, plan.capacity)
        if (n <= 0) return page
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { style = Paint.Style.STROKE; color = 0xFFB5B5B5.toInt(); strokeWidth = max(1f, dpi / 100f) }
        val colsUsed = min(plan.cols, n); val rowsUsed = ceil(n / colsUsed.toDouble()).toInt()
        val blockW = colsUsed * pw + (colsUsed - 1) * gap
        val blockH = rowsUsed * ph + (rowsUsed - 1) * gap
        val x0 = (sw - blockW) / 2; val y0 = (sh - blockH) / 2
        for (i in 0 until n) {
            val col = i % colsUsed; val row = i / colsUsed
            val r = RectF(px(x0 + col * (pw + gap)), px(y0 + row * (ph + gap)), px(x0 + col * (pw + gap) + pw), px(y0 + row * (ph + gap) + ph))
            canvas.drawBitmap(photo, null, r, paint)
            if (cutLines) canvas.drawRect(r, line)
        }
        return page
    }
}

object PageEncoder {
    /** JPG / PNG bytes, or a one-page PDF whose page is exactly [pageWmm] x [pageHmm]. */
    fun encode(bmp: Bitmap, format: ExportFormat, jpegQ: Int, pageWmm: Double, pageHmm: Double): ByteArray {
        val bos = ByteArrayOutputStream()
        when (format) {
            ExportFormat.JPG -> bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, bos)
            ExportFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            ExportFormat.PDF -> {
                val j = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, j)
                val w = (pageWmm / 25.4 * 72).toFloat(); val h = (pageHmm / 25.4 * 72).toFloat()
                JpegPdf.write(listOf(JpegPdf.Page(j.toByteArray(), bmp.width, bmp.height, w, h, 0f, 0f, w, h)), bos)
            }
        }
        return bos.toByteArray()
    }
}
