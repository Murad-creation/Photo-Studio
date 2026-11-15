package com.smartscan.photostudio.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Drag the handle: the left part shows [before], the right part [after]. Both bitmaps must have the same size. */
@Composable
fun BeforeAfterSlider(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    val line = MaterialTheme.colorScheme.primary
    Box(
        modifier.background(Color(0xFFE6E6E6)).pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                frac = (change.position.x / size.width).coerceIn(0f, 1f)
            }
        },
    ) {
        Image(after.asImageBitmap(), "After", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(
            before.asImageBitmap(), "Before",
            Modifier.fillMaxSize().clip(object : Shape {
                override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                    Outline.Rectangle(Rect(0f, 0f, size.width * frac, size.height))
            }),
            contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * frac
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
            drawCircle(Color.White, 16.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(line, 12.dp.toPx(), Offset(x, size.height / 2))
        }
        Text("Before", Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text("After", Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
