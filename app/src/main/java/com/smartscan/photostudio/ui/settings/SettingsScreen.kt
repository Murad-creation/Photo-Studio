package com.smartscan.photostudio.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartscan.photostudio.BuildConfig
import com.smartscan.photostudio.core.EnhanceFilter
import com.smartscan.photostudio.core.ExportFormat
import com.smartscan.photostudio.core.ExportQuality
import com.smartscan.photostudio.core.LibraryFiles
import com.smartscan.photostudio.core.formatSize
import com.smartscan.photostudio.data.AppSettings
import com.smartscan.photostudio.data.PhotoPreset
import com.smartscan.photostudio.data.SettingsRepository
import com.smartscan.photostudio.feature.doc.PaperSize
import com.smartscan.photostudio.ui.components.BackgroundColorPicker
import com.smartscan.photostudio.ui.components.ConfirmationDialog
import com.smartscan.photostudio.ui.components.SecondaryActionButton
import com.smartscan.photostudio.ui.components.SmartScanTopBar
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val s by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) { scope.launch { block() } }

    var storageKey by remember { mutableIntStateOf(0) }
    var storage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(storageKey) {
        storage = withContext(Dispatchers.IO) {
            LibraryFiles.dirSize(LibraryFiles.root(ctx)) to LibraryFiles.dirSize(ctx.cacheDir)
        }
    }
    var confirmTemp by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Settings")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            SettingsCard("Appearance") {
                Column(Modifier.selectableGroup()) {
                    listOf(ThemeMode.LIGHT to "Light Mode", ThemeMode.DARK to "Dark Mode", ThemeMode.SYSTEM to "System Default").forEach { (m, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)
                                .selectable(selected = mode == m, role = Role.RadioButton, onClick = { onChange(m) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null)
                            Text(label, Modifier.padding(start = Spacing.x3), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            SettingsCard("Scanning") {
                Label("Default filter")
                ChipRow(
                    listOf(EnhanceFilter.ORIGINAL to "Original", EnhanceFilter.MAGIC to "Magic Color",
                        EnhanceFilter.GRAYSCALE to "Grayscale", EnhanceFilter.BLACK_WHITE to "Black & White"),
                    s.defaultFilter,
                ) { run { repo.setDefaultFilter(it) } }
                SwitchRow("Auto crop after capture", s.autoCrop) { run { repo.setAutoCrop(it) } }
                Label("Default paper size")
                ChipRow(PaperSize.values().filter { it != PaperSize.CUSTOM }.map { it to it.label }, s.defaultPaper) { run { repo.setPaper(it) } }
            }

            SettingsCard("Photo Studio") {
                Label("Default photo size")
                ChipRow(PhotoPreset.values().filter { it != PhotoPreset.CUSTOM }.map { it to it.label }, s.photoPreset) { run { repo.setPhotoPreset(it) } }
                Label("Default background color")
                BackgroundColorPicker(s.photoBg, { run { repo.setPhotoBg(it) } })
            }

            SettingsCard("Export") {
                Label("Default format")
                ChipRow(ExportFormat.values().map { it to it.name }, s.format) { run { repo.setFormat(it) } }
                Label("Default quality")
                ChipRow(ExportQuality.values().map { it to it.label }, s.quality) { run { repo.setQuality(it) } }
                Label("Default resolution")
                ChipRow(listOf(150, 300).map { it to "$it DPI" }, s.dpi) { run { repo.setDpi(it) } }
            }

            SettingsCard("Storage") {
                Text("Save location", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Files you create are kept in the app's private library. \"Save to device\" lets you choose any folder.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                storage?.let { (lib, tmp) ->
                    Text("Library: ${formatSize(lib)} · Temporary files: ${formatSize(tmp)}", style = MaterialTheme.typography.bodyMedium)
                }
                SecondaryActionButton("Delete temporary files", { confirmTemp = true }, Modifier.fillMaxWidth())
            }

            SettingsCard("Transfer") {
                Label("Session timeout")
                ChipRow(listOf(5, 10, 30).map { it to "$it min" }, s.sessionMin) { run { repo.setSessionMin(it) } }
                Text(
                    "Send to PC starts a temporary server on your Wi-Fi. It needs a secret link, and stops when the session ends.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard("Privacy & Security") {
                Text(
                    "Local processing: scanning, enhancement, background removal and PDF creation all run on this device. " +
                        "Nothing is uploaded, and the app has no analytics or ads.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryActionButton("Privacy policy", { showPolicy = true }, Modifier.fillMaxWidth())
                SecondaryActionButton("Delete temporary data", { confirmTemp = true }, Modifier.fillMaxWidth())
            }

            SettingsCard("About") {
                Text("Photo Studio · Smart Scan & Photo Studio", style = MaterialTheme.typography.bodyLarge)
                Text("Scan. Edit. Save. Share.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("Developer: Photo Studio project", style = MaterialTheme.typography.bodyMedium)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(Spacing.x4))
        }
    }

    if (confirmTemp) {
        ConfirmationDialog(
            "Delete temporary files?",
            "Cached images and pages of a scan you have not exported yet will be removed. Saved files in My Files are not touched.",
            "Delete",
            onConfirm = {
                confirmTemp = false
                scope.launch {
                    withContext(Dispatchers.IO) { LibraryFiles.clearDir(ctx.cacheDir) }
                    note = "Temporary files deleted."
                    storageKey++
                }
            },
            onDismiss = { confirmTemp = false },
        )
    }
    if (showPolicy) {
        AlertDialog(
            onDismissRequest = { showPolicy = false },
            shape = DialogShape,
            title = { Text("Privacy policy") },
            text = {
                Text(
                    "Photo Studio works offline. Your ID cards, documents and photos are processed only on this device and are never sent to any server. " +
                        "The app collects no personal data and contains no analytics or advertising. " +
                        "Camera access is used only to take the pictures you ask for. " +
                        "Send to PC opens a temporary web server on your local Wi-Fi, protected by a random link, that serves only the files you selected and stops automatically.",
                )
            },
            confirmButton = { TextButton({ showPolicy = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        options.forEach { (value, label) -> FilterChip(selected == value, { onSelect(value) }, { Text(label) }) }
    }
}
