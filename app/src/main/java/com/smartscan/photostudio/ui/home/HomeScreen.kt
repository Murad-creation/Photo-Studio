package com.smartscan.photostudio.ui.home

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartscan.photostudio.core.LibraryFile
import com.smartscan.photostudio.core.LibraryFiles
import com.smartscan.photostudio.navigation.Routes
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onOpenSettings: () -> Unit, onOpenFile: (String) -> Unit) {
    val ctx = LocalContext.current
    val files by produceState(emptyList<LibraryFile>()) {
        value = withContext(Dispatchers.IO) { LibraryFiles.list(ctx) }
    }
    val scans = files.filter { it.folder == "nid" || it.folder == "docs" }.take(3)
    val projects = files.filter { it.folder == "passport" }.take(3)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x6),
    ) {
        Header(onOpenSettings)
        HeroCard(onStart = { onNavigate(Routes.DOC) })

        Section("Tools") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3), modifier = Modifier.height(IntrinsicSize.Max)) {
                FeatureCard("NID Scanner", "Front and back on one A4 page", Icons.Filled.Badge, Emerald, { onNavigate(Routes.NID) }, Modifier.weight(1f).fillMaxHeight())
                FeatureCard("Document Scanner", "Multi-page PDFs", Icons.Filled.DocumentScanner, AccentBlue, { onNavigate(Routes.DOC) }, Modifier.weight(1f).fillMaxHeight())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3), modifier = Modifier.height(IntrinsicSize.Max)) {
                FeatureCard("Passport Photo", "Size, background, print sheet", Icons.Filled.Portrait, AccentPurple, { onNavigate(Routes.PASSPORT) }, Modifier.weight(1f).fillMaxHeight())
                FeatureCard("Send to PC", "Local Wi-Fi transfer", Icons.Filled.Computer, AccentOrange, { onNavigate(Routes.PC) }, Modifier.weight(1f).fillMaxHeight())
            }
        }

        Section("Recent Projects") {
            if (projects.isEmpty()) EmptyStateView(Icons.Filled.FolderOpen, "No projects yet", "Passport photos you save will appear here.")
            else projects.forEach { f -> RecentProjectCard(f, { onOpenFile(f.rel) }, Modifier.fillMaxWidth()) }
        }
        Section("Recently Scanned") {
            if (scans.isEmpty()) EmptyStateView(Icons.Filled.History, "Nothing scanned yet", "Your latest scans will show up here.")
            else scans.forEach { f -> RecentProjectCard(f, { onOpenFile(f.rel) }, Modifier.fillMaxWidth()) }
        }
        Section("Quick Actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                SecondaryActionButton("Open My Files", { onNavigate(Routes.FILES) }, Modifier.weight(1f))
                SecondaryActionButton("Send to PC", { onNavigate(Routes.PC) }, Modifier.weight(1f))
            }
        }
        StorageCard()
        Spacer(Modifier.height(Spacing.x2))
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, ButtonShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CenterFocusStrong, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("Smart Scan & Photo Studio", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Welcome back", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(Spacing.minTouchTarget)) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun HeroCard(onStart: () -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = Emerald), modifier = Modifier.fillMaxWidth()) {
        Box {
            Icon(Icons.Filled.DocumentScanner, null, tint = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.x4).size(110.dp))
            Column(Modifier.padding(Spacing.x5), verticalArrangement = Arrangement.spacedBy(Spacing.x4)) {
                Text("What would you like to create today?", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.fillMaxWidth(0.7f))
                PrimaryActionButton("Start Scanning", onStart, container = Color.White, content = Emerald)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun StorageCard() {
    val info = remember {
        val s = StatFs(Environment.getDataDirectory().path)
        val free = s.availableBytes / 1_073_741_824.0
        val total = s.totalBytes / 1_073_741_824.0
        "%.1f GB free of %.1f GB".format(free, total) to (1f - (s.availableBytes.toFloat() / s.totalBytes))
    }
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { info.second }, modifier = Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Text(info.first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
