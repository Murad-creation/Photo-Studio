package com.smartscan.photostudio.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.smartscan.photostudio.ui.components.*

@Composable
fun MyFilesScreen() {
    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("My Files")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateView(Icons.Filled.FolderOpen, "No files yet", "Scans, documents and passport photos you save will be listed here.")
        }
    }
}
