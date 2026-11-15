package com.smartscan.photostudio.feature.doc

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.feature.nid.CameraCapture
import com.smartscan.photostudio.feature.nid.CropEditor
import com.smartscan.photostudio.feature.nid.LabeledSlider
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import java.io.File
import java.util.Locale

private val stepTitles = listOf("Capture", "Edit pages", "PDF preview", "Export")

@Composable
fun DocScreen(onExit: () -> Unit, onSendToPc: (List<File>) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: DocViewModel = viewModel(viewModelStoreOwner = activity) // keeps pages while navigating away
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }

    fun goBack() {
        when {
            ui.pending != null -> vm.cancelPending()
            ui.step == DocStep.CAPTURE -> when {
                ui.retakeId != null -> vm.cancelRetake()
                ui.pages.isNotEmpty() -> vm.goTo(DocStep.EDIT)
                else -> onExit()
            }
            ui.step == DocStep.EDIT -> if (ui.pages.isNotEmpty()) confirmExit = true else onExit()
            else -> vm.goTo(DocStep.values()[ui.step.ordinal - 1])
        }
    }
    BackHandler { goBack() }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Document Scanner", onBack = { goBack() })
        Column(Modifier.padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Text("Step ${ui.step.ordinal + 1} of 4 · ${stepTitles[ui.step.ordinal]}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (ui.step.ordinal + 1) / 4f }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }
        Spacer(Modifier.height(Spacing.x3))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.step) {
                DocStep.CAPTURE -> CaptureStep(ui, vm)
                DocStep.EDIT -> EditStep(ui, vm)
                DocStep.PREVIEW -> PreviewStep(ui, vm)
                DocStep.EXPORT -> ExportStep(ui, vm, onSendToPc)
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
        ConfirmationDialog("Discard scanned pages?", "All pages you scanned will be removed.", "Discard",
            onConfirm = { confirmExit = false; vm.startOver(); onExit() }, onDismiss = { confirmExit = false }, dismissLabel = "Keep editing")
    }
    if (ui.confirmOverwrite) {
        ConfirmationDialog("Replace existing file?", "A file with this name already exists. Replace it?", "Replace",
            onConfirm = { vm.createFile(overwriteOk = true) }, onDismiss = { vm.dismissOverwrite() })
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, enabled = enabled, shape = ButtonShape, modifier = modifier.heightIn(min = Spacing.minTouchTarget)) { Text(text) }
}

@Composable
private fun PageStrip(ui: DocUiState, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        itemsIndexed(ui.pages, key = { _, p -> p.id }) { i, p ->
            Box(
                Modifier.size(width = 64.dp, height = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(if (i == ui.selected) 3.dp else 1.dp,
                        if (i == ui.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) },
            ) {
                Image(p.thumb.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Text("${i + 1}", Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp),
                    color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CaptureStep(ui: DocUiState, vm: DocViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.onUris(uris)
    }
    val openGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val pending = ui.pending

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        if (pending != null) {
            Text(
                if (pending.autoDetected) "Page edges detected. Drag the corners to fine-tune."
                else "Edges could not be detected automatically. Drag each corner onto the page corner.",
                style = MaterialTheme.typography.bodyMedium,
            )
            CropEditor(pending.source, pending.corners, vm::updateCorners, Modifier.weight(1f).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                SecondaryActionButton("Cancel", { vm.cancelPending() }, Modifier.weight(1f))
                PrimaryActionButton("Crop & continue", { vm.confirmCrop() }, Modifier.weight(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.retakeId != null) "Retaking a page" else "Pages scanned: ${ui.pages.size}",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                )
                if (ui.pages.isNotEmpty() && ui.retakeId == null) PrimaryActionButton("Done (${ui.pages.size})", { vm.goTo(DocStep.EDIT) })
            }
            if (ui.retakeId == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-crop after capture", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(ui.autoCrop, { vm.setAutoCrop(it) })
                }
            }
            CameraCapture(
                onCaptured = { vm.onPhoto(it) },
                onGallery = { openGallery() },
                onError = { vm.reportError(it) },
                frameAspect = 0.707f,
                hint = "Fit the page inside the frame",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun EditStep(ui: DocUiState, vm: DocViewModel) {
    if (ui.pages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(Spacing.screenHorizontal), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyStateView(Icons.Filled.DocumentScanner, "No pages yet", "Scan or import a page to start.")
            PrimaryActionButton("Scan a page", { vm.goTo(DocStep.CAPTURE) })
        }
        return
    }
    val index = ui.selected.coerceIn(0, ui.pages.lastIndex)
    val page = ui.pages[index]
    val p = page.params
    var showOriginal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        PageStrip(ui) { vm.select(it) }
        val shown = if (showOriginal) page.thumb else (page.preview ?: page.thumb)
        Image(shown.asImageBitmap(), "Page ${index + 1} preview", Modifier.fillMaxWidth().height(300.dp), contentScale = ContentScale.Fit)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${index + 1} of ${ui.pages.size}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (showOriginal) "Original" else "Enhanced", style = MaterialTheme.typography.labelMedium)
            Switch(showOriginal, { showOriginal = it }, modifier = Modifier.padding(start = Spacing.x2))
        }
        Text("Filter", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(EnhanceFilter.ORIGINAL to "Original", EnhanceFilter.MAGIC to "Magic Color",
                EnhanceFilter.GRAYSCALE to "Grayscale", EnhanceFilter.BLACK_WHITE to "Black & White").forEach { (f, l) ->
                FilterChip(p.filter == f, { vm.setParams(p.copy(filter = f)) }, { Text(l) })
            }
        }
        LabeledSlider("Brightness", p.brightness, -60f..60f) { vm.setParams(p.copy(brightness = it)) }
        LabeledSlider("Contrast", p.contrast, -50f..50f) { vm.setParams(p.copy(contrast = it)) }
        LabeledSlider("Sharpening", p.sharpen, 0f..100f) { vm.setParams(p.copy(sharpen = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ActionButton("Rotate", { vm.rotate(index) }, Modifier.weight(1f))
            ActionButton("Crop", { vm.recrop(index) }, Modifier.weight(1f))
            ActionButton("Retake", { vm.retake(index) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ActionButton("◀ Move", { vm.move(index, -1) }, Modifier.weight(1f), enabled = index > 0)
            ActionButton("Move ▶", { vm.move(index, 1) }, Modifier.weight(1f), enabled = index < ui.pages.lastIndex)
            ActionButton("Delete", { confirmDelete = true }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ActionButton("Add page", { vm.goTo(DocStep.CAPTURE) }, Modifier.weight(1f))
            ActionButton("Apply to all", { vm.applyToAll() }, Modifier.weight(1f))
        }
        PrimaryActionButton("Next: PDF preview", { vm.goTo(DocStep.PREVIEW) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x4))
    }
    if (confirmDelete) {
        ConfirmationDialog("Delete page ${index + 1}?", "This page will be removed from the document.", "Delete",
            onConfirm = { confirmDelete = false; vm.delete(index) }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun PreviewStep(ui: DocUiState, vm: DocViewModel) {
    if (ui.pages.isEmpty()) { EditStep(ui, vm); return }
    val index = ui.selected.coerceIn(0, ui.pages.lastIndex)
    val page = ui.pages[index]
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var customW by remember { mutableStateOf(String.format(Locale.US, "%.0f", ui.customWMm)) }
    var customH by remember { mutableStateOf(String.format(Locale.US, "%.0f", ui.customHMm)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mm = paperMm(ui)
    val img = page.preview ?: page.thumb
    val aspect = mm?.let { (it.first / it.second).toFloat() } ?: (img.width.toFloat() / img.height)

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            PaperSize.values().forEach { ps -> FilterChip(ui.paper == ps, { vm.setPaper(ps) }, { Text(ps.label) }) }
        }
        if (ui.paper != PaperSize.ORIGINAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!ui.landscape, { vm.setLandscape(false) }, { Text("Portrait") })
                FilterChip(ui.landscape, { vm.setLandscape(true) }, { Text("Landscape") })
            }
        }
        if (ui.paper == PaperSize.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                listOf(Triple("Width (mm)", customW, true), Triple("Height (mm)", customH, false)).forEach { (label, value, isW) ->
                    OutlinedTextField(
                        value, { t ->
                            if (isW) customW = t else customH = t
                            val w = (if (isW) t else customW).toDoubleOrNull(); val h = (if (isW) customH else t).toDoubleOrNull()
                            if (w != null && h != null && w in 50.0..1000.0 && h in 50.0..1000.0) vm.setCustom(w, h)
                        },
                        label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.select(index - 1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
            Text("Page ${index + 1} of ${ui.pages.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton({ vm.select(index + 1) }, enabled = index < ui.pages.lastIndex) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
            TextButton({ scale = 1f; offset = Offset.Zero }) { Text("Reset zoom") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            val sizeMod = if (aspect > maxWidth / maxHeight) Modifier.fillMaxWidth() else Modifier.fillMaxHeight()
            Box(
                sizeMod.aspectRatio(aspect).background(Color.White)
                    .pointerInput(index) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset += pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            ) {
                Image(img.asImageBitmap(), "Page on paper", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        PageStrip(ui) { vm.select(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ActionButton("◀ Move", { vm.move(index, -1) }, Modifier.weight(1f), enabled = index > 0)
            ActionButton("Move ▶", { vm.move(index, 1) }, Modifier.weight(1f), enabled = index < ui.pages.lastIndex)
            ActionButton("Remove", { confirmDelete = true }, Modifier.weight(1f))
        }
        PrimaryActionButton("Next: Export", { vm.goTo(DocStep.EXPORT) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.x1))
    }
    if (confirmDelete) {
        ConfirmationDialog("Remove page ${index + 1}?", "This page will be removed from the document.", "Remove",
            onConfirm = { confirmDelete = false; vm.delete(index) }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun ExportStep(ui: DocUiState, vm: DocViewModel, onSendToPc: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ui.format.mime)) { uri: Uri? ->
        if (uri != null) vm.saveTo(uri)
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.saveTree(uri)
    }
    val ex = ui.exported

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text("${ui.pages.size} page(s) · ${ui.paper.label}${if (ui.paper != PaperSize.ORIGINAL) if (ui.landscape) " landscape" else " portrait" else ""}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(ui.fileName, { vm.setFileName(it) }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Format", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportFormat.values().forEach { f -> FilterChip(ui.format == f, { vm.setFormat(f) }, { Text(f.name) }) }
        }
        if (ui.format != ExportFormat.PDF && ui.pages.size > 1) {
            Text("Each page is saved as its own image file.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Quality (PDF and JPG)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            ExportQuality.values().forEach { q -> FilterChip(ui.quality == q, { vm.setQuality(q) }, { Text(q.label) }) }
        }
        Text("Resolution", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            listOf(150, 300).forEach { d -> FilterChip(ui.dpi == d, { vm.setDpi(d) }, { Text("$d DPI") }) }
        }
        PrimaryActionButton(if (ex == null) "Create file" else "Create again", { vm.createFile() }, Modifier.fillMaxWidth(), )

        if (ex != null) {
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    Text(if (ex.files.size == 1) ex.files[0].name else "${ex.files.size} files", style = MaterialTheme.typography.titleMedium)
                    Text(String.format(Locale.US, "%.2f MB total", ex.totalBytes / 1_048_576.0), style = MaterialTheme.typography.bodyMedium)
                    PrimaryActionButton("Save to device", {
                        if (ex.files.size == 1) saver.launch(ex.files[0].name) else tree.launch(null)
                    }, Modifier.fillMaxWidth())
                    SecondaryActionButton("Share", {
                        val uris = ArrayList(ex.files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
                        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                            type = ex.format.mime
                            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share"))
                    }, Modifier.fillMaxWidth())
                    SecondaryActionButton("Send to PC", { onSendToPc(ex.files) }, Modifier.fillMaxWidth())
                }
            }
            SecondaryActionButton("Start a new document", { vm.startOver() }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.x4))
    }
}
