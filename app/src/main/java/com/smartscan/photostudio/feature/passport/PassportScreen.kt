package com.smartscan.photostudio.feature.passport

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.photostudio.core.ExportFormat
import com.smartscan.photostudio.core.ExportQuality
import com.smartscan.photostudio.core.ShareUtil
import com.smartscan.photostudio.core.formatSize
import com.smartscan.photostudio.feature.nid.CameraCapture
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import java.io.File
import java.util.Locale

private val titles = listOf("Photo", "Edit photo", "Print layout", "Export")

@Composable
fun PassportScreen(onExit: () -> Unit, onSendToPc: (List<File>) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: PassportViewModel = viewModel(viewModelStoreOwner = activity)
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }

    fun goBack() {
        when (ui.step) {
            PassportStep.INPUT -> if (ui.hasPhoto) vm.goTo(PassportStep.EDIT) else onExit()
            PassportStep.EDIT -> confirmExit = true
            PassportStep.LAYOUT -> vm.goTo(PassportStep.EDIT)
            PassportStep.EXPORT -> vm.goTo(PassportStep.LAYOUT)
        }
    }
    BackHandler { goBack() }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Passport Photo Studio", onBack = { goBack() })
        StepHeader(titles[ui.step.ordinal], ui.step.ordinal, 4)
        Spacer(Modifier.height(Spacing.x3))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.step) {
                PassportStep.INPUT -> InputStep(ui, vm)
                PassportStep.EDIT -> EditStep(ui, vm)
                PassportStep.LAYOUT -> LayoutStep(ui, vm)
                PassportStep.EXPORT -> ExportStep(ui, vm, onSendToPc)
            }
            if (ui.busy) ProcessingIndicator("Processing photo…", Modifier.matchParentSize())
        }
        ui.status?.let { StatusBanner(it.text, it.isError) { vm.dismissStatus() } }
    }
    if (confirmExit) {
        ConfirmationDialog("Discard this photo?", "Your edits will be lost.", "Discard",
            onConfirm = { confirmExit = false; vm.discard(); onExit() }, onDismiss = { confirmExit = false }, dismissLabel = "Keep editing")
    }
    if (ui.confirmOverwrite) {
        ConfirmationDialog("Replace existing file?", "A file with this name already exists. Replace it?", "Replace",
            onConfirm = { vm.createFile(overwriteOk = true) }, onDismiss = { vm.dismissOverwrite() })
    }
}

@Composable
private fun InputStep(ui: PassportUiState, vm: PassportViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.onUri(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text("Take a new photo or choose one", style = MaterialTheme.typography.titleMedium)
        Text("Face the camera, keep a neutral expression, and use even light. Your face is never retouched.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CameraCapture(
            onCaptured = { vm.onPhoto(it) },
            onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onError = { vm.reportError(it) },
            frameAspect = 0.78f, hint = "Look straight at the camera", allowFlip = true,
        )
        if (ui.hasPhoto) SecondaryActionButton("Back to editing", { vm.goTo(PassportStep.EDIT) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x4))
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(fmt(value), style = MaterialTheme.typography.labelMedium) }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun EditStep(ui: PassportUiState, vm: PassportViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        val b = ui.before; val a = ui.after
        if (b != null && a != null) BeforeAfterSlider(b, a, Modifier.fillMaxWidth().height(380.dp))
        else Box(Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (!ui.faceFound) Text("No face detected: position the head with the sliders.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Text("Drag the handle to compare the original with the result.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        BackgroundColorPicker(ui.bg, { vm.setBg(it) })
        PhotoSizeSelector(ui.preset, ui.customW, ui.customH, { vm.setPreset(it) }, { w, h -> vm.setCustom(w, h) })

        Text("Position", style = MaterialTheme.typography.titleMedium)
        ValueSlider("Head size", ui.headScale, 0.7f..1.3f, { String.format(Locale.US, "%.0f%%", it * 100) }) { vm.setHeadScale(it) }
        ValueSlider("Move left / right", ui.shiftX, -0.3f..0.3f, { String.format(Locale.US, "%+.0f", it * 100) }) { vm.setShiftX(it) }
        ValueSlider("Move up / down", ui.shiftY, -0.3f..0.3f, { String.format(Locale.US, "%+.0f", it * 100) }) { vm.setShiftY(it) }
        ValueSlider("Head tilt", ui.tilt, -15f..15f, { String.format(Locale.US, "%+.1f°", it) }) { vm.setTilt(it) }
        Text("Light", style = MaterialTheme.typography.titleMedium)
        ValueSlider("Brightness", ui.brightness, -50f..50f, { String.format(Locale.US, "%+.0f", it) }) { vm.setBrightness(it) }
        ValueSlider("Exposure", ui.exposure, -1f..1f, { String.format(Locale.US, "%+.1f EV", it) }) { vm.setExposure(it) }
        ValueSlider("Contrast", ui.contrast, -40f..40f, { String.format(Locale.US, "%+.0f", it) }) { vm.setContrast(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            SecondaryActionButton("Reset position", { vm.resetFraming() }, Modifier.weight(1f))
            SecondaryActionButton("New photo", { vm.newPhoto() }, Modifier.weight(1f))
        }
        PrimaryActionButton("Next: Print layout", { vm.goTo(PassportStep.LAYOUT) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x4))
    }
}

@Composable
private fun LayoutStep(ui: PassportUiState, vm: PassportViewModel) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            SheetType.values().forEach { st -> FilterChip(ui.sheet == st, { vm.setSheet(st) }, { Text(st.label) }) }
            Spacer(Modifier.weight(1f))
            TextButton({ scale = 1f; offset = Offset.Zero }) { Text("Reset zoom") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Copies", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton({ vm.setCopies(ui.copies - 1) }, enabled = ui.copies > 1, shape = ButtonShape, modifier = Modifier.size(Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) { Text("−") }
            Text("${ui.copies}", Modifier.padding(horizontal = Spacing.x4), style = MaterialTheme.typography.titleMedium)
            OutlinedButton({ vm.setCopies(ui.copies + 1) }, shape = ButtonShape, modifier = Modifier.size(Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
        ValueSlider("Spacing", ui.gapMm.toFloat(), 0f..10f, { String.format(Locale.US, "%.1f mm", it) }) { vm.setGap(it.toDouble()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cut lines", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(ui.cutLines, { vm.setCutLines(it) })
        }
        Text(
            if (ui.capacity == 0) "This photo does not fit on the sheet."
            else if (ui.copies > ui.capacity) "Only ${ui.capacity} fit on this sheet (auto-arranged). Reduce spacing or use A4 for more."
            else "${ui.copies} of ${ui.capacity} positions used (auto-arranged).",
            style = MaterialTheme.typography.labelMedium,
            color = if (ui.capacity == 0 || ui.copies > ui.capacity) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFFE6E6E6))) {
            ui.layoutPreview?.let {
                Image(
                    it.asImageBitmap(), "Print sheet preview",
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit,
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        PrimaryActionButton("Next: Export", { vm.goTo(PassportStep.EXPORT) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x1))
    }
}

@Composable
private fun ExportStep(ui: PassportUiState, vm: PassportViewModel, onSendToPc: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ui.format.mime)) { uri -> if (uri != null) vm.saveTo(uri) }
    val ex = ui.exported
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text("What to export", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            FilterChip(ui.target == ExportTarget.PHOTO, { vm.setTarget(ExportTarget.PHOTO) }, { Text("Single photo") })
            FilterChip(ui.target == ExportTarget.SHEET, { vm.setTarget(ExportTarget.SHEET) }, { Text("Print sheet (${ui.copies})") })
        }
        OutlinedTextField(ui.fileName, { vm.setFileName(it) }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Format", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportFormat.values().forEach { f -> FilterChip(ui.format == f, { vm.setFormat(f) }, { Text(f.name) }) }
        }
        Text("Quality (JPG and PDF)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportQuality.values().forEach { q -> FilterChip(ui.quality == q, { vm.setQuality(q) }, { Text(q.label) }) }
        }
        Text("Resolution", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(150, 300, 600).forEach { d -> FilterChip(ui.dpi == d, { vm.setDpi(d) }, { Text("$d DPI") }) }
        }
        PrimaryActionButton(if (ex == null) "Create file" else "Create again", { vm.createFile() }, Modifier.fillMaxWidth())
        if (ex != null) {
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    Text(ex.name, style = MaterialTheme.typography.titleMedium)
                    Text(formatSize(ex.length()), style = MaterialTheme.typography.bodyMedium)
                    PrimaryActionButton("Save to device", { saver.launch(ex.name) }, Modifier.fillMaxWidth())
                    SecondaryActionButton("Share", { ShareUtil.share(ctx, listOf(ex)) }, Modifier.fillMaxWidth())
                    SecondaryActionButton("Send to PC", { onSendToPc(listOf(ex)) }, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(Spacing.x4))
    }
}
