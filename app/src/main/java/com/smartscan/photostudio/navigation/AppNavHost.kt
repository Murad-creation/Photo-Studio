package com.smartscan.photostudio.navigation

import android.util.Base64
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
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.smartscan.photostudio.core.transfer.TransferQueue
import com.smartscan.photostudio.feature.doc.DocScreen
import com.smartscan.photostudio.feature.nid.NidScreen
import com.smartscan.photostudio.feature.passport.PassportScreen
import com.smartscan.photostudio.feature.transfer.TransferScreen
import com.smartscan.photostudio.ui.components.SmartScanBottomNavigation
import com.smartscan.photostudio.ui.files.DocumentPreviewScreen
import com.smartscan.photostudio.ui.files.MyFilesScreen
import com.smartscan.photostudio.ui.home.HomeScreen
import com.smartscan.photostudio.ui.settings.SettingsScreen
import com.smartscan.photostudio.ui.theme.ThemeMode
import java.io.File

object Routes {
    const val HOME = "home"; const val FILES = "files"; const val SETTINGS = "settings"
    const val NID = "nid"; const val DOC = "document"; const val PASSPORT = "passport"; const val PC = "pc"
    const val PREVIEW = "preview/{p}"

    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    fun preview(rel: String) = "preview/" + Base64.encodeToString(rel.toByteArray(Charsets.UTF_8), B64)
    fun decodePreview(p: String?): String =
        runCatching { String(Base64.decode(p ?: "", B64), Charsets.UTF_8) }.getOrDefault("")
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
    val sendToPc: (List<File>) -> Unit = { files ->
        TransferQueue.set(files)
        nav.navigate(Routes.PC) { launchSingleTop = true }
    }
    val openFile: (String) -> Unit = { rel -> nav.navigate(Routes.preview(rel)) { launchSingleTop = true } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) SmartScanBottomNavigation(items = bottomItems, currentRoute = dest?.route, onSelect = ::goTop)
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
            composable(Routes.HOME) {
                HomeScreen(
                    onNavigate = { nav.navigate(it) { launchSingleTop = true } },
                    onOpenSettings = { goTop(Routes.SETTINGS) },
                    onOpenFile = openFile,
                )
            }
            composable(Routes.FILES) { MyFilesScreen(onOpen = openFile, onSendToPc = sendToPc) }
            composable(Routes.SETTINGS) { SettingsScreen(themeMode, onThemeChange) }
            composable(Routes.NID) { NidScreen(onExit = { nav.popBackStack() }, onSendToPc = sendToPc) }
            composable(Routes.DOC) { DocScreen(onExit = { nav.popBackStack() }, onSendToPc = sendToPc) }
            composable(Routes.PASSPORT) { PassportScreen(onExit = { nav.popBackStack() }, onSendToPc = sendToPc) }
            composable(Routes.PC) { TransferScreen(onBack = { nav.popBackStack() }, onOpenFiles = { goTop(Routes.FILES) }) }
            composable(Routes.PREVIEW, arguments = listOf(navArgument("p") { type = NavType.StringType })) { e ->
                DocumentPreviewScreen(
                    rel = Routes.decodePreview(e.arguments?.getString("p")),
                    onBack = { nav.popBackStack() },
                    onSendToPc = sendToPc,
                )
            }
        }
    }
}
