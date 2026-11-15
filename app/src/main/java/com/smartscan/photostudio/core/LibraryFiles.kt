package com.smartscan.photostudio.core

import android.content.Context
import java.io.File
import java.io.IOException

enum class FileCategory(val label: String) {
    ALL("All Files"), NID("NID Documents"), PDFS("Scanned PDFs"),
    PHOTOS("Passport Photos"), RECENT("Recent"), FAVORITES("Favorites")
}

data class LibraryFile(val file: File, val folder: String) {
    val name: String get() = file.name
    val ext: String get() = file.extension.lowercase()
    val size: Long get() = file.length()
    val modified: Long get() = file.lastModified()
    val isPdf: Boolean get() = ext == "pdf"
}

object LibraryFiles {
    private val folders = listOf("nid", "docs", "passport")

    fun root(ctx: Context) = File(ctx.filesDir, "library")

    fun list(ctx: Context): List<LibraryFile> =
        folders.flatMap { f ->
            File(root(ctx), f).listFiles()?.filter { it.isFile }?.map { LibraryFile(it, f) } ?: emptyList()
        }.sortedByDescending { it.modified }

    fun rename(lf: LibraryFile, newBase: String): Result<File> {
        val base = newBase.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (base.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty."))
        val target = File(lf.file.parentFile, "$base.${lf.file.extension}")
        if (target.exists()) return Result.failure(IllegalStateException("A file with that name already exists."))
        return if (lf.file.renameTo(target)) Result.success(target) else Result.failure(IOException("Could not rename the file."))
    }

    fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    fun clearDir(f: File) { f.listFiles()?.forEach { if (it.isDirectory) { clearDir(it) }; it.delete() } }
}
