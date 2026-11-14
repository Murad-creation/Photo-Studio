package com.smartscan.photostudio.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.smartscan.photostudio.feature.nid.NidScreen
import com.smartscan.photostudio.ui.components.SmartScanBottomNavigation
import com.smartscan.photostudio.ui.files.MyFilesScreen
import com.smartscan.photostudio.ui.home.HomeScreen
import com.smartscan.photostudio.ui.placeholder.ModulePlaceholderScreen
import com.smartscan.photostudio.ui.settings.SettingsScreen
import com.smartscan.photostudio.ui.theme.ThemeMode

object Routes {
    const val HOME = "home"; const val FILES = "files"; const val SETTINGS = "settings"
    const val NID = "nid"; const val DOC = "document"; const val PASSPORT = "passport"; const val PC = "pc"
}

data class BottomItem(val route: String, val label: String, val icon: ImageVector)

val bottomItems = listOf(
    BottomItem(Routes.HOME, "Home", Icons.Filled.Home),
    BottomItem(Routes.FILES, "My Files", Icons.Filled.Folder),
    BottomItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNavHost(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = bottomItems.any { item -> dest?.hierarchy?.any { it.route == item.route } == true }

    fun goTop(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) SmartScanBottomNavigation(
                items = bottomItems,
                currentRoute = dest?.route,
                onSelect = ::goTop,
            )
        },
    ) { inner ->
        NavHost(
            navController = nav, startDestination = Routes.HOME,
            modifier = Modifier.padding(inner),
            enterTransition = { fadeIn() + slideInHorizontally { it / 10 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 10 } },
        ) {
            composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) { launchSingleTop = true } }, onOpenSettings = { goTop(Routes.SETTINGS) }) }
            composable(Routes.FILES) { MyFilesScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(themeMode, onThemeChange) }
            composable(Routes.NID) { NidScreen(onExit = { nav.popBackStack() }) }
            composable(Routes.DOC) { ModulePlaceholderScreen("Document Scanner") { nav.popBackStack() } }
            composable(Routes.PASSPORT) { ModulePlaceholderScreen("Passport Photo Studio") { nav.popBackStack() } }
            composable(Routes.PC) { ModulePlaceholderScreen("Send to PC") { nav.popBackStack() } }
        }
    }
}
