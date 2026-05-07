package com.omniband.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omniband.service.DeviceService
import com.omniband.ui.screen.*
import com.omniband.ui.theme.OmniBandTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val label: String) {
    object Dashboard : Screen("dashboard", "Dashboard")
    object Health    : Screen("health", "Health")
    object Devices   : Screen("devices", "Devices")
    object Settings  : Screen("settings", "Settings")
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the foreground service as soon as the app opens
        startForegroundService(DeviceService.startIntent(this))

        setContent {
            OmniBandTheme {
                OmniBandApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniBandApp() {
    val navController = rememberNavController()
    val navItems = listOf(
        Triple(Screen.Dashboard, Icons.Filled.Watch,    "Dashboard"),
        Triple(Screen.Health,    Icons.Filled.Favorite, "Health"),
        Triple(Screen.Devices,   Icons.Filled.Bluetooth,"Devices"),
        Triple(Screen.Settings,  Icons.Filled.Settings, "Settings")
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val currentEntry by navController.currentBackStackEntryAsState()
                navItems.forEach { (screen, icon, label) ->
                    NavigationBarItem(
                        icon = { Icon(icon, label) },
                        label = { Text(label) },
                        selected = currentEntry?.destination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(navController) }
            composable(Screen.Health.route)    { HealthScreen() }
            composable(Screen.Devices.route)   { DeviceScanScreen(navController) }
            composable(Screen.Settings.route)  { SettingsScreen() }
        }
    }
}
