package com.omniband.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.omniband.OmniBandApp
import com.omniband.R
import com.omniband.ble.BleManager
import com.omniband.ble.ConnectionState
import com.omniband.ble.DeviceType
import com.omniband.ble.isConnected
import com.omniband.ble.protocol.DeviceEvent
import com.omniband.data.repository.DeviceRepository
import com.omniband.data.repository.HealthRepository
import com.omniband.data.repository.UserPreferencesRepository
import com.omniband.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that maintains the BLE connection to paired gadgets.
 *
 * This service:
 *  - Persists through app background / killed states
 *  - Auto-reconnects when Bluetooth comes back on or device is found nearby
 *  - Collects all [DeviceEvent]s and persists them to the database
 *  - Bridges Sleep as Android events to device protocols
 *  - Shows a persistent notification with connection status
 */
@AndroidEntryPoint
class DeviceService : LifecycleService() {

    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var healthRepository: HealthRepository
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var isServiceForeground = false

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISCONNECT = "com.omniband.action.DISCONNECT"
        const val ACTION_FIND_DEVICE = "com.omniband.action.FIND_DEVICE"
        const val ACTION_CONNECT = "com.omniband.action.CONNECT"

        fun startIntent(context: Context) =
            Intent(context, DeviceService::class.java).also { intent ->
                intent.action = ACTION_CONNECT
            }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Timber.i("DeviceService: created")
        
        val notification = buildNotification("Connecting…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isServiceForeground = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service")
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && (e is android.app.ForegroundServiceStartNotAllowedException)) {
                stopSelf()
            }
        }

        if (isServiceForeground) {
            observeConnectionState()
            observeDeviceEvents()
            autoConnectSavedDevice()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!isServiceForeground && intent?.action != ACTION_DISCONNECT) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                bleManager.disconnect()
                stopSelf()
            }
            ACTION_FIND_DEVICE -> {
                lifecycleScope.launch { bleManager.vibrate() }
            }
            ACTION_CONNECT -> autoConnectSavedDevice()
        }
        return START_STICKY  // Re-create if killed by OS
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Timber.i("DeviceService: destroyed")
        bleManager.destroy()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Auto-connect on startup
    // -------------------------------------------------------------------------

    private fun autoConnectSavedDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = preferencesRepository
            val address = prefs.activeDeviceAddress.first() ?: run {
                Timber.d("DeviceService: no saved device address")
                return@launch
            }
            val typeName = prefs.activeDeviceType.first() ?: return@launch
            val authKey = prefs.authKey.first()
            val autoReconnect = prefs.autoReconnect.first()

            if (!autoReconnect) return@launch

            val deviceType = runCatching { DeviceType.valueOf(typeName) }.getOrNull() ?: return@launch
            Timber.i("DeviceService: auto-connecting to $address ($deviceType)")
            bleManager.connect(address, deviceType, authKey)
        }
    }

    // -------------------------------------------------------------------------
    // Observe flows
    // -------------------------------------------------------------------------

    private fun observeConnectionState() {
        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                Timber.d("DeviceService: connection state → $state")
                val notifText = when (state) {
                    is ConnectionState.Connected     -> "Connected to ${state.deviceType.displayName}"
                    is ConnectionState.Connecting    -> "Connecting…"
                    is ConnectionState.Initializing  -> "Initializing…"
                    is ConnectionState.Reconnecting  -> "Reconnecting (attempt ${state.attempt})…"
                    is ConnectionState.Scanning      -> "Scanning…"
                    is ConnectionState.Disconnected  -> "Disconnected"
                    is ConnectionState.Error         -> "Error: ${state.message}"
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(notifText))

                if (state.isConnected) {
                    val address = (state as ConnectionState.Connected).address
                    deviceRepository.updateLastConnected(address)
                }
            }
        }
    }

    private fun observeDeviceEvents() {
        lifecycleScope.launch(Dispatchers.IO) {
            val address = preferencesRepository.activeDeviceAddress.first() ?: return@launch

            bleManager.deviceEvents.collect { event ->
                when (event) {
                    is DeviceEvent.HeartRate  -> healthRepository.saveHeartRate(address, event.bpm)
                    is DeviceEvent.Steps      -> healthRepository.saveSteps(address, event.count, event.calories, event.distance)
                    is DeviceEvent.Battery    -> healthRepository.saveBattery(address, event.percent, event.charging)
                    is DeviceEvent.SpO2       -> healthRepository.saveSpO2(address, event.percent)
                    is DeviceEvent.DeviceReady -> {
                        Timber.i("DeviceService: device ready, syncing time")
                        launch { bleManager.syncTime() }
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(contentText: String) = NotificationCompat.Builder(this, OmniBandApp.CHANNEL_DEVICE_SERVICE)
        .setSmallIcon(R.drawable.ic_watch)  // provide this drawable
        .setContentTitle("OmniBand")
        .setContentText(contentText)
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0, "Find Device",
            PendingIntent.getService(
                this, 1,
                Intent(this, DeviceService::class.java).apply { action = ACTION_FIND_DEVICE },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0, "Disconnect",
            PendingIntent.getService(
                this, 2,
                Intent(this, DeviceService::class.java).apply { action = ACTION_DISCONNECT },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}

// -----------------------------------------------------------------
// Boot Receiver
// -----------------------------------------------------------------

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) {
            Timber.i("BootReceiver: starting DeviceService after boot")
            context.startForegroundService(DeviceService.startIntent(context))
        }
    }
}

// -----------------------------------------------------------------
// Bluetooth State Receiver
// -----------------------------------------------------------------

class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                    android.bluetooth.BluetoothAdapter.ERROR
                )
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Timber.i("BluetoothStateReceiver: BT turned on — starting service")
                    context.startForegroundService(DeviceService.startIntent(context))
                }
            }
        }
    }
}
