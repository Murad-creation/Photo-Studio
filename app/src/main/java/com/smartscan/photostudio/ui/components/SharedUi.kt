package com.smartscan.photostudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartscan.photostudio.data.PhotoPreset
import com.smartscan.photostudio.ui.theme.*
import java.util.Locale

@Composable
fun StepHeader(title: String, index: Int, total: Int) {
    Column(Modifier.padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text("Step ${index + 1} of $total · $title", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { (index + 1) / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp))
    }
}

@Composable
fun ProcessingIndicator(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(shape = CardShape, tonalElevation = 6.dp) {
            Row(Modifier.padding(Spacing.x5), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(text)
            }
        }
    }
}

@Composable
fun StatusBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

val PassportColors = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Light Blue" to 0xFFD6E6F5.toInt(),
    "Sky Blue" to 0xFF87CEEB.toInt(),
    "Professional Blue" to 0xFF3A6EA5.toInt(),
    "Dark Blue" to 0xFF1F3B73.toInt(),
)

@Composable
fun BackgroundColorPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var showCustom by remember { mutableStateOf(false) }
    val isCustom = PassportColors.none { it.second == selected }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text("Background", style = MaterialTheme.typography.titleMedium)
        PassportColors.forEach { (name, c) -> Swatch(name, c, selected == c) { onSelect(c) } }
        Swatch("Custom color…", selected.takeIf { isCustom } ?: 0xFFCCCCCC.toInt(), isCustom) { showCustom = true }
    }
    if (showCustom) ColorPickerDialog(selected, { showCustom = false }) { onSelect(it); showCustom = false }
}

@Composable
private fun Swatch(name: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    // each swatch is a labelled, 48dp+ touch target
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Box(
            Modifier.size(32.dp).background(Color(color), CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    AlertDialog(
        onDismissRequest = onDismiss, shape = DialogShape,
        title = { Text("Custom color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                Box(Modifier.fillMaxWidth().height(56.dp).background(Color(color), ButtonShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, ButtonShape))
                Text("Hue"); Slider(h, { h = it }, valueRange = 0f..360f)
                Text("Saturation"); Slider(s, { s = it }, valueRange = 0f..1f)
                Text("Brightness"); Slider(v, { v = it }, valueRange = 0f..1f)
                Text(String.format(Locale.US, "#%06X", color and 0xFFFFFF), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Use color") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PhotoSizeSelector(
    preset: PhotoPreset, customW: Double, customH: Double,
    onPreset: (PhotoPreset) -> Unit, onCustom: (Double, Double) -> Unit, modifier: Modifier = Modifier,
) {
    var w by remember { mutableStateOf(String.format(Locale.US, "%.0f", customW)) }
    var h by remember { mutableStateOf(String.format(Locale.US, "%.0f", customH)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Text("Photo size", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            PhotoPreset.values().forEach { p -> FilterChip(preset == p, { onPreset(p) }, { Text(p.label) }) }
        }
        if (preset == PhotoPreset.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                listOf(Triple("Width (mm)", w, true), Triple("Height (mm)", h, false)).forEach { (label, value, isW) ->
                    OutlinedTextField(
                        value, { t ->
                            if (isW) w = t else h = t
                            val a = (if (isW) t else w).toDoubleOrNull(); val b = (if (isW) h else t).toDoubleOrNull()
                            if (a != null && b != null && a in 15.0..300.0 && b in 15.0..300.0) onCustom(a, b)
                        },
                        label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

