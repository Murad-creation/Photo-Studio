package com.smartscan.photostudio.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

object ComponentStyles {
    @Composable
    fun primaryButtonColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )

    @Composable
    fun cardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

    @Composable
    fun navBarColors() = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        indicatorColor = MaterialTheme.colorScheme.primary,
    )
}
