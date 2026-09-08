package nodomain.freeyourgadget.gadgetbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import nodomain.freeyourgadget.gadgetbridge.service.DeviceService
import nodomain.freeyourgadget.gadgetbridge.ui.screen.DashboardScreen
import nodomain.freeyourgadget.gadgetbridge.ui.screen.DeviceScanScreen
import nodomain.freeyourgadget.gadgetbridge.ui.screen.HealthScreen
import nodomain.freeyourgadget.gadgetbridge.ui.screen.SettingsScreen
import nodomain.freeyourgadget.gadgetbridge.ui.theme.OmniBandTheme

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
        // Start the foreground service only once BLE runtime permissions are granted,
        // otherwise startForeground(connectedDevice) throws SecurityException on API 31+.
        // The service is started later from ScanViewModel after permissions are granted.
        if (hasBlePermissions()) {
            startForegroundService(DeviceService.startIntent(this))
        }

        setContent {
            OmniBandTheme {
                OmniBandApp()
            }
        }
    }

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .all {
                    ContextCompat.checkSelfPermission(
                        this,
                        it
                    ) == PackageManager.PERMISSION_GRANTED
                }
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
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
