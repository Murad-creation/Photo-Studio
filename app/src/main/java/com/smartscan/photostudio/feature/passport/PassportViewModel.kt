package com.smartscan.photostudio.feature.passport

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscan.photostudio.core.*
import com.smartscan.photostudio.data.PhotoPreset
import com.smartscan.photostudio.data.SettingsRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

enum class PassportStep { INPUT, EDIT, LAYOUT, EXPORT }
enum class SheetType(val label: String, val wMm: Double, val hMm: Double) {
    S4X6("4 × 6 inch", 101.6, 152.4), A4("A4", 210.0, 297.0)
}
enum class ExportTarget { PHOTO, SHEET }

data class PassportUiState(
    val step: PassportStep = PassportStep.INPUT,
    val hasPhoto: Boolean = false,
    val faceFound: Boolean = true,
    val bg: Int = 0xFFFFFFFF.toInt(),
    val preset: PhotoPreset = PhotoPreset.P35X45,
    val customW: Double = 35.0,
    val customH: Double = 45.0,
    val headScale: Float = 1f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f,
    val tilt: Float = 0f,
    val brightness: Float = 0f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val before: Bitmap? = null,
    val after: Bitmap? = null,
    val sheet: SheetType = SheetType.S4X6,
    val copies: Int = 6,
    val gapMm: Double = 2.0,
    val cutLines: Boolean = true,
    val layoutPreview: Bitmap? = null,
    val capacity: Int = 0,
    val target: ExportTarget = ExportTarget.SHEET,
    val format: ExportFormat = ExportFormat.JPG,
    val quality: ExportQuality = ExportQuality.HIGH,
    val dpi: Int = 300,
    val fileName: String = defaultName(),
    val busy: Boolean = false,
    val status: StatusMsg? = null,
    val exported: File? = null,
    val confirmOverwrite: Boolean = false,
)

private fun defaultName() = "Passport_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

fun photoMm(s: PassportUiState): Pair<Double, Double> =
    if (s.preset == PhotoPreset.CUSTOM) s.customW to s.customH else s.preset.wMm to s.preset.hMm

class PassportViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PassportUiState())
    val ui: StateFlow<PassportUiState> = _ui.asStateFlow()
    private var source: StudioSource? = null
    private var original: Bitmap? = null
    private var rollZ = 0f
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val st = SettingsRepository(app).settings.first()
            _ui.update { it.copy(bg = st.photoBg, preset = st.photoPreset, format = st.format, quality = st.quality, dpi = st.dpi) }
        }
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            _ui.update { it.copy(status = StatusMsg("Not enough memory for this photo. Try a smaller image.", true)) }
        } catch (e: Throwable) {
            _ui.update { it.copy(status = StatusMsg(e.message ?: "Something went wrong.", true)) }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    // ---------- input ----------
    fun onPhoto(file: File) = onUri(Uri.fromFile(file), file)

    fun onUri(uri: Uri, deleteAfter: File? = null) {
        work {
            val bmp = withContext(Dispatchers.IO) { ImageOps.loadBitmap(getApplication(), uri, 1200) }
                ?: throw IOException("Could not open this image.")
            deleteAfter?.delete()
            val a = withContext(Dispatchers.Default) { PhotoSegmenter.analyze(bmp) }
            source = StudioSource(bmp, a.alpha, a.face)
            original = bmp
            rollZ = a.rollZ.coerceIn(-12f, 12f)
            _ui.update {
                it.copy(
                    step = PassportStep.EDIT, hasPhoto = true, faceFound = a.faceFound,
                    headScale = 1f, shiftX = 0f, shiftY = 0f, tilt = rollZ, exported = null,
                    status = if (!a.faceFound) StatusMsg("No face was detected. Use the sliders to position the head manually.", true) else null,
                )
            }
            rebuild()
        }
    }

    fun newPhoto() { _ui.update { it.copy(step = PassportStep.INPUT) } }
    fun goTo(step: PassportStep) { _ui.update { it.copy(step = step, status = null) }; rebuild() }
    fun discard() { source = null; original = null; _ui.value = PassportUiState(bg = _ui.value.bg, preset = _ui.value.preset, format = _ui.value.format, quality = _ui.value.quality, dpi = _ui.value.dpi) }

    // ---------- editing ----------
    private fun edit(block: (PassportUiState) -> PassportUiState) { _ui.update { block(it).copy(exported = null) }; rebuild() }
    fun setBg(c: Int) = edit { it.copy(bg = c) }
    fun setPreset(p: PhotoPreset) = edit { it.copy(preset = p) }
    fun setCustom(w: Double, h: Double) = edit { it.copy(customW = w, customH = h) }
    fun setHeadScale(v: Float) = edit { it.copy(headScale = v) }
    fun setShiftX(v: Float) = edit { it.copy(shiftX = v) }
    fun setShiftY(v: Float) = edit { it.copy(shiftY = v) }
    fun setTilt(v: Float) = edit { it.copy(tilt = v) }
    fun setBrightness(v: Float) = edit { it.copy(brightness = v) }
    fun setExposure(v: Float) = edit { it.copy(exposure = v) }
    fun setContrast(v: Float) = edit { it.copy(contrast = v) }
    fun resetFraming() = edit { it.copy(headScale = 1f, shiftX = 0f, shiftY = 0f, tilt = rollZ) }

    fun setSheet(s: SheetType) = edit { it.copy(sheet = s) }
    fun setCopies(n: Int) = edit { it.copy(copies = n.coerceIn(1, 60)) }
    fun setGap(mm: Double) = edit { it.copy(gapMm = mm) }
    fun setCutLines(b: Boolean) = edit { it.copy(cutLines = b) }

    fun setTarget(t: ExportTarget) = _ui.update { it.copy(target = t, exported = null) }
    fun setFormat(f: ExportFormat) = _ui.update { it.copy(format = f, exported = null) }
    fun setQuality(q: ExportQuality) = _ui.update { it.copy(quality = q, exported = null) }
    fun setDpi(d: Int) = _ui.update { it.copy(dpi = d, exported = null) }
    fun setFileName(n: String) = _ui.update { it.copy(fileName = n, exported = null) }
    fun dismissStatus() = _ui.update { it.copy(status = null) }
    fun dismissOverwrite() = _ui.update { it.copy(confirmOverwrite = false) }
    fun reportError(m: String) = _ui.update { it.copy(status = StatusMsg(m, true)) }

    private fun params(s: PassportUiState) = StudioParams(
        bg = s.bg, headRatio = s.preset.headRatio, brightness = s.brightness, exposure = s.exposure, contrast = s.contrast,
        headScale = s.headScale, shiftX = s.shiftX, shiftY = s.shiftY, tilt = s.tilt,
    )

    private fun px(mm: Double, dpi: Int) = (mm / 25.4 * dpi).roundToInt().coerceAtLeast(1)

    private fun letterbox(o: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(0xFFE6E6E6.toInt())
        val k = min(w / o.width.toFloat(), h / o.height.toFloat())
        val dw = o.width * k; val dh = o.height * k
        c.drawBitmap(o, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Re-renders the live previews on a background thread; newer edits cancel older work. */
    private fun rebuild() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            val src = source ?: return@launch
            val o = original ?: return@launch
            val s = _ui.value
            val (pw, ph) = photoMm(s)
            runCatching {
                if (s.step == PassportStep.EDIT || s.step == PassportStep.INPUT) {
                    val h = 480; val w = (h * pw / ph).roundToInt().coerceAtLeast(1)
                    val after = PassportRender.render(src, params(s), w, h)
                    ensureActive()
                    val before = letterbox(o, w, h)
                    _ui.update { it.copy(before = before, after = after) }
                } else if (s.step == PassportStep.LAYOUT) {
                    val dpi = 100
                    val photo = PassportRender.render(src, params(s), px(pw, dpi), px(ph, dpi))
                    ensureActive()
                    val plan = PrintLayout.plan(s.sheet.wMm, s.sheet.hMm, pw, ph, s.gapMm)
                    val page = PrintLayout.compose(photo, plan, s.sheet.wMm, s.sheet.hMm, pw, ph, s.copies, s.gapMm, dpi, s.cutLines)
                    _ui.update { it.copy(layoutPreview = page, capacity = plan.capacity) }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _ui.update { st -> st.copy(status = StatusMsg(it.message ?: "Preview failed.", true)) }
            }
        }
    }

    // ---------- export ----------
    private fun cleanName(n: String) = n.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { defaultName() }

    fun createFile(overwriteOk: Boolean = false) {
        val s = _ui.value
        val src = source ?: return
        val target = File(Library.dir(getApplication(), "passport"), "${cleanName(s.fileName)}.${s.format.ext}")
        if (target.exists() && !overwriteOk) { _ui.update { it.copy(confirmOverwrite = true) }; return }
        _ui.update { it.copy(confirmOverwrite = false) }
        work {
            withContext(Dispatchers.Default) {
                val (pw, ph) = photoMm(s)
                val photo = PassportRender.render(src, params(s), px(pw, s.dpi), px(ph, s.dpi))
                val bytes: ByteArray
                if (s.target == ExportTarget.PHOTO) {
                    bytes = PageEncoder.encode(photo, s.format, s.quality.jpeg, pw, ph)
                } else {
                    val plan = PrintLayout.plan(s.sheet.wMm, s.sheet.hMm, pw, ph, s.gapMm)
                    if (plan.capacity == 0) throw IOException("The photo does not fit on this sheet. Choose A4 or a smaller photo size.")
                    val page = PrintLayout.compose(photo, plan, s.sheet.wMm, s.sheet.hMm, pw, ph, s.copies, s.gapMm, s.dpi, s.cutLines)
                    val (sw, sh) = PrintLayout.sheetMm(s.sheet.wMm, s.sheet.hMm, plan)
                    bytes = PageEncoder.encode(page, s.format, s.quality.jpeg, sw, sh)
                    page.recycle()
                }
                photo.recycle()
                target.writeBytes(bytes)
            }
            _ui.update { it.copy(exported = target, status = StatusMsg("File created: ${target.name}", false)) }
        }
    }

    fun saveTo(uri: Uri) {
        val f = _ui.value.exported ?: return
        work {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not write to the chosen location.")
            }
            _ui.update { it.copy(status = StatusMsg("Saved to your device.", false)) }
        }
    }
}
