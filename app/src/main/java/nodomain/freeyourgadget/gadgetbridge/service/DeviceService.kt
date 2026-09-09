package nodomain.freeyourgadget.gadgetbridge.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.OmniBandApp
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import nodomain.freeyourgadget.gadgetbridge.ble.ConnectionState
import nodomain.freeyourgadget.gadgetbridge.ble.DeviceType
import nodomain.freeyourgadget.gadgetbridge.ble.isConnected
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.DeviceEvent
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.SleepStage
import nodomain.freeyourgadget.gadgetbridge.data.repository.DeviceRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.HealthRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.UserPreferencesRepository
import nodomain.freeyourgadget.gadgetbridge.ui.MainActivity
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Foreground service that owns the BLE connection to the user's paired gadget(s).
 *
 * ## Responsibilities
 * - Starts and keeps the connection alive across app background / process death
 * - Persists all [DeviceEvent]s to the Room database via [HealthRepository]
 * - Forwards HR and actigraphy data to Sleep as Android (when enabled in preferences)
 * - Bridges Sleep as Android broadcasts to the protocol via [SleepAsAndroidReceiver]
 * - Shows a persistent foreground notification with live connection status
 *
 * ## Startup sequence
 * 1. `onCreate` — show foreground notification, begin auto-connect
 * 2. `onStartCommand` — handle explicit `ACTION_CONNECT` / `ACTION_DISCONNECT` / `ACTION_FIND_DEVICE`
 * 3. `BootReceiver` — schedules `startForegroundService(startIntent)` after reboot
 * 4. `BluetoothStateReceiver` — re-triggers auto-connect when BT is switched back on
 *
 * ## Sleep as Android forwarding
 * HR data is sent to Sleep as Android only when [UserPreferencesRepository.sleepAsAndroidEnabled]
 * is `true`. Actigraphy (accelerometer) data is forwarded if the device ever emits
 * [DeviceEvent.RawAccelerometer] events — currently not the case for Mi Band 7 (ZeppOS does
 * not expose raw sensor access).
 */
@AndroidEntryPoint
class DeviceService : LifecycleService() {

    @Inject
    lateinit var bleManager: BleManager
    @Inject
    lateinit var healthRepository: HealthRepository
    @Inject
    lateinit var deviceRepository: DeviceRepository
    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository
    @Inject
    lateinit var sleepAsAndroidSender: SleepAsAndroidSender

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    companion object {
        const val NOTIFICATION_ID = 1004
        const val ACTION_DISCONNECT = "com.freeyourgadget.gadgetbridge.action.DISCONNECT"
        const val ACTION_FIND_DEVICE = "com.freeyourgadget.gadgetbridge.action.FIND_DEVICE"
        const val ACTION_CONNECT = "com.freeyourgadget.gadgetbridge.action.CONNECT"

        fun startIntent(context: Context): Intent =
            Intent(context, DeviceService::class.java).apply { action = ACTION_CONNECT }

        fun stopIntent(context: Context): Intent =
            Intent(context, DeviceService::class.java).apply { action = ACTION_DISCONNECT }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.i("DeviceService: created")

        // On API 31+ the FGS type `connectedDevice` is only valid while a BT runtime
        // permission is *granted*. Starting without one throws SecurityException.
        if (!hasBlePermissions()) {
            Timber.w("DeviceService: BLE permissions not granted — stopping; start again after they are granted")
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
        }

        observeConnectionState()
        observeDeviceEvents()
        observeIdleAlertSetting()
        schedulePeriodicHistorySync()
        autoConnectSavedDevice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                bleManager.disconnect(); stopSelf()
            }

            ACTION_FIND_DEVICE -> lifecycleScope.launch { bleManager.vibrate() }
            ACTION_CONNECT -> autoConnectSavedDevice()
        }
        return START_STICKY  // Re-create the service if the OS kills it under memory pressure
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null  // Not a bound service
    }

    override fun onDestroy() {
        Timber.i("DeviceService: destroyed")
        bleManager.destroy()
        super.onDestroy()
    }

    // ── Auto-connect ──────────────────────────────────────────────────────────

    private fun autoConnectSavedDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            val address = preferencesRepository.activeDeviceAddress.first() ?: run {
                Timber.d("DeviceService: no saved device address"); return@launch
            }
            val typeName = preferencesRepository.activeDeviceType.first() ?: return@launch
            val authKey = preferencesRepository.authKey.first()

            if (!preferencesRepository.autoReconnect.first()) return@launch

            val deviceType = runCatching { DeviceType.valueOf(typeName) }.getOrNull() ?: return@launch
            Timber.i("DeviceService: auto-connecting to %s (%s)", address, deviceType)
            bleManager.connect(address, deviceType, authKey)
        }
    }

    // ── Event observation ─────────────────────────────────────────────────────

    private fun observeConnectionState() {
        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                Timber.d("DeviceService: connection state → %s", state)
                val text = when (state) {
                    is ConnectionState.Connected -> "Connected to ${state.deviceType.displayName}"
                    is ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Initializing -> "Initializing…"
                    is ConnectionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})…"
                    is ConnectionState.Scanning -> "Scanning…"
                    is ConnectionState.Disconnected -> "Disconnected"
                    is ConnectionState.Error -> "Error: ${state.message}"
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(text))

                if (state.isConnected) {
                    deviceRepository.updateLastConnected((state as ConnectionState.Connected).address)
                }
            }
        }
    }

    /**
     * Collects [DeviceEvent]s and persists them to the database.
     *
     * Sleep as Android forwarding happens here too, but only when the user has enabled
     * the integration via the [UserPreferencesRepository.sleepAsAndroidEnabled] preference.
     *
     * The flow is restarted whenever the active device address changes via [flatMapLatest],
     * so a device re-pair does not leave a stale address baked in.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDeviceEvents() {
        lifecycleScope.launch(Dispatchers.IO) {
            preferencesRepository.activeDeviceAddress
                .flatMapLatest { address ->
                    if (address == null) flowOf() else bleManager.deviceEvents
                        .map { event -> address to event }
                }
                .collect { (address, event) ->
                    val sleepEnabled = preferencesRepository.sleepAsAndroidEnabled.first()
                    handleEvent(address, event, sleepEnabled)
                }
        }
    }

    private suspend fun handleEvent(address: String, event: DeviceEvent, sleepEnabled: Boolean) {
        when (event) {
            is DeviceEvent.HeartRate -> {
                healthRepository.saveHeartRate(address, event.bpm)
                if (sleepEnabled) sleepAsAndroidSender.onHeartRateChanged(event.bpm)
            }

            is DeviceEvent.Steps ->
                healthRepository.saveSteps(address, event.count, event.calories, event.distance)

            is DeviceEvent.Battery ->
                healthRepository.saveBattery(address, event.percent, event.charging)

            is DeviceEvent.SpO2 ->
                healthRepository.saveSpO2(address, event.percent)

            is DeviceEvent.RawAccelerometer -> {
                // Raw accelerometer samples (Mi Band classic 0x0002 stream, active during
                // sleep tracking). Forwarded as actigraphy magnitudes when enabled below.
                if (sleepEnabled) {
                    val magnitude = sqrt(
                        (event.x * event.x + event.y * event.y + event.z * event.z).toDouble()
                    ).toFloat()
                    sleepAsAndroidSender.onMovementData(magnitude)
                }
            }

            is DeviceEvent.DeviceReady -> {
                Timber.i("DeviceService: device ready — syncing time")
                lifecycleScope.launch { bleManager.syncTime() }
                applyIdleAlertSetting()
                fetchSleepHistoryAuto()
            }

            is DeviceEvent.SleepData -> handleSleepState(address, event)

            else -> Unit
        }
    }

    // ── Sleep session tracking ────────────────────────────────────────────

    private var activeSleepSessionId: Long? = null
    private var activeSleepAddress: String? = null

    /**
     * Turns band sleep-state events ([DeviceEvent.SleepData], from the 0x001D
     * fall-asleep/wake-up notification) into persisted sleep sessions + stages.
     */
    private suspend fun handleSleepState(address: String, event: DeviceEvent.SleepData) {
        if (event.stage == SleepStage.AWAKE) {
            val id = activeSleepSessionId?.takeIf { activeSleepAddress == address } ?: return
            healthRepository.addSleepStage(id, SleepStage.AWAKE.name)
            healthRepository.endSleepSession(id)
            Timber.i("DeviceService: sleep session %d ended", id)
            activeSleepSessionId = null
            activeSleepAddress = null
        } else {
            if (activeSleepSessionId == null || activeSleepAddress != address) {
                activeSleepSessionId = healthRepository.startSleepSession(address)
                activeSleepAddress = address
                Timber.i("DeviceService: sleep session %d started", activeSleepSessionId)
            }
            healthRepository.addSleepStage(activeSleepSessionId!!, event.stage.name)
        }
    }

    // Apply the idle-alert setting on connection and on live changes
    private fun applyIdleAlertSetting() {
        lifecycleScope.launch {
            val disabled = preferencesRepository.disableIdleAlert.first()
            Timber.d("DeviceService: applying idle-alert setting (disabled=$disabled)")
            bleManager.setInactivityWarnings(!disabled)
        }
    }

    // Observe the idle-alert preference and re-apply on live changes while connected
    private fun observeIdleAlertSetting() {
        lifecycleScope.launch {
            preferencesRepository.disableIdleAlert
                .distinctUntilChanged()
                .collect { disabled ->
                    // Only apply if we're currently connected
                    if (bleManager.connectionState.value.isConnected) {
                        Timber.d("DeviceService: idle-alert setting changed (disabled=$disabled) — reapplying")
                        bleManager.setInactivityWarnings(!disabled)
                    }
                }
        }
    }

    /**
     * Re-pull recorded history every 15 minutes while connected, so values recorded
     * after the initial post-connect sync (e.g. a new stress reading) show up
     * without manual syncing.
     */
    private fun schedulePeriodicHistorySync() {
        lifecycleScope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                if (bleManager.connectionState.value.isConnected) {
                    Timber.d("DeviceService: periodic history sync")
                    fetchSleepHistoryAuto()
                }
            }
        }
    }

    /**
     * Pull recorded history (sleep, SpO2, stress) since the last successful fetch
     * (max 7 days back). Runs once per connection; failures are non-fatal.
     */
    private fun fetchSleepHistoryAuto() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val last = preferencesRepository.lastHistoryFetchMs.first()
                val since = maxOf(if (last > 0) last else 0L, now - 7L * 24 * 3600 * 1000)
                val address = preferencesRepository.activeDeviceAddress.first() ?: return@launch
                var importedSleep = 0
                var importedSpo2 = 0
                var importedStress = 0
                bleManager.withHistorySyncLock {
                    bleManager.fetchSleepHistory(since).forEach { s ->
                        if (healthRepository.importSleepSession(
                                address,
                                s.startMs,
                                s.endMs,
                                s.stages
                            ) != null
                        ) {
                            importedSleep++
                        }
                    }
                    bleManager.fetchSpo2History(since).forEach { s ->
                        if (healthRepository.importSpO2At(
                                address,
                                s.percent,
                                s.timestampMs
                            )
                        ) importedSpo2++
                    }
                    bleManager.fetchStressHistory(since).forEach { s ->
                        if (healthRepository.importStressAt(
                                address,
                                s.score,
                                s.timestampMs
                            )
                        ) importedStress++
                    }
                }
                preferencesRepository.setLastHistoryFetchMs(now)
                Timber.i(
                    "DeviceService: history sync: %d sleep, %d SpO2, %d stress new",
                    importedSleep, importedSpo2, importedStress
                )
            } catch (e: Exception) {
                Timber.w(e, "DeviceService: history sync failed")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, OmniBandApp.CHANNEL_DEVICE_SERVICE)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("OmniBand")
            .setContentText(contentText)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, "Find Device",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, DeviceService::class.java).apply { action = ACTION_FIND_DEVICE },
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, "Disconnect",
                PendingIntent.getService(
                    this, 2,
                    Intent(this, DeviceService::class.java).apply { action = ACTION_DISCONNECT },
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
}

// ── Permissions ─────────────────────────────────────────────────────────────

/**
 * Whether the runtime permissions required for the `connectedDevice` FGS type are
 * granted. On API 31+ that is `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; before that,
 * location is what the system checks for BLE.
 */
private fun Context.hasBlePermissions(): Boolean =
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

// ── Boot receiver ─────────────────────────────────────────────────────────────

/**
 * Restarts [DeviceService] after the device boots or the app is updated.
 * Declared in `AndroidManifest.xml` with `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` intents.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
        ) {
            // startForegroundService must be followed by startForeground within 5 s; the
            // FGS type `connectedDevice` additionally requires granted BT runtime
            // permissions. Never start the FGS when we cannot satisfy the contract.
            if (!context.hasBlePermissions()) {
                Timber.w("BootReceiver: BLE permissions not granted — not starting DeviceService")
                return
            }
            Timber.i("BootReceiver: starting DeviceService after boot/update")
            context.startForegroundService(DeviceService.startIntent(context))
        }
    }
}

// ── Bluetooth state receiver ──────────────────────────────────────────────────

/**
 * Triggers an auto-connect attempt when the user re-enables Bluetooth.
 * Declared in `AndroidManifest.xml` with `ACTION_STATE_CHANGED`.
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                if (!context.hasBlePermissions()) {
                    Timber.w("BluetoothStateReceiver: BLE permissions not granted — not starting DeviceService")
                    return
                }
                Timber.i("BluetoothStateReceiver: BT turned on — starting DeviceService")
                context.startForegroundService(DeviceService.startIntent(context))
            }
        }
    }
}
