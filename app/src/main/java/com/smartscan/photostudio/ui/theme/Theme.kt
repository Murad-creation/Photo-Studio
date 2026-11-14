package com.smartscan.photostudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { LIGHT, DARK, SYSTEM }

private val LightColors = lightColorScheme(
    primary = Emerald, onPrimary = Color.White,
    secondary = EmeraldSecondary, onSecondary = Color.White,
    background = LightBackground, onBackground = TextLight,
    surface = LightSurface, onSurface = TextLight,
    surfaceVariant = Color(0xFFE6EFE9), onSurfaceVariant = Color(0xFF45594E),
    outlineVariant = Color(0xFFD3E0D8),
)
private val DarkColors = darkColorScheme(
    primary = EmeraldSecondary, onPrimary = Color(0xFF04170D),
    secondary = Emerald, onSecondary = Color.White,
    background = DarkBackground, onBackground = TextDark,
    surface = DarkSurface, onSurface = TextDark,
    surfaceVariant = Color(0xFF22332A), onSurfaceVariant = Color(0xFFA9BDB1),
    outlineVariant = Color(0xFF2F4339),
)

@Composable
fun SmartScanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
