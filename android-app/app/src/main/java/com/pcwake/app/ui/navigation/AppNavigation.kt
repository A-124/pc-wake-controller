package com.pcwake.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pcwake.app.AppContainer
import com.pcwake.app.data.AppSettings
import com.pcwake.app.ui.screens.AboutScreen
import com.pcwake.app.ui.screens.BridgeSetupScreen
import com.pcwake.app.ui.screens.DashboardScreen
import com.pcwake.app.ui.screens.DeviceDetailsScreen
import com.pcwake.app.ui.screens.DeviceEditScreen
import com.pcwake.app.ui.screens.DiagnosticsScreen
import com.pcwake.app.ui.screens.HistoryScreen
import com.pcwake.app.ui.screens.OnboardingScreen
import com.pcwake.app.ui.screens.PairingScreen
import com.pcwake.app.ui.screens.PowerScreen
import com.pcwake.app.ui.screens.SettingsScreen
import com.pcwake.app.ui.screens.WolConfigScreen
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.ThemeMode
import com.pcwake.app.ui.components.BrandLoading
import com.pcwake.app.ui.theme.PcWakeTheme
import kotlinx.coroutines.launch

object Routes {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val ADD_PC = "add-pc"
    const val EDIT = "edit/{deviceId}"
    const val DETAILS = "details/{deviceId}"
    const val POWER = "power/{deviceId}"
    const val WOL = "wol/{deviceId}"
    const val DIAGNOSTICS = "diagnostics/{deviceId}"
    const val PAIRING = "pairing/{kind}"
    const val BRIDGE_SETUP = "bridge"

    fun edit(id: Long) = "edit/$id"
    fun details(id: Long) = "details/$id"
    fun power(id: Long) = "power/$id"
    fun wol(id: Long) = "wol/$id"
    fun diagnostics(id: Long) = "diagnostics/$id"
    fun pairing(mode: ConnectionMode) = "pairing/${mode.wire}"
}

@Composable
fun PcWakeAppRoot(container: AppContainer) {
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(container) {
        container.settingsRepository.settings.collect { settings = it }
    }
    val loaded = settings
    if (loaded == null) {
        PcWakeTheme(ThemeMode.DARK) {
            Box(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentAlignment = Alignment.Center,
            ) { BrandLoading() }
        }
        return
    }
    PcWakeTheme(loaded.theme) {
        val scope = rememberCoroutineScope()
        if (!loaded.onboarded) {
            OnboardingScreen(
                onDone = {
                    scope.launch { container.settingsRepository.setOnboarded(true) }
                },
            )
        } else {
            PcWakeNavigation(container)
        }
    }
}

@Composable
fun PcWakeNavigation(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = listOf(Routes.DASHBOARD, Routes.HISTORY, Routes.SETTINGS)
    fun goTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in topLevel) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.DASHBOARD,
                        onClick = { goTop(Routes.DASHBOARD) },
                        icon = { Icon(Icons.Rounded.Home, null) },
                        label = { Text("Dashboard") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.HISTORY,
                        onClick = { goTop(Routes.HISTORY) },
                        icon = { Icon(Icons.Rounded.History, null) },
                        label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { goTop(Routes.SETTINGS) },
                        icon = { Icon(Icons.Rounded.Settings, null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(bottom = inner.calculateBottomPadding()),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    container = container,
                    onAddPc = { navController.navigate(Routes.ADD_PC) },
                    onOpenDevice = { id -> navController.navigate(Routes.details(id)) },
                    onOpenHistory = { goTop(Routes.HISTORY) },
                    onPair = { navController.navigate(Routes.pairing(ConnectionMode.AGENT)) },
                    onOpenBridgeSetup = { navController.navigate(Routes.BRIDGE_SETUP) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(container)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onBridgeSetup = { navController.navigate(Routes.BRIDGE_SETUP) },
                    onPairAgent = { navController.navigate(Routes.pairing(ConnectionMode.AGENT)) },
                    onDiagnostics = { navController.navigate(Routes.diagnostics(0)) },
                )
            }
            composable(Routes.ADD_PC) {
                DeviceEditScreen(
                    container = container,
                    deviceId = 0,
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.navigate(Routes.details(id)) {
                            popUpTo(Routes.DASHBOARD)
                        }
                    },
                )
            }
            composable(
                Routes.EDIT,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: 0L
                DeviceEditScreen(
                    container = container,
                    deviceId = id,
                    onBack = { navController.popBackStack() },
                    onSaved = { savedId ->
                        navController.navigate(Routes.details(savedId)) {
                            popUpTo(Routes.DASHBOARD)
                        }
                    },
                )
            }
            composable(
                Routes.DETAILS,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: 0L
                DeviceDetailsScreen(
                    container = container,
                    deviceId = id,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.edit(it)) },
                    onWolConfig = { navController.navigate(Routes.wol(it)) },
                    onDiagnostics = { navController.navigate(Routes.diagnostics(it)) },
                    onPowerControls = { navController.navigate(Routes.power(it)) },
                    onOpenDevice = { navController.navigate(Routes.details(it)) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(
                Routes.POWER,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: 0L
                PowerScreen(container, id, onBack = { navController.popBackStack() })
            }
            composable(
                Routes.WOL,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: 0L
                WolConfigScreen(
                    container = container,
                    deviceId = id,
                    onBack = { navController.popBackStack() },
                    onDiagnostics = { navController.navigate(Routes.diagnostics(it)) },
                )
            }
            composable(
                Routes.DIAGNOSTICS,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: 0L
                DiagnosticsScreen(container, id, onBack = { navController.popBackStack() })
            }
            composable(Routes.BRIDGE_SETUP) {
                BridgeSetupScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onPair = { navController.navigate(Routes.pairing(ConnectionMode.BRIDGE)) },
                )
            }
            composable(
                Routes.PAIRING,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { entry ->
                val mode = ConnectionMode.fromWire(entry.arguments?.getString("kind"))
                    ?: ConnectionMode.AGENT
                PairingScreen(
                    container = container,
                    initialMode = mode,
                    onBack = { navController.popBackStack() },
                    onCreated = { id ->
                        navController.navigate(Routes.details(id)) {
                            popUpTo(Routes.DASHBOARD)
                        }
                    },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
