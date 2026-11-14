package com.smartscan.photostudio.feature.nid

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.ui.components.PrimaryActionButton
import com.smartscan.photostudio.ui.components.SecondaryActionButton
import com.smartscan.photostudio.ui.components.SmartScanTopBar
import com.smartscan.photostudio.ui.theme.*
import java.util.Locale

private val stepTitles = listOf("Front side", "Back side", "Enhance", "A4 preview", "Export")

@Composable
fun NidScreen(onExit: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: NidViewModel = viewModel(viewModelStoreOwner = activity) // keeps progress while navigating away
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    val hasWork = ui.front != null || ui.back != null || ui.pending != null

    fun goBack() {
        when {
            ui.pending != null -> vm.cancelPending()
            ui.step == NidStep.FRONT -> if (hasWork) confirmExit = true else onExit()
            else -> vm.goTo(NidStep.values()[ui.step.ordinal - 1])
        }
    }
    BackHandler { goBack() }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("NID Scanner", onBack = { goBack() })
        Column(Modifier.padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Text("Step ${ui.step.ordinal + 1} of 5 · ${stepTitles[ui.step.ordinal]}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (ui.step.ordinal + 1) / 5f }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }
        Spacer(Modifier.height(Spacing.x3))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.step) {
                NidStep.FRONT -> CaptureStep(Side.FRONT, ui, vm)
                NidStep.BACK -> CaptureStep(Side.BACK, ui, vm)
                NidStep.ENHANCE -> EnhanceStep(ui, vm)
                NidStep.PREVIEW -> PreviewStep(ui, vm)
                NidStep.EXPORT -> ExportStep(ui, vm)
            }
            if (ui.busy) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = CardShape, tonalElevation = 6.dp) {
                        Row(Modifier.padding(Spacing.x5), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text("Processing…")
                        }
                    }
                }
            }
        }
        ui.status?.let { st ->
            Surface(
                color = if (st.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.dismissStatus() }) { Text("OK") }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            shape = DialogShape,
            title = { Text("Discard this scan?") },
            text = { Text("Your captured card images will be removed.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.startOver(); onExit() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } },
        )
    }
    if (ui.confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { vm.dismissOverwrite() },
            shape = DialogShape,
            title = { Text("Replace existing file?") },
            text = { Text("A file named \"${ui.fileName}.${ui.format.ext}\" already exists. Replace it?") },
            confirmButton = { TextButton(onClick = { vm.createFile(overwriteOk = true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { vm.dismissOverwrite() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CaptureStep(side: Side, ui: NidUiState, vm: NidViewModel) {
    val label = if (side == Side.FRONT) "front" else "back"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onSourceUri(side, uri)
    }
    val openGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val captured = if (side == Side.FRONT) ui.front else ui.back
    val pending = ui.pending?.takeIf { it.side == side }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        when {
            pending != null -> {
                Text(
                    if (pending.autoDetected) "Card edges detected. Drag the corners to fine-tune."
                    else "Edges could not be detected automatically. Drag each corner onto the card's corner.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                CropEditor(pending.source, pending.corners, vm::updateCorners, Modifier.weight(1f).fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    SecondaryActionButton("Cancel", { vm.cancelPending() }, Modifier.weight(1f))
                    PrimaryActionButton("Crop & continue", { vm.confirmCrop() }, Modifier.weight(1f))
                }
            }
            captured != null -> {
                Text("The $label side is ready.", style = MaterialTheme.typography.titleMedium)
                Image(captured.asImageBitmap(), "Captured $label side", Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    SecondaryActionButton("Retake", { vm.retake(side) }, Modifier.weight(1f))
                    PrimaryActionButton("Next", {
                        vm.goTo(if (side == Side.FRONT) NidStep.BACK else NidStep.ENHANCE)
                    }, Modifier.weight(1f))
                }
            }
            else -> {
                Text("Capture the $label of the card", style = MaterialTheme.typography.titleMedium)
                CameraCapture(
                    onCaptured = { vm.onPhoto(side, it) },
                    onGallery = { openGallery() },
                    onError = { vm.reportError(it) },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun EnhanceStep(ui: NidUiState, vm: NidViewModel) {
    var side by remember { mutableStateOf(Side.FRONT) }
    var showOriginal by remember { mutableStateOf(false) }
    val original = if (side == Side.FRONT) ui.frontSmall else ui.backSmall
    val processed = if (side == Side.FRONT) ui.previewFront else ui.previewBack
    val shown = if (showOriginal) original else (processed ?: original)
    val p = ui.params

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            FilterChip(side == Side.FRONT, { side = Side.FRONT }, { Text("Front") })
            FilterChip(side == Side.BACK, { side = Side.BACK }, { Text("Back") })
        }
        shown?.let { Image(it.asImageBitmap(), "Card preview", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Before / After", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (showOriginal) "Original" else "Enhanced", style = MaterialTheme.typography.labelMedium)
            Switch(showOriginal, { showOriginal = it }, modifier = Modifier.padding(start = Spacing.x2))
        }
        Text("Filter", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(EnhanceFilter.ORIGINAL to "Original", EnhanceFilter.AUTO to "Auto Enhance", EnhanceFilter.MAGIC to "Magic Color").forEach { (f, l) ->
                FilterChip(p.filter == f, { vm.setParams(p.copy(filter = f)) }, { Text(l) })
            }
        }
        LabeledSlider("Brightness", p.brightness, -60f..60f) { vm.setParams(p.copy(brightness = it)) }
        LabeledSlider("Contrast", p.contrast, -50f..50f) { vm.setParams(p.copy(contrast = it)) }
        LabeledSlider("Sharpening", p.sharpen, 0f..100f) { vm.setParams(p.copy(sharpen = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            OutlinedButton({ vm.rotate(side) }, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget)) {
                Icon(Icons.Filled.RotateRight, null); Text("  Rotate")
            }
            OutlinedButton({ vm.retake(side) }, shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget)) {
                Text("Re-crop / retake")
            }
        }
        PrimaryActionButton("Next: A4 preview", { vm.goTo(NidStep.PREVIEW) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x4))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("${value.toInt()}", style = MaterialTheme.typography.labelMedium) }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun PreviewStep(ui: NidUiState, vm: NidViewModel) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val pf = ui.previewFront; val pb = ui.previewBack
    val shownMm = if (pf != null && pb != null) A4Layout.fitCardWidthMm(pf, pb, ui.cardWidthMm) else ui.cardWidthMm

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            FilterChip(ui.cardWidthMm == A4Layout.ACTUAL_CARD_WIDTH_MM, { vm.setCardWidth(A4Layout.ACTUAL_CARD_WIDTH_MM) }, { Text("Actual size") })
            FilterChip(ui.cardWidthMm == A4Layout.LARGE_CARD_WIDTH_MM, { vm.setCardWidth(A4Layout.LARGE_CARD_WIDTH_MM) }, { Text("Large") })
            TextButton({ scale = 1f; offset = Offset.Zero }) { Text("Reset zoom") }
        }
        Text(
            String.format(Locale.US, "Card width %.1f mm · gap 0.5 in (12.7 mm) · pinch to zoom", shownMm),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            ui.a4Preview?.let {
                Image(
                    it.asImageBitmap(), "A4 page preview",
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset += pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit,
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        PrimaryActionButton("Next: Export", { vm.goTo(NidStep.EXPORT) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x2))
    }
}

@Composable
private fun ExportStep(ui: NidUiState, vm: NidViewModel) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ui.format.mime)) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val ex = ui.exported

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        OutlinedTextField(ui.fileName, { vm.setFileName(it) }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Format", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportFormat.values().forEach { f -> FilterChip(ui.format == f, { vm.setFormat(f) }, { Text(f.name) }) }
        }
        Text("Quality (JPG)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportQuality.values().forEach { q -> FilterChip(ui.quality == q, { vm.setQuality(q) }, { Text(q.label) }) }
        }
        Text("Resolution", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(150, 300).forEach { d -> FilterChip(ui.dpi == d, { vm.setDpi(d) }, { Text("$d DPI") }) }
        }
        PrimaryActionButton(if (ex == null) "Create file" else "Create again", { vm.createFile() }, Modifier.fillMaxWidth())

        if (ex != null) {
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    Text(ex.file.name, style = MaterialTheme.typography.titleMedium)
                    Text(String.format(Locale.US, "%.2f MB", ex.sizeBytes / 1_048_576.0), style = MaterialTheme.typography.bodyMedium)
                    PrimaryActionButton("Save to device", { saver.launch(ex.file.name) }, Modifier.fillMaxWidth())
                    SecondaryActionButton("Share", {
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", ex.file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = ex.format.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share"))
                    }, Modifier.fillMaxWidth())
                    OutlinedButton({}, enabled = false, shape = ButtonShape, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.buttonHeight)) {
                        Text("Send to PC")
                    }
                    Text("Send to PC arrives with the Wi-Fi transfer phase.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SecondaryActionButton("Start a new scan", { vm.startOver() }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.x4))
    }
}
