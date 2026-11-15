package com.smartscan.photostudio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscan.photostudio.data.SettingsRepository
import com.smartscan.photostudio.ui.theme.ThemeMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    val themeMode: StateFlow<ThemeMode> = repo.settings.map { it.themeMode }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { repo.setTheme(mode) } }
}
