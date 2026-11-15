package com.smartscan.photostudio.core

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

data class Analysis(val alpha: Bitmap, val face: RectF, val faceFound: Boolean, val rollZ: Float)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** On-device face detection and person segmentation (ML Kit, bundled models) refined with GrabCut. */
object PhotoSegmenter {
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }
    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
    }

    suspend fun analyze(bmp: Bitmap): Analysis {
        val faces = detector.process(InputImage.fromBitmap(bmp, 0)).awaitResult()
        val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val alpha = segment(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val fallback = RectF(w * 0.30f, h * 0.18f, w * 0.70f, h * 0.18f + w * 0.40f * 1.25f)
        return Analysis(alpha, best?.boundingBox?.let { RectF(it) } ?: fallback, best != null, best?.headEulerAngleZ ?: 0f)
    }

    private suspend fun segment(bmp: Bitmap): Bitmap {
        check(ImageOps.cvReady()) { "Image engine (OpenCV) could not start." }
        val small = ImageOps.downscale(bmp, 720)
        val mask = segmenter.process(InputImage.fromBitmap(small, 0)).awaitResult()
        val mw = mask.width; val mh = mask.height
        val fb = FloatArray(mw * mh)
        mask.buffer.rewind(); mask.buffer.asFloatBuffer().get(fb)
        val conf = Mat(mh, mw, CvType.CV_32F); conf.put(0, 0, fb)
        val conf8 = Mat(); conf.convertTo(conf8, CvType.CV_8U, 255.0); conf.release()
        val img = if (small.width != mw || small.height != mh) Bitmap.createScaledBitmap(small, mw, mh, true) else small
        val fg = refine(img, conf8)
        conf8.release()
        val big = Mat()
        Imgproc.resize(fg, big, CvSize(bmp.width.toDouble(), bmp.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        fg.release()
        // pull the edge in by one pixel (no halo) and soften it
        Imgproc.erode(big, big, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, CvSize(3.0, 3.0)))
        Imgproc.GaussianBlur(big, big, CvSize(0.0, 0.0), max(1.0, max(bmp.width, bmp.height) / 600.0))
        val rgba = Mat(); Imgproc.cvtColor(big, rgba, Imgproc.COLOR_GRAY2RGBA)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, out)
        big.release(); rgba.release()
        return out
    }

    private fun refine(img: Bitmap, conf8: Mat): Mat {
        val fallback = Mat(); Imgproc.threshold(conf8, fallback, 127.0, 255.0, Imgproc.THRESH_BINARY)
        return runCatching {
            val rgba = Mat(); Utils.bitmapToMat(img, rgba)
            val rgb = Mat(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
            val gc = Mat(conf8.rows(), conf8.cols(), CvType.CV_8UC1, Scalar(Imgproc.GC_PR_BGD.toDouble()))
            val probFg = Mat(); Imgproc.threshold(conf8, probFg, 127.0, 255.0, Imgproc.THRESH_BINARY)
            gc.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), probFg)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, CvSize(9.0, 9.0))
            val sureFg = Mat(); Imgproc.threshold(conf8, sureFg, 229.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.erode(sureFg, sureFg, k)
            gc.setTo(Scalar(Imgproc.GC_FGD.toDouble()), sureFg)
            val sureBg = Mat(); Imgproc.threshold(conf8, sureBg, 25.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.erode(sureBg, sureBg, k)
            gc.setTo(Scalar(Imgproc.GC_BGD.toDouble()), sureBg)
            require(Core.countNonZero(sureFg) > 50 && Core.countNonZero(sureBg) > 50) { "mask too small" }
            Imgproc.grabCut(rgb, gc, CvRect(), Mat(), Mat(), 4, Imgproc.GC_INIT_WITH_MASK)
            val m1 = Mat(); Core.compare(gc, Scalar(1.0), m1, Core.CMP_EQ)
            val m3 = Mat(); Core.compare(gc, Scalar(3.0), m3, Core.CMP_EQ)
            val fg = Mat(); Core.bitwise_or(m1, m3, fg)
            // keep only the largest connected region (the person)
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(fg.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }
            val res = Mat.zeros(fg.rows(), fg.cols(), CvType.CV_8UC1)
            if (biggest != null) Imgproc.drawContours(res, listOf(biggest), -1, Scalar(255.0), -1) else fg.copyTo(res)
            rgb.release(); gc.release(); probFg.release(); sureFg.release(); sureBg.release()
            m1.release(); m3.release(); fg.release(); fallback.release()
            res
        }.getOrElse { fallback }
    }
}
