package com.smartscan.photostudio.feature.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartscan.photostudio.core.QrCode
import com.smartscan.photostudio.core.formatSize
import com.smartscan.photostudio.core.transfer.TransferManager
import com.smartscan.photostudio.core.transfer.TransferQueue
import com.smartscan.photostudio.data.SettingsRepository
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TransferScreen(onBack: () -> Unit, onOpenFiles: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by TransferManager.state.collectAsStateWithLifecycle()
    val queue by TransferQueue.files.collectAsStateWithLifecycle()
    var localError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { TransferManager.refreshNetwork(ctx) }
    val qr = remember(state.url) { state.url?.let { QrCode.bitmap(it) } }

    // While a session runs, the list shown is the session's own list.
    val names = if (state.running || state.names.isNotEmpty()) state.names else queue.map { it.name }
    val sizes = if (state.running || state.names.isNotEmpty()) state.sizes else queue.map { it.length() }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Send to PC", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            TransferStatusCard(state)

            if (!state.lan.connected) {
                ErrorStateView("Wi-Fi not detected. Connect the phone to the same Wi-Fi network as your PC (or turn on your phone's hotspot and connect the PC to it).",
                    onRetry = { TransferManager.refreshNetwork(ctx) })
            }

            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    Text("Selected files", style = MaterialTheme.typography.titleMedium)
                    if (names.isEmpty()) {
                        EmptyStateView(Icons.Filled.FolderOpen, "No files selected",
                            "Export a scan or photo and tap Send to PC, or choose a file in My Files.")
                        SecondaryActionButton("Open My Files", onOpenFiles, Modifier.fillMaxWidth())
                    } else {
                        names.forEachIndexed { i, n ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                                Icon(if (i in state.doneIdx) Icons.Filled.CheckCircle else Icons.Filled.InsertDriveFile, null,
                                    tint = if (i in state.doneIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(n, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(formatSize(sizes.getOrElse(i) { 0L }) + if (i in state.doneIdx) " · downloaded" else "",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            val url = state.url
            if (state.running && url != null) {
                QRCodeCard(qr, url, onCopy = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Transfer link", url)); copied = true
                })
                if (copied) Text("Link copied.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("The PC must be on the same Wi-Fi. Type the address into the PC's browser (a QR scanner on another device also works). " +
                    "The link works only for this session and contains a secret code.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val active = state.activeIdx
            if (active != null) {
                Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                        Text("Sending ${names.getOrNull(active) ?: "file"}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { if (state.total > 0) (state.sent.toFloat() / state.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Text("${formatSize(state.sent)} of ${formatSize(state.total)}", style = MaterialTheme.typography.labelMedium)
                        SecondaryActionButton("Cancel transfer", { TransferManager.cancelTransfer() }, Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.allDone) {
                Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(Spacing.x4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Download complete. All files reached the PC.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            localError?.let { ErrorStateView(it, onRetry = null) }
            state.message?.let { m -> StatusBanner(m, state.messageIsError) { TransferManager.dismissMessage() } }

            if (!state.running) {
                PrimaryActionButton("Generate QR & start session", {
                    localError = null; copied = false
                    scope.launch {
                        val min = SettingsRepository(ctx).settings.first().sessionMin
                        TransferManager.start(ctx, queue, min)?.let { localError = it }
                    }
                }, Modifier.fillMaxWidth())
            } else {
                SecondaryActionButton("Stop server", { TransferManager.stop() }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Spacing.x4))
        }
    }
}
