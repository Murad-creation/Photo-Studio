package com.smartscan.photostudio.ui.placeholder

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.smartscan.photostudio.ui.components.*

/** Honest stand-in until the module's phase is built. */
@Composable
fun ModulePlaceholderScreen(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar(title, onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateView(Icons.Filled.Construction, "Not built yet", "$title is scheduled for a later implementation phase.")
        }
    }
}
