package com.smartscan.photostudio.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Point as CvPoint
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EnhanceFilter { ORIGINAL, AUTO, MAGIC }

data class EnhanceParams(
    val filter: EnhanceFilter = EnhanceFilter.MAGIC,
    val brightness: Float = 0f,   // -60..60
    val contrast: Float = 0f,     // -50..50 (%)
    val sharpen: Float = 30f,     // 0..100
)

object ImageOps {
    private var ready: Boolean? = null

    @Synchronized
    fun cvReady(): Boolean {
        if (ready == null) ready = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        return ready == true
    }

    // ---------- loading ----------
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val deg = runCatching {
            cr.openInputStream(uri)?.use { exifDegrees(ExifInterface(it)) }
        }.getOrNull() ?: 0
        val longSide = max(bmp.width, bmp.height)
        val m = Matrix()
        if (longSide > maxSide) { val s = maxSide.toFloat() / longSide; m.postScale(s, s) }
        if (deg != 0) m.postRotate(deg.toFloat())
        if (!m.isIdentity) {
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }

    private fun exifDegrees(e: ExifInterface): Int =
        when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val l = max(src.width, src.height)
        if (l <= maxSide) return src
        val s = maxSide.toFloat() / l
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ---------- card detection ----------
    fun defaultCorners(b: Bitmap): List<PointF> {
        val dx = b.width * 0.08f; val dy = b.height * 0.08f
        return listOf(PointF(dx, dy), PointF(b.width - dx, dy), PointF(b.width - dx, b.height - dy), PointF(dx, b.height - dy))
    }

    /** Returns corners ordered TL, TR, BR, BL in bitmap coordinates, or null if no card-like quadrilateral was found. */
    fun detectCard(src: Bitmap): List<PointF>? {
        if (!cvReady()) return null
        val mat = Mat(); Utils.bitmapToMat(src, mat)
        val scale = 800.0 / max(src.width, src.height)
        val small = Mat(); Imgproc.resize(mat, small, CvSize(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat(); Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, CvSize(5.0, 5.0), 0.0)
        val edges = Mat(); Imgproc.Canny(gray, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, CvSize(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imgArea = small.width().toDouble() * small.height()
        var best: Array<CvPoint>? = null
        var bestArea = 0.0
        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
            val pts = approx.toArray()
            if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                val area = abs(Imgproc.contourArea(approx))
                if (area > imgArea * 0.15 && area > bestArea) { bestArea = area; best = pts }
            }
            c.release(); c2f.release(); approx.release()
        }
        mat.release(); small.release(); gray.release(); edges.release()
        val found = best ?: return null
        return orderCorners(found.map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) })
    }

    private fun orderCorners(p: List<PointF>): List<PointF> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return listOf(tl, tr, br, bl)
    }

    // ---------- perspective correction ----------
    fun warp(src: Bitmap, c: List<PointF>, maxSide: Int = 2400): Bitmap {
        if (!cvReady()) return src
        val (tl, tr, br, bl) = c
        fun d(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        var w = max(d(tl, tr), d(bl, br))
        var h = max(d(tl, bl), d(tr, br))
        val s = min(1.0, maxSide / max(w, h))
        w = max(2.0, (w * s).roundToInt().toDouble()); h = max(2.0, (h * s).roundToInt().toDouble())
        val m = Mat(); Utils.bitmapToMat(src, m)
        val srcPts = MatOfPoint2f(
            CvPoint(tl.x.toDouble(), tl.y.toDouble()), CvPoint(tr.x.toDouble(), tr.y.toDouble()),
            CvPoint(br.x.toDouble(), br.y.toDouble()), CvPoint(bl.x.toDouble(), bl.y.toDouble()),
        )
        val dstPts = MatOfPoint2f(CvPoint(0.0, 0.0), CvPoint(w, 0.0), CvPoint(w, h), CvPoint(0.0, h))
        val t = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(m, out, t, CvSize(w, h), Imgproc.INTER_CUBIC)
        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        m.release(); out.release(); t.release(); srcPts.release(); dstPts.release()
        return bmp
    }

    // ---------- enhancement ----------
    fun enhance(src: Bitmap, p: EnhanceParams): Bitmap {
        if (!cvReady()) return src
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        var work: Mat = when (p.filter) {
            EnhanceFilter.ORIGINAL -> rgb.clone()
            EnhanceFilter.AUTO -> autoLevels(rgb)
            EnhanceFilter.MAGIC -> magic(rgb)
        }
        rgb.release()
        if (p.brightness != 0f || p.contrast != 0f) {
            val adj = Mat()
            work.convertTo(adj, -1, 1.0 + p.contrast / 100.0, p.brightness.toDouble())
            work.release(); work = adj
        }
        if (p.sharpen > 0f) {
            val blur = Mat()
            Imgproc.GaussianBlur(work, blur, CvSize(0.0, 0.0), 1.5)
            val amt = p.sharpen / 100.0 * 1.2
            Core.addWeighted(work, 1.0 + amt, blur, -amt, 0.0, work)
            blur.release()
        }
        val outRgba = Mat(); Imgproc.cvtColor(work, outRgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, bmp)
        work.release(); outRgba.release()
        return bmp
    }

    private fun percentileBounds(gray: Mat, lowPct: Double, highPct: Double): Pair<Double, Double> {
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val h = FloatArray(256); hist.get(0, 0, h); hist.release()
        val total = h.sum()
        var acc = 0f; var lo = 0; var hi = 255
        for (i in 0..255) { acc += h[i]; if (acc >= total * lowPct) { lo = i; break } }
        acc = 0f
        for (i in 0..255) { acc += h[i]; if (acc >= total * highPct) { hi = i; break } }
        if (hi - lo < 20) { lo = 0; hi = 255 }
        return lo.toDouble() to hi.toDouble()
    }

    private fun stretch(rgb: Mat, lo: Double, hi: Double): Mat {
        val out = Mat()
        val a = 255.0 / (hi - lo)
        rgb.convertTo(out, -1, a, -lo * a)
        return out
    }

    private fun autoLevels(rgb: Mat): Mat {
        val g = Mat(); Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val (lo, hi) = percentileBounds(g, 0.01, 0.99); g.release()
        return stretch(rgb, lo, hi)
    }

    /** Flatten uneven lighting/shadows on the luminance channel only (colours kept), then clean levels and mild denoise. */
    private fun magic(rgb: Mat): Mat {
        val lab = Mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>(); Core.split(lab, ch)
        val lf = Mat(); ch[0].convertTo(lf, CvType.CV_32F)
        val illum = Mat()
        Imgproc.GaussianBlur(lf, illum, CvSize(0.0, 0.0), max(lf.cols(), lf.rows()) / 6.0)
        Core.add(illum, Scalar(1.0), illum)
        val meanIllum = Core.mean(illum).`val`[0]
        val ratio = Mat(); Core.divide(lf, illum, ratio, meanIllum)
        val l8 = Mat(); ratio.convertTo(l8, CvType.CV_8U)
        ch[0].release(); ch[0] = l8
        val lab2 = Mat(); Core.merge(ch, lab2)
        val flat = Mat(); Imgproc.cvtColor(lab2, flat, Imgproc.COLOR_Lab2RGB)
        val g = Mat(); Imgproc.cvtColor(flat, g, Imgproc.COLOR_RGB2GRAY)
        val (lo, hi) = percentileBounds(g, 0.02, 0.97)
        val levels = stretch(flat, lo, hi)
        val den = Mat(); Imgproc.bilateralFilter(levels, den, 5, 30.0, 5.0)
        lab.release(); ch.forEach { it.release() }; lf.release(); illum.release(); ratio.release()
        lab2.release(); flat.release(); g.release(); levels.release()
        return den
    }
}
