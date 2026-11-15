package com.smartscan.photostudio.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartscan.photostudio.core.LibraryFile
import com.smartscan.photostudio.core.Thumbs
import com.smartscan.photostudio.core.formatDate
import com.smartscan.photostudio.core.formatSize
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LibraryFile.rel: String get() = "$folder/${file.name}"

enum class FileAction { OPEN, FAVORITE, SHARE, SEND_PC, RENAME, DETAILS, DELETE }

@Composable
fun FileThumb(f: LibraryFile, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, f.file.path, f.modified) {
        value = withContext(Dispatchers.IO) { Thumbs.load(f.file, 320) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (f.isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FileMenu(expanded: Boolean, favorite: Boolean, onDismiss: () -> Unit, onAction: (FileAction) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        listOf(
            FileAction.OPEN to "Open", FileAction.FAVORITE to (if (favorite) "Remove from favorites" else "Add to favorites"),
            FileAction.SHARE to "Share", FileAction.SEND_PC to "Send to PC", FileAction.RENAME to "Rename",
            FileAction.DETAILS to "Details", FileAction.DELETE to "Delete",
        ).forEach { (a, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); onAction(a) }) }
    }
}

@Composable
fun FileListItem(f: LibraryFile, favorite: Boolean, onAction: (FileAction) -> Unit, showMenu: Boolean = true, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.fillMaxWidth().clickable { onAction(FileAction.OPEN) }) {
        Row(Modifier.padding(Spacing.x3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            FileThumb(f, Modifier.size(56.dp).clip(ButtonShape))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatSize(f.size)} · ${formatDate(f.modified)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (favorite) Icon(Icons.Filled.Star, "Favorite", tint = MaterialTheme.colorScheme.primary)
            if (showMenu) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(Spacing.minTouchTarget)) { Icon(Icons.Filled.MoreVert, "More actions") }
                FileMenu(menu, favorite, { menu = false }, onAction)
            }
        }
    }
}

@Composable
fun FileGridItem(f: LibraryFile, favorite: Boolean, onAction: (FileAction) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.clickable { onAction(FileAction.OPEN) }) {
        Column {
            Box {
                FileThumb(f, Modifier.fillMaxWidth().height(130.dp))
                if (favorite) Icon(Icons.Filled.Star, "Favorite", Modifier.align(Alignment.TopStart).padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(Spacing.minTouchTarget)) { Icon(Icons.Filled.MoreVert, "More actions") }
                    FileMenu(menu, favorite, { menu = false }, onAction)
                }
            }
            Column(Modifier.padding(Spacing.x3)) {
                Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(f.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RecentProjectCard(f: LibraryFile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(shape = CardShape, colors = ComponentStyles.cardColors(), modifier = modifier.width(150.dp).clickable(onClick = onClick)) {
        Column {
            FileThumb(f, Modifier.fillMaxWidth().height(110.dp))
            Column(Modifier.padding(Spacing.x3)) {
                Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDate(f.modified), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
