package nodomain.freeyourgadget.gadgetbridge.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import nodomain.freeyourgadget.gadgetbridge.ble.ConnectionState
import nodomain.freeyourgadget.gadgetbridge.ble.DeviceType
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.ANCMode
import nodomain.freeyourgadget.gadgetbridge.ui.theme.BatteryAmber
import nodomain.freeyourgadget.gadgetbridge.ui.theme.HeartRed
import nodomain.freeyourgadget.gadgetbridge.ui.theme.StepGreen
import nodomain.freeyourgadget.gadgetbridge.ui.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle(null)
    val lastHR by viewModel.lastHeartRate.collectAsStateWithLifecycle()
    val todaySteps by viewModel.todaySteps.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val lastSpO2 by viewModel.lastSpO2.collectAsStateWithLifecycle()
    val ancMode by viewModel.ancMode.collectAsStateWithLifecycle()
    val stepGoal by viewModel.stepGoal.collectAsStateWithLifecycle(8000)
    val recentHR by viewModel.recentHeartRates.collectAsStateWithLifecycle(emptyList())

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- Header ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "OmniBand",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                activeDevice?.let {
                    Text(
                        it.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            BatteryIndicator(battery?.percent, battery?.charging == true)
        }

        // ---- Connection Status Card ----
        ConnectionStatusCard(
            state = connectionState,
            onFindDevice = { viewModel.vibrate() },
            onDisconnect = { viewModel.disconnect() }
        )

        // Only show health tiles when connected
        if (connectionState is ConnectionState.Connected) {
            // ---- Heart Rate & SpO2 Row ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Favorite,
                    iconColor = HeartRed,
                    label = "Heart Rate",
                    value = lastHR?.let { "$it" } ?: "--",
                    unit = "bpm",
                    gradient = Brush.verticalGradient(
                        listOf(HeartRed.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Bloodtype,
                    iconColor = Color(0xFF2196F3),
                    label = "SpO2",
                    value = lastSpO2?.let { "$it" } ?: "--",
                    unit = "%",
                    gradient = Brush.verticalGradient(
                        listOf(Color(0xFF2196F3).copy(alpha = 0.15f), Color.Transparent)
                    )
                )
            }

            // ---- Steps Progress Card ----
            StepsCard(
                steps = todaySteps?.count ?: 0,
                goal = stepGoal,
                calories = todaySteps?.calories ?: 0,
                distanceMeters = todaySteps?.distanceMeters ?: 0f
            )

            // ---- Heart Rate mini-chart (last 20 readings) ----
            if (recentHR.isNotEmpty()) {
                HeartRateSparklineCard(readings = recentHR.takeLast(20).map { it.bpm })
            }

            // ---- Sony ANC Control (only when Sony device) ----
            val deviceType = (connectionState as? ConnectionState.Connected)?.deviceType
            if (deviceType == DeviceType.SONY_WF1000XM5) {
                AncModeCard(currentMode = ancMode)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Connection Status Card
// ---------------------------------------------------------------------------

@Composable
fun ConnectionStatusCard(
    state: ConnectionState,
    onFindDevice: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (statusText, statusColor, showActions) = when (state) {
        is ConnectionState.Connected    -> Triple("Connected", StepGreen, true)
        is ConnectionState.Connecting   -> Triple("Connecting…", BatteryAmber, false)
        is ConnectionState.Initializing -> Triple("Initializing…", BatteryAmber, false)
        is ConnectionState.Reconnecting -> Triple(
            "Reconnecting (attempt ${state.attempt})…",
            BatteryAmber, false
        )
        is ConnectionState.Scanning     -> Triple("Scanning…", Color(0xFF9C27B0), false)
        is ConnectionState.Disconnected -> Triple("Disconnected", MaterialTheme.colorScheme.error, false)
        is ConnectionState.Error        -> Triple("Error: ${state.message}", MaterialTheme.colorScheme.error, false)
    }

    val infiniteTransition = rememberInfiniteTransition("pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(if (state is ConnectionState.Reconnecting) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier.weight(1f)
            )
            if (showActions) {
                IconButton(onClick = onFindDevice, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Vibration, "Find Device",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDisconnect, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.BluetoothDisabled, "Disconnect",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Metric Card
// ---------------------------------------------------------------------------

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconColor: Color,
    label: String,
    value: String,
    unit: String,
    gradient: Brush
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.background(gradient)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, label, tint = iconColor, modifier = Modifier.size(18.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        value,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Steps Card
// ---------------------------------------------------------------------------

@Composable
fun StepsCard(steps: Int, goal: Int, calories: Int, distanceMeters: Float) {
    val progress = (steps.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    val progressAnim by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "stepsProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.DirectionsWalk, "Steps", tint = StepGreen, modifier = Modifier.size(20.dp))
                Text("Steps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("$steps / $goal", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }

            LinearProgressIndicator(
                progress = { progressAnim },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = StepGreen,
                trackColor = StepGreen.copy(alpha = 0.15f)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StepsSubMetric(label = "Calories", value = "$calories kcal")
                StepsSubMetric(label = "Distance", value = "%.1f km".format(distanceMeters / 1000f))
                StepsSubMetric(label = "Goal", value = "${(progress * 100).toInt()}%")
            }
        }
    }
}

@Composable
fun StepsSubMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

// ---------------------------------------------------------------------------
// Heart Rate Sparkline
// ---------------------------------------------------------------------------

@Composable
fun HeartRateSparklineCard(readings: List<Int>) {
    if (readings.isEmpty()) return
    val max = readings.max().toFloat()
    val min = readings.min().toFloat()
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ShowChart, "HR Chart", tint = HeartRed, modifier = Modifier.size(18.dp))
                Text("Recent Heart Rate", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("${readings.last()} bpm", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = HeartRed
                )
            }

            // Simple canvas sparkline
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                val stepX = size.width / (readings.size - 1).coerceAtLeast(1)
                val points = readings.mapIndexed { i, v ->
                    Offset(
                        x = i * stepX,
                        y = size.height - ((v - min) / range) * size.height
                    )
                }
                // Draw line segments
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = HeartRed,
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
                // Draw last point dot
                points.lastOrNull()?.let { pt ->
                    drawCircle(color = HeartRed, radius = 6f, center = pt)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Battery Indicator
// ---------------------------------------------------------------------------

@Composable
fun BatteryIndicator(percent: Int?, charging: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (charging) Icon(Icons.Filled.BatteryChargingFull, "Charging", tint = BatteryAmber, modifier = Modifier.size(20.dp))
        val batteryIcon = when {
            percent == null         -> Icons.Filled.BatteryUnknown
            percent > 80            -> Icons.Filled.BatteryFull
            percent > 50            -> Icons.Filled.Battery4Bar
            percent > 20            -> Icons.Filled.Battery2Bar
            else                    -> Icons.Filled.Battery0Bar
        }
        val batteryColor = when {
            percent == null   -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            percent > 20      -> StepGreen
            else              -> MaterialTheme.colorScheme.error
        }
        Icon(batteryIcon, "Battery", tint = batteryColor, modifier = Modifier.size(20.dp))
        percent?.let {
            Text("$it%", style = MaterialTheme.typography.labelMedium, color = batteryColor)
        }
    }
}

// ---------------------------------------------------------------------------
// ANC Mode Card (Sony)
// ---------------------------------------------------------------------------

@Composable
fun AncModeCard(currentMode: ANCMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Headphones, "ANC", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Noise Control", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ANCMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ANCMode.OFF -> "Off"
                        ANCMode.NOISE_CANCELLING -> "NC"
                        ANCMode.AMBIENT -> "Ambient"
                        ANCMode.WIND_REDUCTION -> "Wind"
                    }
                    FilterChip(
                        selected = mode == currentMode,
                        onClick = { /* TODO: send ANC command */ },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}
