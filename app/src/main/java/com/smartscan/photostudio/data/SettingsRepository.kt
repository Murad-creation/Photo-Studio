package com.smartscan.photostudio.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartscan.photostudio.core.EnhanceFilter
import com.smartscan.photostudio.core.ExportFormat
import com.smartscan.photostudio.core.ExportQuality
import com.smartscan.photostudio.feature.doc.PaperSize
import com.smartscan.photostudio.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "settings")

enum class PhotoPreset(val label: String, val wMm: Double, val hMm: Double, val headRatio: Double) {
    P35X45("35 × 45 mm", 35.0, 45.0, 0.74),
    P2X2("2 × 2 inch", 50.8, 50.8, 0.60),
    P4X6("4 × 6 inch", 101.6, 152.4, 0.50),
    CUSTOM("Custom", 35.0, 45.0, 0.70),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultFilter: EnhanceFilter = EnhanceFilter.MAGIC,
    val autoCrop: Boolean = true,
    val defaultPaper: PaperSize = PaperSize.A4,
    val photoPreset: PhotoPreset = PhotoPreset.P35X45,
    val photoBg: Int = 0xFFFFFFFF.toInt(),
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ExportQuality = ExportQuality.HIGH,
    val dpi: Int = 300,
    val sessionMin: Int = 5,
    val favorites: Set<String> = emptySet(),
)

private object K {
    val theme = stringPreferencesKey("theme_mode")
    val filter = stringPreferencesKey("default_filter")
    val autoCrop = booleanPreferencesKey("auto_crop")
    val paper = stringPreferencesKey("default_paper")
    val preset = stringPreferencesKey("photo_preset")
    val bg = intPreferencesKey("photo_bg")
    val format = stringPreferencesKey("default_format")
    val quality = stringPreferencesKey("default_quality")
    val dpi = intPreferencesKey("default_dpi")
    val session = intPreferencesKey("session_min")
    val favorites = stringSetPreferencesKey("favorites")
}

private inline fun <reified E : Enum<E>> Preferences.enumOf(k: Preferences.Key<String>, def: E): E {
    val raw = this[k] ?: return def
    return runCatching { enumValueOf<E>(raw) }.getOrDefault(def)
}

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            themeMode = p.enumOf(K.theme, ThemeMode.SYSTEM),
            defaultFilter = p.enumOf(K.filter, EnhanceFilter.MAGIC),
            autoCrop = p[K.autoCrop] ?: true,
            defaultPaper = p.enumOf(K.paper, PaperSize.A4),
            photoPreset = p.enumOf(K.preset, PhotoPreset.P35X45),
            photoBg = p[K.bg] ?: 0xFFFFFFFF.toInt(),
            format = p.enumOf(K.format, ExportFormat.PDF),
            quality = p.enumOf(K.quality, ExportQuality.HIGH),
            dpi = p[K.dpi] ?: 300,
            sessionMin = p[K.session] ?: 5,
            favorites = p[K.favorites] ?: emptySet(),
        )
    }

    private suspend fun <T> set(k: Preferences.Key<T>, v: T) { context.store.edit { it[k] = v } }

    suspend fun setTheme(v: ThemeMode) = set(K.theme, v.name)
    suspend fun setDefaultFilter(v: EnhanceFilter) = set(K.filter, v.name)
    suspend fun setAutoCrop(v: Boolean) = set(K.autoCrop, v)
    suspend fun setPaper(v: PaperSize) = set(K.paper, v.name)
    suspend fun setPhotoPreset(v: PhotoPreset) = set(K.preset, v.name)
    suspend fun setPhotoBg(v: Int) = set(K.bg, v)
    suspend fun setFormat(v: ExportFormat) = set(K.format, v.name)
    suspend fun setQuality(v: ExportQuality) = set(K.quality, v.name)
    suspend fun setDpi(v: Int) = set(K.dpi, v)
    suspend fun setSessionMin(v: Int) = set(K.session, v)

    suspend fun toggleFavorite(path: String) {
        context.store.edit { p ->
            val s = p[K.favorites] ?: emptySet()
            p[K.favorites] = if (path in s) s - path else s + path
        }
    }
    suspend fun renameFavorite(old: String, new: String) {
        context.store.edit { p ->
            val s = p[K.favorites] ?: emptySet()
            if (old in s) p[K.favorites] = s - old + new
        }
    }
    suspend fun removeFavorite(path: String) {
        context.store.edit { p ->
            val s = p[K.favorites] ?: emptySet()
            if (path in s) p[K.favorites] = s - path
        }
    }
}
