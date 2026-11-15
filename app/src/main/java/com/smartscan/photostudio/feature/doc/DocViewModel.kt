package com.smartscan.photostudio.feature.doc

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.feature.nid.StatusMsg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.smartscan.photostudio.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DocStep { CAPTURE, EDIT, PREVIEW, EXPORT }

enum class PaperSize(val label: String, val wMm: Double, val hMm: Double) {
    A4("A4", 210.0, 297.0), A3("A3", 297.0, 420.0), A5("A5", 148.0, 210.0),
    LETTER("Letter", 215.9, 279.4), LEGAL("Legal", 215.9, 355.6),
    ORIGINAL("Original", 0.0, 0.0), CUSTOM("Custom", 0.0, 0.0)
}

data class DocPage(
    val id: Long,
    val source: File,            // uncropped image, kept so the crop can be redone
    val corners: List<PointF>,
    val cropped: File,           // full-resolution cropped page (rotation applied at render time)
    val rotation: Int,
    val params: EnhanceParams,
    val thumb: Bitmap,           // small cropped + rotated image used by the UI
    val preview: Bitmap? = null, // thumb with filters applied
)

data class DocPending(val source: Bitmap, val corners: List<PointF>, val autoDetected: Boolean, val replaceId: Long?)
data class ExportedDoc(val files: List<File>, val format: ExportFormat, val totalBytes: Long)

data class DocUiState(
    val step: DocStep = DocStep.CAPTURE,
    val pages: List<DocPage> = emptyList(),
    val selected: Int = 0,
    val pending: DocPending? = null,
    val retakeId: Long? = null,
    val autoCrop: Boolean = true,
    val paper: PaperSize = PaperSize.A4,
    val landscape: Boolean = false,
    val customWMm: Double = 210.0,
    val customHMm: Double = 297.0,
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ExportQuality = ExportQuality.HIGH,
    val dpi: Int = 150,
    val fileName: String = defaultDocName(),
    val busy: Boolean = false,
    val status: StatusMsg? = null,
    val exported: ExportedDoc? = null,
    val confirmOverwrite: Boolean = false,
)

private fun defaultDocName() = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

/** Paper size in mm after orientation, or null for "Original" (page follows the image). */
fun paperMm(s: DocUiState): Pair<Double, Double>? {
    if (s.paper == PaperSize.ORIGINAL) return null
    val w0 = if (s.paper == PaperSize.CUSTOM) s.customWMm else s.paper.wMm
    val h0 = if (s.paper == PaperSize.CUSTOM) s.customHMm else s.paper.hMm
    val lo = min(w0, h0); val hi = max(w0, h0)
    return if (s.landscape) hi to lo else lo to hi
}

class DocViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DocUiState())
    val ui: StateFlow<DocUiState> = _ui.asStateFlow()
    private var previewJob: Job? = null
    private var nextId = 1L
    private val workDir = File(app.cacheDir, "doc").apply { mkdirs() }
    private var defaultFilter = EnhanceFilter.MAGIC

    init {
        viewModelScope.launch {
            val st = SettingsRepository(app).settings.first()
            defaultFilter = st.defaultFilter
            _ui.update { it.copy(autoCrop = st.autoCrop, paper = st.defaultPaper, format = st.format, quality = st.quality, dpi = st.dpi) }
        }
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            _ui.update { it.copy(status = StatusMsg("Not enough memory. Try fewer pages or a lower DPI.", true)) }
        } catch (e: Throwable) {
            _ui.update { it.copy(status = StatusMsg(e.message ?: "Something went wrong.", true)) }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    private fun saveJpeg(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    // ---------- capture / import ----------
    fun onPhoto(file: File) {
        work {
            val bmp = withContext(Dispatchers.IO) { ImageOps.loadBitmap(getApplication(), Uri.fromFile(file)) }
                ?: throw IOException("Could not read the captured photo.")
            file.delete()
            ingest(bmp, batch = false)
        }
    }

    fun onUris(uris: List<Uri>) {
        work {
            val list = if (_ui.value.retakeId != null) uris.take(1) else uris
            var failed = 0
            for (u in list) {
                val bmp = withContext(Dispatchers.IO) { ImageOps.loadBitmap(getApplication(), u) }
                if (bmp == null) { failed++; continue }
                ingest(bmp, batch = list.size > 1)
            }
            if (failed > 0) _ui.update { it.copy(status = StatusMsg("$failed image(s) could not be opened.", true)) }
            refreshPreviews()
        }
    }

    private suspend fun ingest(bmp: Bitmap, batch: Boolean) {
        val found = withContext(Dispatchers.Default) { ImageOps.detectCard(bmp) }
        val rid = _ui.value.retakeId
        when {
            rid != null ->
                _ui.update { it.copy(pending = DocPending(bmp, found ?: ImageOps.defaultCorners(bmp), found != null, rid)) }
            batch -> addPage(bmp, found ?: ImageOps.fullCorners(bmp), null)
            _ui.value.autoCrop && found != null -> { addPage(bmp, found, null); refreshPreviews() }
            else ->
                _ui.update { it.copy(pending = DocPending(bmp, found ?: ImageOps.defaultCorners(bmp), found != null, null)) }
        }
    }

    private suspend fun addPage(bmp: Bitmap, corners: List<PointF>, replaceId: Long?) {
        val old = replaceId?.let { rid -> _ui.value.pages.firstOrNull { it.id == rid } }
        val id = old?.id ?: nextId++
        val src = File(workDir, "src_$id.jpg"); val crp = File(workDir, "crop_$id.jpg")
        val rotation = old?.rotation ?: 0
        val thumb = withContext(Dispatchers.Default) {
            saveJpeg(bmp, src)
            val cropped = ImageOps.warp(bmp, corners)
            saveJpeg(cropped, crp)
            var t = ImageOps.downscale(cropped, 1000)
            if (t === cropped) t = cropped.copy(Bitmap.Config.ARGB_8888, false)
            if (rotation != 0) t = ImageOps.rotate(t, rotation)
            t
        }
        val page = DocPage(id, src, corners, crp, rotation, old?.params ?: EnhanceParams(filter = defaultFilter), thumb)
        _ui.update { s ->
            if (old == null) s.copy(pages = s.pages + page, selected = s.pages.size, exported = null)
            else s.copy(pages = s.pages.map { if (it.id == id) page else it }, exported = null)
        }
    }

    fun updateCorners(c: List<PointF>) {
        _ui.update { s -> s.pending?.let { s.copy(pending = it.copy(corners = c)) } ?: s }
    }

    fun confirmCrop() {
        val p = _ui.value.pending ?: return
        work {
            addPage(p.source, p.corners, p.replaceId)
            _ui.update { it.copy(pending = null, retakeId = null, step = if (p.replaceId != null) DocStep.EDIT else it.step) }
            refreshPreviews()
        }
    }

    fun cancelPending() {
        _ui.update { s ->
            val fromEdit = s.pending?.replaceId != null
            s.copy(pending = null, retakeId = null, step = if (fromEdit) DocStep.EDIT else s.step)
        }
    }

    fun cancelRetake() { _ui.update { it.copy(retakeId = null, step = DocStep.EDIT) } }
    fun setAutoCrop(on: Boolean) = _ui.update { it.copy(autoCrop = on) }
    fun goTo(step: DocStep) { _ui.update { it.copy(step = step, status = null) } }
    fun select(i: Int) = _ui.update { it.copy(selected = i.coerceIn(0, max(0, it.pages.lastIndex))) }

    // ---------- page operations ----------
    fun retake(index: Int) {
        val page = _ui.value.pages.getOrNull(index) ?: return
        _ui.update { it.copy(retakeId = page.id, step = DocStep.CAPTURE) }
    }

    fun recrop(index: Int) {
        val page = _ui.value.pages.getOrNull(index) ?: return
        work {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(page.source.path) }
                ?: throw IOException("The original page image is missing.")
            _ui.update { it.copy(pending = DocPending(bmp, page.corners, true, page.id)) }
        }
    }

    fun rotate(index: Int) {
        val page = _ui.value.pages.getOrNull(index) ?: return
        work {
            val t = withContext(Dispatchers.Default) { ImageOps.rotate(page.thumb, 90) }
            _ui.update { s ->
                s.copy(pages = s.pages.map {
                    if (it.id == page.id) it.copy(rotation = (it.rotation + 90) % 360, thumb = t, preview = null) else it
                }, exported = null)
            }
            refreshPreviews()
        }
    }

    fun delete(index: Int) {
        _ui.update { s ->
            val page = s.pages.getOrNull(index) ?: return@update s
            page.source.delete(); page.cropped.delete()
            val pages = s.pages.filterIndexed { i, _ -> i != index }
            s.copy(pages = pages, selected = min(s.selected, max(0, pages.lastIndex)), exported = null)
        }
    }

    fun move(index: Int, delta: Int) {
        _ui.update { s ->
            val to = index + delta
            if (index !in s.pages.indices || to !in s.pages.indices) return@update s
            val m = s.pages.toMutableList(); val p = m.removeAt(index); m.add(to, p)
            s.copy(pages = m, selected = to, exported = null)
        }
    }

    fun setParams(p: EnhanceParams) {
        _ui.update { s ->
            s.copy(pages = s.pages.mapIndexed { i, pg -> if (i == s.selected) pg.copy(params = p, preview = null) else pg }, exported = null)
        }
        refreshPreviews()
    }

    fun applyToAll() {
        _ui.update { s ->
            val p = s.pages.getOrNull(s.selected)?.params ?: return@update s
            s.copy(pages = s.pages.map { it.copy(params = p, preview = null) }, exported = null)
        }
        refreshPreviews()
    }

    private fun refreshPreviews() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val s = _ui.value
                val p = s.pages.getOrNull(s.selected)?.takeIf { it.preview == null }
                    ?: s.pages.firstOrNull { it.preview == null } ?: break
                val pv = ImageOps.enhance(p.thumb, p.params)
                ensureActive()
                _ui.update { st ->
                    st.copy(pages = st.pages.map { if (it.id == p.id && it.params == p.params && it.thumb === p.thumb) it.copy(preview = pv) else it })
                }
            }
        }
    }

    // ---------- paper / export settings ----------
    fun setPaper(p: PaperSize) = _ui.update { it.copy(paper = p, exported = null) }
    fun setLandscape(l: Boolean) = _ui.update { it.copy(landscape = l, exported = null) }
    fun setCustom(w: Double, h: Double) = _ui.update { it.copy(customWMm = w, customHMm = h, exported = null) }
    fun setFormat(f: ExportFormat) = _ui.update { it.copy(format = f, exported = null) }
    fun setQuality(q: ExportQuality) = _ui.update { it.copy(quality = q, exported = null) }
    fun setDpi(d: Int) = _ui.update { it.copy(dpi = d, exported = null) }
    fun setFileName(n: String) = _ui.update { it.copy(fileName = n, exported = null) }
    fun dismissStatus() = _ui.update { it.copy(status = null) }
    fun dismissOverwrite() = _ui.update { it.copy(confirmOverwrite = false) }
    fun reportError(msg: String) = _ui.update { it.copy(status = StatusMsg(msg, true)) }

    private fun cleanName(n: String) = n.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { defaultDocName() }

    fun createFile(overwriteOk: Boolean = false) {
        val s = _ui.value
        if (s.pages.isEmpty()) return
        val dir = Library.dir(getApplication(), "docs")
        val base = cleanName(s.fileName)
        val ext = s.format.ext
        val targets: List<File> = when {
            s.format == ExportFormat.PDF -> listOf(File(dir, "$base.pdf"))
            s.pages.size == 1 -> listOf(File(dir, "$base.$ext"))
            else -> s.pages.indices.map { File(dir, "${base}_${it + 1}.$ext") }
        }
        if (!overwriteOk && targets.any { it.exists() }) {
            _ui.update { it.copy(confirmOverwrite = true) }
            return
        }
        _ui.update { it.copy(confirmOverwrite = false) }
        work {
            val files = withContext(Dispatchers.Default) { renderExport(s, targets) }
            _ui.update {
                it.copy(exported = ExportedDoc(files, s.format, files.sumOf { f -> f.length() }),
                    status = StatusMsg("Created ${files.size} file(s).", false))
            }
        }
    }

    private fun renderExport(s: DocUiState, targets: List<File>): List<File> {
        val pdfPages = ArrayList<JpegPdf.Page>()
        val mm = paperMm(s)
        s.pages.forEachIndexed { i, page ->
            var bmp = BitmapFactory.decodeFile(page.cropped.path) ?: throw IOException("Page ${i + 1} image is missing.")
            if (page.rotation != 0) { val r = ImageOps.rotate(bmp, page.rotation); bmp.recycle(); bmp = r }
            val enh = ImageOps.enhance(bmp, page.params)
            if (enh !== bmp) bmp.recycle()
            val ptW: Float; val ptH: Float
            if (mm == null) { ptW = enh.width / s.dpi.toFloat() * 72f; ptH = enh.height / s.dpi.toFloat() * 72f }
            else { ptW = (mm.first / 25.4 * 72).toFloat(); ptH = (mm.second / 25.4 * 72).toFloat() }
            val fit = min(ptW / enh.width, ptH / enh.height)
            val drawW = enh.width * fit; val drawH = enh.height * fit
            val tw = (drawW / 72f * s.dpi).roundToInt(); val th = (drawH / 72f * s.dpi).roundToInt()
            val out = if (enh.width > tw + 1 && tw > 0 && th > 0) Bitmap.createScaledBitmap(enh, tw, th, true) else enh
            if (s.format == ExportFormat.PDF) {
                val bos = java.io.ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, s.quality.jpeg, bos)
                pdfPages.add(JpegPdf.Page(bos.toByteArray(), out.width, out.height, ptW, ptH, (ptW - drawW) / 2f, (ptH - drawH) / 2f, drawW, drawH))
            } else {
                FileOutputStream(targets[i]).use {
                    if (s.format == ExportFormat.JPG) out.compress(Bitmap.CompressFormat.JPEG, s.quality.jpeg, it)
                    else out.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            if (out !== enh) out.recycle()
            enh.recycle()
        }
        if (s.format == ExportFormat.PDF) FileOutputStream(targets[0]).use { JpegPdf.write(pdfPages, it) }
        return targets
    }

    fun saveTo(uri: Uri) {
        val ex = _ui.value.exported ?: return
        work {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    ex.files.first().inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Could not write to the chosen location.")
            }
            _ui.update { it.copy(status = StatusMsg("Saved to your device.", false)) }
        }
    }

    fun saveTree(treeUri: Uri) {
        val ex = _ui.value.exported ?: return
        work {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(ctx, treeUri) ?: throw IOException("Could not open the chosen folder.")
                ex.files.forEach { f ->
                    val target = dir.createFile(ex.format.mime, f.nameWithoutExtension)
                        ?: throw IOException("Could not create ${f.name}.")
                    ctx.contentResolver.openOutputStream(target.uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Could not write ${f.name}.")
                }
            }
            _ui.update { it.copy(status = StatusMsg("Saved ${ex.files.size} files to the chosen folder.", false)) }
        }
    }

    fun startOver() {
        _ui.value.pages.forEach { it.source.delete(); it.cropped.delete() }
        _ui.value = DocUiState()
    }
}
