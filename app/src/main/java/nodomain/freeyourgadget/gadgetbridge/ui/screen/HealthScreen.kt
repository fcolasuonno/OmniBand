package nodomain.freeyourgadget.gadgetbridge.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity
import nodomain.freeyourgadget.gadgetbridge.ui.theme.BatteryAmber
import nodomain.freeyourgadget.gadgetbridge.ui.theme.HeartRed
import nodomain.freeyourgadget.gadgetbridge.ui.theme.SleepBlue
import nodomain.freeyourgadget.gadgetbridge.ui.theme.StepGreen
import nodomain.freeyourgadget.gadgetbridge.ui.viewmodel.HealthViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen(viewModel: HealthViewModel = hiltViewModel()) {
    val heartRates  by viewModel.recentHeartRates.collectAsStateWithLifecycle()
    val steps       by viewModel.last30DaysSteps.collectAsStateWithLifecycle()
    val spO2List    by viewModel.recentSpO2.collectAsStateWithLifecycle()
    val sleepSessions by viewModel.sleepSessions.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Heart Rate", "Steps", "Sleep", "SpO2")

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        when (selectedTab) {
            0 -> HeartRateTab(heartRates)
            1 -> StepsTab(steps)
            2 -> SleepTab(sleepSessions)
            3 -> SpO2Tab(spO2List)
        }
    }
}

// ─────────────────────────────────────────────
// Heart Rate Tab
// ─────────────────────────────────────────────

@Composable
fun HeartRateTab(readings: List<HeartRateEntity>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Stats summary
            if (readings.isNotEmpty()) {
                val avg = readings.map { it.bpm }.average().toInt()
                val max = readings.maxOf { it.bpm }
                val min = readings.minOf { it.bpm }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        HrStatBox("Avg", "$avg", "bpm", HeartRed)
                        VerticalDivider(modifier = Modifier.height(48.dp))
                        HrStatBox("Max", "$max", "bpm", MaterialTheme.colorScheme.error)
                        VerticalDivider(modifier = Modifier.height(48.dp))
                        HrStatBox("Min", "$min", "bpm", Color(0xFF2196F3))
                    }
                }
            }
        }

        item {
            // Chart
            if (readings.isNotEmpty()) {
                HeartRateLineChart(readings = readings.takeLast(60))
            } else {
                EmptyState(icon = Icons.Filled.Favorite, message = "No heart rate data yet.\nConnect your device to start monitoring.")
            }
        }

        item {
            Text("Recent Readings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        items(readings.reversed().take(30)) { entity ->
            HrReadingRow(entity, timeFormat)
        }
    }
}

@Composable
fun HrStatBox(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = color)
        Text(unit, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
fun HeartRateLineChart(readings: List<HeartRateEntity>) {
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "hrChart"
    )
    val maxBpm = readings.maxOf { it.bpm }.toFloat().coerceAtLeast(100f)
    val minBpm = readings.minOf { it.bpm }.toFloat().coerceAtMost(50f)
    val range = maxBpm - minBpm

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.ShowChart, "HR Chart", tint = HeartRed, modifier = Modifier.size(16.dp))
                Text("Heart Rate (last ${readings.size} readings)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)) {
                val stepX = size.width / (readings.size - 1).coerceAtLeast(1)
                val drawnCount = (readings.size * animProgress).toInt().coerceAtLeast(1)
                val points = readings.take(drawnCount).mapIndexed { i, v ->
                    Offset(i * stepX, size.height - ((v.bpm - minBpm) / range) * size.height)
                }
                // Fill path
                if (points.size > 1) {
                    val fillPath = Path().apply {
                        moveTo(points.first().x, size.height)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, size.height)
                        close()
                    }
                    drawPath(fillPath, Brush.verticalGradient(
                        listOf(HeartRed.copy(alpha = 0.3f), Color.Transparent)
                    ))
                    // Line
                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        linePath,
                        HeartRed,
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

@Composable
fun HrReadingRow(entity: HeartRateEntity, timeFormat: SimpleDateFormat) {
    val zoneColor = when {
        entity.bpm < 60 -> Color(0xFF2196F3)
        entity.bpm < 100 -> StepGreen
        entity.bpm < 140 -> BatteryAmber
        else -> HeartRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = zoneColor.copy(alpha = 0.15f)) {
            Text(
                "${entity.bpm}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = zoneColor
            )
        }
        Text("bpm", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.weight(1f))
        Text(
            timeFormat.format(Date(entity.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

// ─────────────────────────────────────────────
// Steps Tab
// ─────────────────────────────────────────────

@Composable
fun StepsTab(stepsList: List<StepsEntity>) {
    if (stepsList.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Filled.DirectionsWalk, "No steps data yet.")
        }
        return
    }

    val maxSteps = stepsList.maxOf { it.count }.toFloat().coerceAtLeast(1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Today's highlight
            stepsList.firstOrNull()?.let { today ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = StepGreen.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Filled.DirectionsRun, "Steps", tint = StepGreen, modifier = Modifier.size(36.dp))
                        Column {
                            Text("Today", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Text("${today.count} steps", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = StepGreen
                            )
                            Text("${today.calories} kcal · ${"%.1f".format(today.distanceMeters / 1000f)} km",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        item {
            Text("Last 30 Days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        // Bar chart
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(16.dp)) {
                    val barWidth = size.width / stepsList.size.coerceAtLeast(1)
                    stepsList.reversed().forEachIndexed { i, entity ->
                        val barHeight = (entity.count / maxSteps) * size.height
                        val x = i * barWidth
                        drawRoundRect(
                            color = StepGreen.copy(alpha = 0.8f),
                            topLeft = Offset(x + 2, size.height - barHeight),
                            size = Size(barWidth - 4, barHeight),
                            cornerRadius = CornerRadius(4f)
                        )
                    }
                }
            }
        }

        items(stepsList) { entity ->
            StepDayRow(entity)
        }
    }
}

@Composable
fun StepDayRow(entity: StepsEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(entity.date, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(90.dp))
        Text("${entity.count}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("${entity.calories} kcal", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

// ─────────────────────────────────────────────
// Sleep Tab
// ─────────────────────────────────────────────

@Composable
fun SleepTab(sessions: List<SleepSessionEntity>) {
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Filled.Bedtime, "No sleep data recorded.\nEnable Sleep as Android integration in Settings.")
        }
        return
    }

    val dtFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Recent Sleep Sessions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        items(sessions) { session ->
            SleepSessionCard(session, dtFormat)
        }
    }
}

@Composable
fun SleepSessionCard(session: SleepSessionEntity, dtFormat: SimpleDateFormat) {
    val durationMs = (session.endTime ?: System.currentTimeMillis()) - session.startTime
    val hours = durationMs / 3_600_000
    val mins  = (durationMs % 3_600_000) / 60_000

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SleepBlue.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Filled.Bedtime, "Sleep", tint = SleepBlue, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dtFormat.format(Date(session.startTime)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "→ ${session.endTime?.let { dtFormat.format(Date(it)) } ?: "Ongoing"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${hours}h ${mins}m", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold, color = SleepBlue
                )
                Text(
                    if (session.source == "sleep_as_android") "via SaA" else "OmniBand",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// SpO2 Tab
// ─────────────────────────────────────────────

@Composable
fun SpO2Tab(readings: List<SpO2Entity>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (readings.isNotEmpty()) {
                val avg = readings.map { it.percent }.average().toInt()
                val latest = readings.first().percent

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (latest >= 95) Color(0xFF2196F3).copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Filled.Bloodtype, "SpO2",
                            tint = if (latest >= 95) Color(0xFF2196F3) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp))
                        Column {
                            Text("Latest SpO2", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Text("$latest%", style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (latest >= 95) Color(0xFF2196F3) else MaterialTheme.colorScheme.error)
                            if (latest < 95) {
                                Text("⚠ Below normal (95–100%)", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Avg", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text("$avg%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                EmptyState(Icons.Filled.Bloodtype, "No SpO2 data yet.")
            }
        }

        items(readings) { entity ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val color = if (entity.percent >= 95) Color(0xFF2196F3) else MaterialTheme.colorScheme.error
                Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
                    Text("${entity.percent}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(Modifier.weight(1f))
                Text(timeFormat.format(Date(entity.timestamp)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

// ─────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────

@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(icon, "Empty", modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            textAlign = TextAlign.Center
        )
    }
}
