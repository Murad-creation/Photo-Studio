package com.smartscan.photostudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartscan.photostudio.navigation.AppNavHost
import com.smartscan.photostudio.ui.theme.SmartScanTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            SmartScanTheme(mode) { AppNavHost(themeMode = mode, onThemeChange = vm::setTheme) }
        }
    }
}
