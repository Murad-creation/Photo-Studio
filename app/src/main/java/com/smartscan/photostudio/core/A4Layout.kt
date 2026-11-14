package com.smartscan.photostudio.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/** A4 portrait page: front on top, back below, exactly 0.5 inch (12.7 mm) apart, centered, white background. */
object A4Layout {
    const val PAGE_W_MM = 210.0
    const val PAGE_H_MM = 297.0
    const val GAP_MM = 12.7           // 0.5 inch
    const val TOP_MARGIN_MM = 20.0
    const val BOTTOM_MARGIN_MM = 15.0
    const val SIDE_MARGIN_MM = 15.0
    const val ACTUAL_CARD_WIDTH_MM = 85.6
    const val LARGE_CARD_WIDTH_MM = 150.0

    private fun px(mm: Double, dpi: Int) = (mm / 25.4 * dpi).toFloat()

    /** Largest width <= requested that keeps both cards inside the margins, keeping each card's own aspect ratio. */
    fun fitCardWidthMm(front: Bitmap, back: Bitmap, requestedMm: Double): Double {
        val k = front.height.toDouble() / front.width + back.height.toDouble() / back.width
        val maxByHeight = (PAGE_H_MM - TOP_MARGIN_MM - BOTTOM_MARGIN_MM - GAP_MM) / k
        val maxByWidth = PAGE_W_MM - 2 * SIDE_MARGIN_MM
        return min(requestedMm, min(maxByHeight, maxByWidth))
    }

    fun compose(front: Bitmap, back: Bitmap, dpi: Int, cardWidthMm: Double): Bitmap {
        val pw = px(PAGE_W_MM, dpi).roundToInt()
        val ph = px(PAGE_H_MM, dpi).roundToInt()
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val w = px(cardWidthMm, dpi)
        val hf = w * front.height / front.width
        val hb = w * back.height / back.width
        val x = (pw - w) / 2f
        val y1 = px(TOP_MARGIN_MM, dpi)
        val y2 = y1 + hf + px(GAP_MM, dpi)
        canvas.drawBitmap(front, null, RectF(x, y1, x + w, y1 + hf), paint)
        canvas.drawBitmap(back, null, RectF(x, y2, x + w, y2 + hb), paint)
        return page
    }
}
