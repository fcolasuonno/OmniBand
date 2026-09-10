package nodomain.freeyourgadget.gadgetbridge.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNotificationsClick: () -> Unit = {}
) {
    val autoReconnect       by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val sleepAndroid        by viewModel.sleepAsAndroidEnabled.collectAsStateWithLifecycle()
    val stepGoal            by viewModel.dailyStepGoal.collectAsStateWithLifecycle()
    val disableIdleAlert by viewModel.disableIdleAlert.collectAsStateWithLifecycle()
    val savedDevices        by viewModel.savedDevices.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var stepGoalInput by remember(stepGoal) { mutableStateOf(stepGoal.toString()) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var authKeyInput by remember { mutableStateOf("") }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ── Paired Devices ─────────────────────────────────────────
        SectionHeader(Icons.Filled.Watch, "Paired Devices")
        if (savedDevices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    "No devices paired yet. Go to Devices tab to scan.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            savedDevices.forEach { device ->
                DeviceSettingsCard(
                    device = device,
                    onRemove = { viewModel.removeDevice(device.address) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Connectivity ────────────────────────────────────────────
        SectionHeader(Icons.Filled.Bluetooth, "Connectivity")
        SettingsCard {
            SettingsToggleRow(
                icon = Icons.Filled.Autorenew,
                title = "Auto Reconnect",
                subtitle = "Automatically reconnect when device is nearby",
                checked = autoReconnect,
                onCheckedChange = { viewModel.setAutoReconnect(it) }
            )
        }

        // ── Notifications ───────────────────────────────────────────
        SectionHeader(Icons.Filled.Notifications, "Notifications")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNotificationsClick)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    "Notifications",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notification Mirroring", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Choose which apps forward to the band",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "Open",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        // ── Authentication ──────────────────────────────────────────
        SectionHeader(Icons.Filled.Key, "Xiaomi Authentication")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Xiaomi Smart Band 7 requires a 16-byte auth key obtained from your Xiaomi account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedButton(
                    onClick = { showAuthDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Edit, "Edit key", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Update Auth Key")
                }
            }
        }

        // ── Sleep as Android ────────────────────────────────────────
        SectionHeader(Icons.Filled.Bedtime, "Sleep as Android")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsToggleRow(
                    icon = Icons.Filled.Bedtime,
                    title = "Enable Integration",
                    subtitle = "Receive sleep events from Sleep as Android and control your device",
                    checked = sleepAndroid,
                    onCheckedChange = { viewModel.setSleepAsAndroid(it) }
                )
                if (sleepAndroid) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("When Sleep as Android is active:", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        BulletPoint("Continuous heart rate & SpO2 monitoring is enabled on the band")
                        BulletPoint("Alarm events trigger vibration on the band to wake you up")
                        BulletPoint("Accelerometer data is forwarded to Sleep as Android for actigraphy")
                        BulletPoint("Sony WF-1000XM5 switches to Noise Cancelling mode during sleep")
                    }
                    OutlinedButton(
                        onClick = {
                            val activities = listOf(
                                "com.urbandroid.sleep.wearable.WearablePickerActivity",
                                "com.urbandroid.sleep.domain.wearable.WearablePickerActivity",
                                "com.urbandroid.sleep.settings.WearablePickerActivity"
                            )
                            var success = false
                            for (activity in activities) {
                                try {
                                    val intent = Intent().apply {
                                        setClassName("com.urbandroid.sleep", activity)
                                    }
                                    context.startActivity(intent)
                                    success = true
                                    break
                                } catch (e: Exception) {
                                    continue
                                }
                            }
                            if (!success) {
                                try {
                                    val launchIntent =
                                        context.packageManager.getLaunchIntentForPackage("com.urbandroid.sleep")
                                    if (launchIntent != null) context.startActivity(launchIntent)
                                } catch (e: Exception) {
                                    // Sleep as Android not installed
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            "Open SaA",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open Sleep as Android")
                    }
                }
            }
        }

        // ── Device ─────────────────────────────────────────────────────
        SectionHeader(Icons.Filled.FitnessCenter, "Device")
        SettingsCard {
            SettingsToggleRow(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = "Disable Idle Alert",
                subtitle = "Stop the band's inactivity reminder vibrations",
                checked = disableIdleAlert,
                onCheckedChange = { viewModel.setDisabledIdleAlert(it) }
            )
        }

        // ── Fitness Goals ───────────────────────────────────────────
        SectionHeader(Icons.Filled.FitnessCenter, "Fitness Goals")
        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.DirectionsWalk,
                    "Steps",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily Step Goal", style = MaterialTheme.typography.bodyLarge)
                    Text("Current: $stepGoal steps", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                OutlinedTextField(
                    value = stepGoalInput,
                    onValueChange = { v ->
                        stepGoalInput = v
                        v.toIntOrNull()?.let { viewModel.setStepGoal(it) }
                    },
                    modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── About ───────────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        SectionHeader(Icons.Filled.Info, "About")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutRow("App", "OmniBand")
                AboutRow("Version", "1.0.0")
                AboutRow("Supported Devices", "Xiaomi Smart Band 7 · Sony WF-1000XM5")
                AboutRow("Protocol", "Huami 2021 (Mi Band) · Sony Headphones BLE")
                AboutRow("Sleep Integration", "Sleep as Android (urbandroid.com)")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Auth key dialog
    if (showAuthDialog) {
        // The extractor prints the key with a "0x" prefix — accept both forms
        val normalizedKey = authKeyInput.trim().removePrefix("0x").removePrefix("0X")
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            icon = { Icon(Icons.Filled.Key, "Auth Key") },
            title = { Text("Update Auth Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the 32-character hexadecimal auth key for your Xiaomi Smart Band 7.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = authKeyInput,
                        onValueChange = { authKeyInput = it.trim() },
                        label = { Text("Auth Key") },
                        placeholder = { Text("00112233445566778899aabbccddeeff") },
                        singleLine = true,
                        isError = normalizedKey.isNotEmpty() && normalizedKey.length != 32,
                        supportingText = if (normalizedKey.isNotEmpty() && normalizedKey.length != 32) {
                            { Text("Must be 32 hex characters (0x prefix optional)") }
                        } else null
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveAuthKey(normalizedKey)
                        showAuthDialog = false
                    },
                    enabled = normalizedKey.length == 32
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────

@Composable
fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(icon, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DeviceSettingsCard(device: DeviceEntity, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (device.deviceType.contains("SONY")) Icons.Filled.Headphones else Icons.Filled.Watch,
                device.name,
                tint = if (device.isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(device.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                if (device.isActive) {
                    Text("Active", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
