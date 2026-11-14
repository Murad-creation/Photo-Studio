package com.smartscan.photostudio.feature.nid

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min

/** Four draggable corners over the image: manual crop and perspective adjustment. */
@Composable
fun CropEditor(bitmap: Bitmap, corners: List<PointF>, onChange: (List<PointF>) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(corners)
    val callback by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / bitmap.width, boxH / bitmap.height)
        val offX = (boxW - bitmap.width * scale) / 2f
        val offY = (boxH - bitmap.height * scale) / 2f
        var active by remember { mutableIntStateOf(-1) }

        Image(bitmap.asImageBitmap(), "Card photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(
            Modifier.fillMaxSize().pointerInput(bitmap, scale, offX, offY) {
                detectDragGestures(
                    onDragStart = { pos ->
                        var best = -1; var bestD = Float.MAX_VALUE
                        current.forEachIndexed { i, p ->
                            val d = hypot(p.x * scale + offX - pos.x, p.y * scale + offY - pos.y)
                            if (d < bestD) { bestD = d; best = i }
                        }
                        active = if (bestD <= 56.dp.toPx()) best else -1
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, _ ->
                        if (active >= 0) {
                            change.consume()
                            val nx = ((change.position.x - offX) / scale).coerceIn(0f, bitmap.width.toFloat())
                            val ny = ((change.position.y - offY) / scale).coerceIn(0f, bitmap.height.toFloat())
                            callback(current.mapIndexed { i, p -> if (i == active) PointF(nx, ny) else p })
                        }
                    },
                )
            },
        ) {
            val pts = current.map { Offset(it.x * scale + offX, it.y * scale + offY) }
            for (i in 0..3) drawLine(accent, pts[i], pts[(i + 1) % 4], strokeWidth = 3.dp.toPx())
            pts.forEach {
                drawCircle(Color.White, 14.dp.toPx(), it)
                drawCircle(accent, 10.dp.toPx(), it)
            }
        }
    }
}
