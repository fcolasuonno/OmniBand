package nodomain.freeyourgadget.gadgetbridge.ui.screen

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import nodomain.freeyourgadget.gadgetbridge.ble.ConnectionState
import nodomain.freeyourgadget.gadgetbridge.ble.DeviceType
import nodomain.freeyourgadget.gadgetbridge.ble.ScannedDevice
import nodomain.freeyourgadget.gadgetbridge.ui.viewmodel.ScanViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DeviceScanScreen(
    navController: NavController,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val devices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    val permissions = rememberMultiplePermissionsState(
        permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    )

    var showAuthKeyDialog by remember { mutableStateOf<ScannedDevice?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Add Device",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Supported: Xiaomi Smart Band 7 · Sony WF-1000XM5",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        // Permission gate
        if (!permissions.allPermissionsGranted) {
            PermissionCard(onGrantClick = { permissions.launchMultiplePermissionRequest() })
        } else {
            // Scan button
            Button(
                onClick = {
                    if (isScanning) {
                        viewModel.stopScan()
                        isScanning = false
                    } else {
                        viewModel.startScan()
                        isScanning = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning… (tap to stop)")
                } else {
                    Icon(Icons.Filled.Search, "Scan", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan for Devices")
                }
            }

            // Connection status if connecting
            AnimatedVisibility(connectionState is ConnectionState.Connecting ||
                    connectionState is ConnectionState.Initializing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (devices.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.BluetoothSearching,
                            "No devices",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Text(
                            "No devices found yet.\nTap Scan to search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    "${devices.size} device(s) found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(devices, key = { it.address }) { device ->
                        ScannedDeviceCard(
                            device = device,
                            onConnect = {
                                if (device.deviceType == DeviceType.XIAOMI_SMART_BAND_7) {
                                    showAuthKeyDialog = device
                                } else {
                                    viewModel.connectToDevice(device, null)
                                    isScanning = false
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Auth key dialog for Mi Band 7
    showAuthKeyDialog?.let { device ->
        AuthKeyDialog(
            deviceName = device.name ?: "Xiaomi Smart Band 7",
            onConfirm = { authKey ->
                viewModel.connectToDevice(device, authKey.ifBlank { null })
                showAuthKeyDialog = null
                isScanning = false
            },
            onDismiss = { showAuthKeyDialog = null }
        )
    }
}

@Composable
fun ScannedDeviceCard(device: ScannedDevice, onConnect: () -> Unit) {
    val (deviceIcon, deviceColor) = when (device.deviceType) {
        DeviceType.XIAOMI_SMART_BAND_7 -> Icons.Filled.Watch to MaterialTheme.colorScheme.primary
        DeviceType.SONY_WF1000XM5     -> Icons.Filled.Headphones to MaterialTheme.colorScheme.secondary
        null                           -> Icons.Filled.Bluetooth to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.deviceType != null)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = deviceColor.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(deviceIcon, device.name ?: "Device", tint = deviceColor, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                // MAC address — essential when several bands advertise the same name
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (device.deviceType != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = deviceColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "Supported",
                            style = MaterialTheme.typography.labelSmall,
                            color = deviceColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuthKeyDialog(
    deviceName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var authKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    // The extractor prints the key with a "0x" prefix — accept both forms
    val normalizedKey = authKey.trim().removePrefix("0x").removePrefix("0X")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Key, "Auth Key") },
        title = { Text("Auth Key Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the 32-character auth key for your $deviceName. " +
                    "This is required to authenticate with the band. " +
                    "You can find it via your Xiaomi account using tools like xiaomi-cloud-tokens-extractor.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = authKey,
                    onValueChange = { authKey = it.trim() },
                    label = { Text("Auth Key (32 hex chars)") },
                    placeholder = { Text("00112233445566778899aabbccddeeff") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                "Toggle visibility"
                            )
                        }
                    },
                    isError = normalizedKey.isNotEmpty() && normalizedKey.length != 32,
                    supportingText = if (normalizedKey.isNotEmpty() && normalizedKey.length != 32) {
                        { Text("Key must be 32 hex characters (0x prefix optional)") }
                    } else null
                )
                TextButton(onClick = { onConfirm("") }, modifier = Modifier.align(Alignment.End)) {
                    Text("Skip (may not authenticate)", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(normalizedKey) },
                enabled = normalizedKey.length == 32 || normalizedKey.isEmpty()
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PermissionCard(onGrantClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.BluetoothDisabled, "Permissions", tint = MaterialTheme.colorScheme.error)
                Text("Bluetooth Permission Required", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(
                "OmniBand needs Bluetooth Scan and Connect permissions to discover and communicate with your devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
            Button(onClick = onGrantClick, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )) {
                Text("Grant Permissions")
            }
        }
    }
}
