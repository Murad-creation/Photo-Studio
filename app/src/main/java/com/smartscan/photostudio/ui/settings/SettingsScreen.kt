package com.smartscan.photostudio.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.smartscan.photostudio.BuildConfig
import com.smartscan.photostudio.ui.components.SmartScanTopBar
import com.smartscan.photostudio.ui.theme.*

@Composable
fun SettingsScreen(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("Settings")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x4)) {
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4).selectableGroup()) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
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
            Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text("Photo Studio · Scan. Edit. Save. Share.", style = MaterialTheme.typography.bodyMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
