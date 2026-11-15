package com.smartscan.photostudio.ui.files

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.unit.dp
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.data.SettingsRepository
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun resolveLibrary(ctx: android.content.Context, rel: String): LibraryFile? {
    val base = LibraryFiles.root(ctx).canonicalFile
    val f = File(base, rel).canonicalFile
    if (!f.path.startsWith(base.path + File.separator) || !f.isFile) return null
    return LibraryFile(f, f.parentFile?.name ?: return null)
}

/** Full-screen viewer for a saved PDF or image: zoom, page navigation, thumbnails, view rotation and file actions. */
@Composable
fun DocumentPreviewScreen(rel: String, onBack: () -> Unit, onSendToPc: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val lib = remember(rel) { resolveLibrary(ctx, rel) }
    if (lib == null) {
        Column(Modifier.fillMaxSize()) {
            SmartScanTopBar("Preview", onBack = onBack)
            EmptyStateView(Icons.Filled.ErrorOutline, "File not found", "This file was moved or deleted.", Modifier.padding(top = Spacing.x8))
        }
        return
    }
    val pdf = remember(lib.file.path) { if (lib.isPdf) runCatching { PdfDoc(lib.file) }.getOrNull() else null }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    val pageCount = if (lib.isPdf) (pdf?.pageCount ?: 0) else 1
    var page by remember { mutableIntStateOf(0) }
    var rotation by remember { mutableIntStateOf(0) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val bmp by produceState<Bitmap?>(null, lib.file.path, page) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (lib.isPdf) pdf?.render(page, 1400) else Thumbs.decodeSampled(lib.file, 2000) }.getOrNull()
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ShareUtil.mimeOf(lib.file))) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> lib.file.inputStream().use { it.copyTo(o) } } != null }.getOrDefault(false)
            }
            status = if (ok) "Saved to your device." else "Could not save the file."
        }
    }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar(lib.name, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF202020))) {
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(), "Page ${page + 1}",
                    Modifier.fillMaxSize().pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset += pan }
                    }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y, rotationZ = rotation.toFloat()),
                    contentScale = ContentScale.Fit,
                )
            } else if (lib.isPdf && pdf == null) {
                Text("This PDF could not be opened.", Modifier.align(Alignment.Center), color = Color.White)
            } else CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (pageCount > 1) {
            Row(Modifier.padding(horizontal = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                Text("Page ${page + 1} of $pageCount", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton({ page++ }, enabled = page < pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
            }
            val p = pdf
            if (p != null) LazyRow(Modifier.padding(horizontal = Spacing.screenHorizontal), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                items((0 until pageCount).toList()) { i ->
                    val t by produceState<Bitmap?>(null, i) { value = withContext(Dispatchers.IO) { runCatching { p.render(i, 160) }.getOrNull() } }
                    Box(
                        Modifier.size(width = 56.dp, height = 74.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                            .border(if (i == page) 3.dp else 1.dp, if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable { page = i },
                    ) { t?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
                }
            }
        }
        status?.let { StatusBanner(it, it.startsWith("Could")) { status = null } }
        Column(Modifier.padding(Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                SecondaryActionButton("Share", { ShareUtil.share(ctx, listOf(lib.file)) }, Modifier.weight(1f))
                SecondaryActionButton("Send to PC", { onSendToPc(listOf(lib.file)) }, Modifier.weight(1f))
                SecondaryActionButton("Save", { saver.launch(lib.name) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                SecondaryActionButton("Rotate view", { rotation = (rotation + 90) % 360 }, Modifier.weight(1f))
                SecondaryActionButton("Rename", { renameOpen = true }, Modifier.weight(1f))
                SecondaryActionButton("Delete", { deleteOpen = true }, Modifier.weight(1f))
            }
        }
    }

    if (renameOpen) {
        var text by remember { mutableStateOf(lib.file.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renameOpen = false }, shape = DialogShape, title = { Text("Rename file") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name (.${lib.ext} is kept)") }) },
            confirmButton = {
                TextButton(onClick = {
                    val old = lib.rel
                    LibraryFiles.rename(lib, text).fold(
                        onSuccess = { nf -> scope.launch { repo.renameFavorite(old, "${lib.folder}/${nf.name}") }; renameOpen = false; onBack() },
                        onFailure = { status = it.message ?: "Could not rename."; renameOpen = false },
                    )
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
    if (deleteOpen) {
        ConfirmationDialog("Delete ${lib.name}?", "This permanently removes the file from the app.", "Delete",
            onConfirm = {
                pdf?.close()
                if (lib.file.delete()) { scope.launch { repo.removeFavorite(lib.rel) }; deleteOpen = false; onBack() }
                else { status = "Could not delete the file."; deleteOpen = false }
            }, onDismiss = { deleteOpen = false })
    }
}
