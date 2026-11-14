package com.smartscan.photostudio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(20.dp)
val ButtonShape = RoundedCornerShape(14.dp)
val DialogShape = RoundedCornerShape(20.dp)

val AppShapes = Shapes(
    small = ButtonShape,
    medium = CardShape,
    large = DialogShape,
    extraLarge = DialogShape,
)
