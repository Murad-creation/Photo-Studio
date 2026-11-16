package com.smartscan.photostudio.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartscan.photostudio.core.transfer.TransferUiState
import com.smartscan.photostudio.ui.theme.*

@Composable
private fun StatusRow(icon: ImageVector, label: String, value: String, ok: Boolean?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Icon(icon, null, tint = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant })
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun TransferStatusCard(s: TransferUiState, modifier: Modifier = Modifier) {
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
            StatusRow(if (s.lan.connected) Icons.Filled.Wifi else Icons.Filled.WifiOff, "Wi-Fi",
                if (s.lan.connected) "Connected · ${s.lan.ip}" else "Not connected", s.lan.connected)
            StatusRow(Icons.Filled.Dns, "PC receiver",
                when { s.starting -> "Starting…"; s.running -> "On · permanent address"; else -> "Off" }, if (s.running) true else null)
            StatusRow(Icons.Filled.Computer, "PC",
                when {
                    s.pcOnline -> "${s.pcAgent ?: "Browser"} · ${s.pcIp}"
                    s.running -> "Waiting for the PC to open the address…"
                    else -> "—"
                }, if (s.pcOnline) true else null)
        }
    }
}

@Composable
fun QRCodeCard(qr: Bitmap?, url: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.x4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            Text("Your PC address (permanent)", style = MaterialTheme.typography.titleMedium)
            qr?.let {
                Image(it.asImageBitmap(), "QR code for the pairing link", Modifier.size(220.dp).background(Color.White).padding(8.dp))
            }
            Text(url, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            SecondaryActionButton("Copy URL", onCopy, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ErrorStateView(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (onRetry != null) SecondaryActionButton("Retry", onRetry, Modifier.fillMaxWidth())
        }
    }
}
