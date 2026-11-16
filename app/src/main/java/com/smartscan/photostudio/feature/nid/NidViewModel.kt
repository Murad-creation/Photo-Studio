package com.smartscan.photostudio.feature.nid

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscan.photostudio.core.*
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NidStep { FRONT, BACK, ENHANCE, PREVIEW, EXPORT }
enum class Side { FRONT, BACK }

data class PendingCrop(val side: Side, val source: Bitmap, val corners: List<PointF>, val autoDetected: Boolean, val returnTo: NidStep? = null)
data class ExportedFile(val file: File, val format: ExportFormat, val sizeBytes: Long)
data class StatusMsg(val text: String, val isError: Boolean)

data class NidUiState(
    val step: NidStep = NidStep.FRONT,
    val front: Bitmap? = null,
    val back: Bitmap? = null,
    val frontSmall: Bitmap? = null,
    val backSmall: Bitmap? = null,
    val frontSrc: Bitmap? = null,
    val backSrc: Bitmap? = null,
    val frontCorners: List<PointF>? = null,
    val backCorners: List<PointF>? = null,
    val frontRot: Int = 0,
    val backRot: Int = 0,
    val previewFront: Bitmap? = null,
    val previewBack: Bitmap? = null,
    val a4Preview: Bitmap? = null,
    val pending: PendingCrop? = null,
    val params: EnhanceParams = EnhanceParams(),
    val cardWidthMm: Double = A4Layout.ACTUAL_CARD_WIDTH_MM,
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ExportQuality = ExportQuality.HIGH,
    val dpi: Int = 300,
    val fileName: String = defaultName(),
    val busy: Boolean = false,
    val status: StatusMsg? = null,
    val exported: ExportedFile? = null,
    val confirmOverwrite: Boolean = false,
)

private fun defaultName() = "NID_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

class NidViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(NidUiState())
    val ui: StateFlow<NidUiState> = _ui.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val st = SettingsRepository(app).settings.first()
            _ui.update { it.copy(format = st.format, quality = st.quality, dpi = st.dpi) }
        }
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            _ui.update { it.copy(status = StatusMsg("Not enough memory for this image. Try a smaller photo or lower DPI.", true)) }
        } catch (e: Throwable) {
            _ui.update { it.copy(status = StatusMsg(e.message ?: "Something went wrong.", true)) }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    // ----- capture / import -----
    /** Gallery import: use the detected card edges when found, otherwise ask for a manual crop. */
    fun onSourceUri(side: Side, uri: Uri) {
        work {
            val ctx = getApplication<Application>()
            val bmp = withContext(Dispatchers.IO) { ImageOps.loadBitmap(ctx, uri) }
                ?: throw IOException("Could not open this image.")
            val found = withContext(Dispatchers.Default) { ImageOps.detectCard(bmp) }
            if (found != null) applyCrop(side, bmp, found, null)
            else _ui.update { it.copy(pending = PendingCrop(side, bmp, ImageOps.defaultCorners(bmp), false)) }
        }
    }

    /** Camera: crop automatically to the on-screen frame. The crop can be adjusted before saving. */
    fun onPhoto(side: Side, file: File) {
        work {
            val ctx = getApplication<Application>()
            val bmp = withContext(Dispatchers.IO) { ImageOps.loadBitmap(ctx, Uri.fromFile(file)) }
                ?: throw IOException("Could not read the captured photo.")
            file.delete()
            applyCrop(side, bmp, ImageOps.frameCorners(bmp, FRAME_WIDTH_FRACTION, CARD_ASPECT), null)
        }
    }

    private suspend fun applyCrop(side: Side, source: Bitmap, corners: List<PointF>, returnTo: NidStep?) {
        val rot = if (side == Side.FRONT) _ui.value.frontRot else _ui.value.backRot
        val cropped = withContext(Dispatchers.Default) {
            val c = ImageOps.warp(source, corners)
            if (rot != 0) ImageOps.rotate(c, rot) else c
        }
        val small = withContext(Dispatchers.Default) { ImageOps.downscale(cropped, 1000) }
        _ui.update { s ->
            val ns = if (side == Side.FRONT) s.copy(front = cropped, frontSmall = small, frontSrc = source, frontCorners = corners)
            else s.copy(back = cropped, backSmall = small, backSrc = source, backCorners = corners)
            ns.copy(pending = null, exported = null, step = returnTo ?: nextStep(ns))
        }
        refreshPreviews()
    }

    fun updateCorners(c: List<PointF>) {
        _ui.update { s -> s.pending?.let { s.copy(pending = it.copy(corners = c)) } ?: s }
    }

    fun cancelPending() { _ui.update { it.copy(pending = null) } }

    fun confirmCrop() {
        val p = _ui.value.pending ?: return
        work { applyCrop(p.side, p.source, p.corners, p.returnTo) }
    }

    /** Re-open the crop editor for a side that was already captured. */
    fun recrop(side: Side) {
        val s = _ui.value
        val src = (if (side == Side.FRONT) s.frontSrc else s.backSrc) ?: return
        val corners = (if (side == Side.FRONT) s.frontCorners else s.backCorners) ?: ImageOps.defaultCorners(src)
        _ui.update { it.copy(pending = PendingCrop(side, src, corners, true, returnTo = it.step)) }
    }

    private fun nextStep(s: NidUiState) = when {
        s.front == null -> NidStep.FRONT
        s.back == null -> NidStep.BACK
        else -> NidStep.ENHANCE
    }

    fun retake(side: Side) {
        _ui.update {
            val n = if (side == Side.FRONT) it.copy(front = null, frontSmall = null, previewFront = null, frontSrc = null, frontCorners = null, frontRot = 0)
            else it.copy(back = null, backSmall = null, previewBack = null, backSrc = null, backCorners = null, backRot = 0)
            n.copy(a4Preview = null, exported = null, step = if (side == Side.FRONT) NidStep.FRONT else NidStep.BACK)
        }
    }

    fun goTo(step: NidStep) { _ui.update { it.copy(step = step, status = null) } }

    // ----- enhance -----
    fun setParams(p: EnhanceParams) {
        _ui.update { it.copy(params = p, exported = null) }
        refreshPreviews()
    }

    fun setCardWidth(mm: Double) {
        _ui.update { it.copy(cardWidthMm = mm, exported = null) }
        refreshPreviews()
    }

    fun rotate(side: Side) {
        val s = _ui.value
        val src = (if (side == Side.FRONT) s.front else s.back) ?: return
        work {
            val r = withContext(Dispatchers.Default) { ImageOps.rotate(src, 90) }
            val sm = withContext(Dispatchers.Default) { ImageOps.downscale(r, 1000) }
            _ui.update {
                (if (side == Side.FRONT) it.copy(front = r, frontSmall = sm, frontRot = (it.frontRot + 90) % 360) else it.copy(back = r, backSmall = sm, backRot = (it.backRot + 90) % 360))
                    .copy(exported = null)
            }
            refreshPreviews()
        }
    }

    private fun refreshPreviews() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            val s = _ui.value
            val pf = s.frontSmall?.let { ImageOps.enhance(it, s.params) }
            ensureActive()
            val pb = s.backSmall?.let { ImageOps.enhance(it, s.params) }
            ensureActive()
            val a4 = if (pf != null && pb != null)
                A4Layout.compose(pf, pb, 100, A4Layout.fitCardWidthMm(pf, pb, s.cardWidthMm)) else null
            _ui.update { it.copy(previewFront = pf, previewBack = pb, a4Preview = a4) }
        }
    }

    // ----- export -----
    fun setFormat(f: ExportFormat) = _ui.update { it.copy(format = f, exported = null) }
    fun setQuality(q: ExportQuality) = _ui.update { it.copy(quality = q, exported = null) }
    fun setDpi(d: Int) = _ui.update { it.copy(dpi = d, exported = null) }
    fun setFileName(n: String) = _ui.update { it.copy(fileName = n, exported = null) }
    fun dismissStatus() = _ui.update { it.copy(status = null) }
    fun dismissOverwrite() = _ui.update { it.copy(confirmOverwrite = false) }

    private fun cleanName(n: String): String =
        n.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { defaultName() }

    fun createFile(overwriteOk: Boolean = false) {
        val s = _ui.value
        val front = s.front ?: return
        val back = s.back ?: return
        val ctx = getApplication<Application>()
        val target = File(Library.dir(ctx, "nid"), "${cleanName(s.fileName)}.${s.format.ext}")
        if (target.exists() && !overwriteOk) {
            _ui.update { it.copy(confirmOverwrite = true) }
            return
        }
        _ui.update { it.copy(confirmOverwrite = false) }
        work {
            withContext(Dispatchers.Default) {
                val f = ImageOps.enhance(front, s.params)
                val b = ImageOps.enhance(back, s.params)
                val page = A4Layout.compose(f, b, s.dpi, A4Layout.fitCardWidthMm(f, b, s.cardWidthMm))
                f.recycle(); b.recycle()
                val bytes = Exporter.encode(page, s.format, s.quality.jpeg)
                page.recycle()
                target.writeBytes(bytes)
            }
            _ui.update {
                it.copy(exported = ExportedFile(target, s.format, target.length()),
                    status = StatusMsg("File created: ${target.name}", false))
            }
        }
    }

    fun saveTo(uri: Uri) {
        val ex = _ui.value.exported ?: return
        work {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    ex.file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Could not write to the chosen location.")
            }
            _ui.update { it.copy(status = StatusMsg("Saved to your device.", false)) }
        }
    }

    fun reportError(msg: String) = _ui.update { it.copy(status = StatusMsg(msg, true)) }

    fun startOver() { _ui.value = NidUiState() }
}
