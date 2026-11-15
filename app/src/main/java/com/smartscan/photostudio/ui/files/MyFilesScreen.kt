package com.smartscan.photostudio.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.data.SettingsRepository
import com.smartscan.photostudio.ui.components.*
import com.smartscan.photostudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SortMode(val label: String) {
    DATE_DESC("Newest first"), DATE_ASC("Oldest first"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), SIZE_DESC("Largest first")
}

enum class TypeFilter(val label: String, val exts: Set<String>) {
    ALL("All types", emptySet()), PDF("PDF", setOf("pdf")), JPG("JPG", setOf("jpg", "jpeg")), PNG("PNG", setOf("png"))
}

@Composable
fun MyFilesScreen(onOpen: (String) -> Unit, onSendToPc: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val favorites by remember { repo.settings.map { it.favorites } }.collectAsStateWithLifecycle(initialValue = emptySet())
    var refresh by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val files by produceState(emptyList<LibraryFile>(), refresh) {
        value = withContext(Dispatchers.IO) { LibraryFiles.list(ctx) }
        loaded = true
    }
    var category by remember { mutableStateOf(FileCategory.ALL) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.DATE_DESC) }
    var type by remember { mutableStateOf(TypeFilter.ALL) }
    var grid by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LibraryFile?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryFile?>(null) }
    var detailsTarget by remember { mutableStateOf<LibraryFile?>(null) }
    var message by remember { mutableStateOf<StatusLine?>(null) }

    val visible = remember(files, category, query, sort, type, favorites) {
        var l: List<LibraryFile> = when (category) {
            FileCategory.ALL -> files
            FileCategory.NID -> files.filter { it.folder == "nid" }
            FileCategory.PDFS -> files.filter { it.folder == "docs" }
            FileCategory.PHOTOS -> files.filter { it.folder == "passport" }
            FileCategory.RECENT -> files.take(20)
            FileCategory.FAVORITES -> files.filter { it.rel in favorites }
        }
        if (type != TypeFilter.ALL) l = l.filter { it.ext in type.exts }
        if (query.isNotBlank()) l = l.filter { it.name.contains(query.trim(), ignoreCase = true) }
        when (sort) {
            SortMode.DATE_DESC -> l.sortedByDescending { it.modified }
            SortMode.DATE_ASC -> l.sortedBy { it.modified }
            SortMode.NAME_ASC -> l.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> l.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_DESC -> l.sortedByDescending { it.size }
        }
    }

    fun act(f: LibraryFile, a: FileAction) {
        when (a) {
            FileAction.OPEN -> onOpen(f.rel)
            FileAction.FAVORITE -> scope.launch { repo.toggleFavorite(f.rel) }
            FileAction.SHARE -> ShareUtil.share(ctx, listOf(f.file))
            FileAction.SEND_PC -> onSendToPc(listOf(f.file))
            FileAction.RENAME -> renameTarget = f
            FileAction.DETAILS -> detailsTarget = f
            FileAction.DELETE -> deleteTarget = f
        }
    }

    Column(Modifier.fillMaxSize()) {
        SmartScanTopBar("My Files", actions = {
            IconButton(onClick = { grid = !grid }) {
                Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, if (grid) "Switch to list" else "Switch to grid")
            }
        })
        Column(Modifier.padding(horizontal = Spacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search files") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = ButtonShape,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                items(FileCategory.values().toList()) { c -> FilterChip(category == c, { category = c }, { Text(c.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                Box {
                    AssistChip(onClick = { sortMenu = true }, label = { Text(sort.label) }, leadingIcon = { Icon(Icons.Filled.Sort, null, Modifier.size(18.dp)) })
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortMode.values().forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { sort = m; sortMenu = false }) }
                    }
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    TypeFilter.values().forEach { t -> FilterChip(type == t, { type = t }, { Text(t.label) }) }
                }
            }
        }
        message?.let { StatusBanner(it.text, it.error) { message = null } }
        Spacer(Modifier.height(Spacing.x2))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                files.isEmpty() -> EmptyStateView(Icons.Filled.FolderOpen, "No files yet",
                    "Scans, documents and passport photos you create are saved here.", Modifier.align(Alignment.Center))
                visible.isEmpty() -> EmptyStateView(Icons.Filled.SearchOff, "Nothing matches",
                    "Try another category, type or search.", Modifier.align(Alignment.Center))
                grid -> LazyVerticalGrid(
                    GridCells.Adaptive(160.dp), contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.x2),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x3), verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                ) { items(visible, key = { it.file.path }) { f -> FileGridItem(f, f.rel in favorites, { a -> act(f, a) }) } }
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.x2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) { items(visible, key = { it.file.path }) { f -> FileListItem(f, f.rel in favorites, { a -> act(f, a) }) } }
            }
        }
    }

    renameTarget?.let { f ->
        var text by remember(f) { mutableStateOf(f.file.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renameTarget = null }, shape = DialogShape,
            title = { Text("Rename file") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name (.${f.ext} is kept)") }) },
            confirmButton = {
                TextButton(onClick = {
                    val old = f.rel
                    LibraryFiles.rename(f, text).fold(
                        onSuccess = { nf ->
                            scope.launch { repo.renameFavorite(old, "${f.folder}/${nf.name}") }
                            refresh++; message = StatusLine("Renamed to ${nf.name}", false)
                        },
                        onFailure = { message = StatusLine(it.message ?: "Could not rename.", true) },
                    )
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { f ->
        ConfirmationDialog("Delete ${f.name}?", "This permanently removes the file from the app.", "Delete",
            onConfirm = {
                val ok = f.file.delete()
                if (ok) scope.launch { repo.removeFavorite(f.rel) }
                refresh++
                message = if (ok) StatusLine("Deleted ${f.name}", false) else StatusLine("Could not delete the file.", true)
                deleteTarget = null
            }, onDismiss = { deleteTarget = null })
    }
    detailsTarget?.let { f ->
        val pages by produceState<Int?>(null, f.file.path) {
            value = if (f.isPdf) withContext(Dispatchers.IO) { runCatching { PdfDoc(f.file).use { it.pageCount } }.getOrNull() } else 1
        }
        AlertDialog(
            onDismissRequest = { detailsTarget = null }, shape = DialogShape,
            title = { Text("File details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                    Text("Name: ${f.name}"); Text("Type: ${f.ext.uppercase()}"); Text("Size: ${formatSize(f.size)}")
                    Text("Modified: ${formatDate(f.modified)}"); pages?.let { Text("Pages: $it") }
                    Text("Location: app library / ${f.folder}")
                }
            },
            confirmButton = { TextButton(onClick = { detailsTarget = null }) { Text("Close") } },
        )
    }
}

private data class StatusLine(val text: String, val error: Boolean)
