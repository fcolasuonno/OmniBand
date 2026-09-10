package nodomain.freeyourgadget.gadgetbridge.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import nodomain.freeyourgadget.gadgetbridge.ui.viewmodel.NotificationsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    navController: NavController,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val enabledApps by viewModel.enabledApps.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val listenerEnabled by viewModel.listenerEnabled.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    // Re-check listener access when returning from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshListenerState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val visible = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Mirroring") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsCard {
                    SettingsToggleRow(
                        icon = Icons.Filled.Notifications,
                        title = "Mirror Notifications",
                        subtitle = "Forward phone notifications to the band",
                        checked = masterEnabled,
                        onCheckedChange = { viewModel.setMasterEnabled(it) }
                    )
                }
            }

            if (!listenerEnabled) {
                item {
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.NotificationsOff,
                                    "Permission needed",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Notification Access Required",
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "OmniBand needs notification access in system settings to read and forward notifications.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                )
                            }) { Text("Grant Access") }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item {
                val enabledCount = apps.count { it.packageName in enabledApps }
                Text(
                    "$enabledCount of ${apps.size} apps enabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            items(visible, key = { it.packageName }) { app ->
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setAppEnabled(
                                app.packageName,
                                app.packageName !in enabledApps
                            )
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (app.pinned)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (app.icon != null) {
                            Image(
                                app.icon, app.label,
                                modifier = Modifier.size(40.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.CheckCircle, app.label,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                if (app.pinned) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Star,
                                        "Pinned favorite",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Checkbox(
                            checked = app.packageName in enabledApps,
                            onCheckedChange = {
                                viewModel.setAppEnabled(app.packageName, it)
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
