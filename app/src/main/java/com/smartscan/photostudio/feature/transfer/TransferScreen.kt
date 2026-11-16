package com.smartscan.photostudio.feature.transfer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartscan.photostudio.core.QrCode
import com.smartscan.photostudio.core.formatSize
import com.smartscan.photostudio.core.transfer.OutboxItem
import com.smartscan.photostudio.core.transfer.TransferManager
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TransferScreen(onBack: () -> Unit, onOpenFiles: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by TransferManager.state.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { TransferManager.refreshNetwork(ctx); delay(3000) } }
    val qr = remember(state.url) { state.url?.let { QrCode.bitmap(it) } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TransferManager.start(ctx) // runs with or without the notification permission
    }
    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else TransferManager.start(ctx)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val files = withContext(Dispatchers.IO) { copyToCache(ctx, uris) }
            TransferManager.send(ctx, files)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Send to PC", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.x4), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PC receiver", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.running) "On. Files you send go straight to the open PC page."
                            else "Off. Turn on once; the PC address never changes.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(state.running || state.starting, { on -> if (on) turnOn() else TransferManager.stop() })
                }
            }

            TransferStatusCard(state)

            if (!state.lan.connected) {
                ErrorStateView(
                    "Wi-Fi not detected. Connect the phone to the same Wi-Fi network as your PC (or turn on the phone's hotspot and connect the PC to it).",
                    onRetry = { TransferManager.refreshNetwork(ctx) },
                )
            }
            state.message?.let { m -> StatusBanner(m, state.messageIsError) { TransferManager.dismissMessage() } }

            val url = state.url
            if (state.running && url != null) {
                QRCodeCard(qr, url, onCopy = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PC address", url)); copied = true
                })
                if (copied) Text("Address copied.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                state.portNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Text(
                    "On the PC, open this address once and bookmark it. Keep the page open: whenever you tap Send to PC here, " +
                        "the file downloads on the PC by itself. If your router gives the phone a new IP address the link changes; " +
                        "reserving an IP for the phone in the router keeps it fixed.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryActionButton("Reset address (new secret link)", { confirmReset = true }, Modifier.fillMaxWidth())
            }

            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    Text("Files for the PC", style = MaterialTheme.typography.titleMedium)
                    if (state.items.isEmpty()) {
                        EmptyStateView(Icons.Filled.FolderOpen, "Nothing sent yet",
                            "Tap Send to PC in an export screen or in My Files, or choose files below.")
                    } else {
                        state.items.reversed().forEach { item -> OutboxRow(item, state.pcOnline) }
                    }
                    if (state.items.any { it.active }) {
                        SecondaryActionButton("Cancel transfer", { TransferManager.cancelTransfer() }, Modifier.fillMaxWidth())
                    }
                    PrimaryActionButton("Choose files to send", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                        SecondaryActionButton("Open My Files", onOpenFiles, Modifier.weight(1f))
                        if (state.items.any { it.delivered }) SecondaryActionButton("Clear finished", { TransferManager.clearFinished() }, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.x4))
        }
    }

    if (confirmReset) {
        ConfirmationDialog(
            "Reset the PC address?",
            "The current link stops working. You will need to open the new address on the PC once.",
            "Reset",
            onConfirm = { confirmReset = false; TransferManager.resetAddress(ctx) },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun OutboxRow(item: OutboxItem, pcOnline: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            Icon(
                if (item.delivered) Icons.Filled.CheckCircle else Icons.Filled.InsertDriveFile, null,
                tint = if (item.delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    formatSize(item.size) + " · " + when {
                        item.delivered -> "received on the PC"
                        item.active -> "sending ${formatSize(item.sent)}"
                        pcOnline -> "waiting, the PC will download it"
                        else -> "waiting for the PC to open the address"
                    },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.active) {
                IconButton({ TransferManager.removeItem(item.id) }, Modifier.size(Spacing.minTouchTarget)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove from list")
                }
            }
        }
        if (item.active) {
            LinearProgressIndicator(
                progress = { if (item.size > 0) (item.sent.toFloat() / item.size).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
    }
}

/** Copies files chosen from other apps into the cache so the receiver can serve them. */
private fun copyToCache(ctx: Context, uris: List<Uri>): List<File> {
    val dir = File(ctx.cacheDir, "transfer").apply { mkdirs() }
    return uris.mapNotNull { uri ->
        runCatching {
            var name = "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            var target = File(dir, safe)
            var n = 1
            while (target.exists()) { target = File(dir, "${n++}_$safe") }
            ctx.contentResolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } }
            target.takeIf { it.length() > 0 }
        }.getOrNull()
    }
}
