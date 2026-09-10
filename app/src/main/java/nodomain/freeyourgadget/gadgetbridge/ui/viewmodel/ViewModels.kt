package nodomain.freeyourgadget.gadgetbridge.ui.viewmodel

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import nodomain.freeyourgadget.gadgetbridge.ble.ConnectionState
import nodomain.freeyourgadget.gadgetbridge.ble.ScannedDevice
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.ANCMode
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.DeviceEvent
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.BatteryEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.DeviceEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.HeartRateEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.NotificationLogEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SleepSessionEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.SpO2Entity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StepsEntity
import nodomain.freeyourgadget.gadgetbridge.data.db.entity.StressEntity
import nodomain.freeyourgadget.gadgetbridge.data.repository.DeviceRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.HealthRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.NotificationLogRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.UserPreferencesRepository
import nodomain.freeyourgadget.gadgetbridge.service.DeviceService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// =====================================================================
// Dashboard ViewModel
// =====================================================================

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val healthRepository: HealthRepository,
    private val deviceRepository: DeviceRepository,
    private val prefs: UserPreferencesRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val activeDevice: Flow<DeviceEntity?> = deviceRepository.allDevices.map { it.firstOrNull { d -> d.isActive } }

    // Live data from the current connection
    private val _lastHeartRate = MutableStateFlow<Int?>(null)
    val lastHeartRate: StateFlow<Int?> = _lastHeartRate.asStateFlow()

    private val _todaySteps = MutableStateFlow<StepsEntity?>(null)
    val todaySteps: StateFlow<StepsEntity?> = _todaySteps.asStateFlow()

    private val _battery = MutableStateFlow<BatteryEntity?>(null)
    val battery: StateFlow<BatteryEntity?> = _battery.asStateFlow()

    // SpO2/stress have no live stream on this band — DB-backed so both live saves
    // and history imports update the cards.
    val lastSpO2: StateFlow<Int?> = prefs.activeDeviceAddress
        .filterNotNull()
        .flatMapLatest { address -> healthRepository.getLatestSpO2(address) }
        .map { it?.percent }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val lastStress: StateFlow<StressEntity?> = prefs.activeDeviceAddress
        .filterNotNull()
        .flatMapLatest { address -> healthRepository.getRecentStress(address) }
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _ancMode = MutableStateFlow(ANCMode.OFF)
    val ancMode: StateFlow<ANCMode> = _ancMode.asStateFlow()

    val stepGoal: Flow<Int> = prefs.dailyStepGoal

    // Heart rate chart data (last hour)
    val recentHeartRates: Flow<List<HeartRateEntity>> = prefs.activeDeviceAddress
        .filterNotNull()
        .flatMapLatest { address -> healthRepository.getRecentHeartRate(address) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        observeDeviceEvents()
        loadCurrentData()
    }

    private fun observeDeviceEvents() {
        viewModelScope.launch {
            bleManager.deviceEvents.collect { event ->
                when (event) {
                    is DeviceEvent.HeartRate -> _lastHeartRate.value = event.bpm
                    is DeviceEvent.Steps     -> {
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        _todaySteps.value = StepsEntity(
                            deviceAddress = "",
                            count = event.count,
                            calories = event.calories,
                            distanceMeters = event.distance,
                            date = today
                        )
                    }
                    is DeviceEvent.Battery   -> _battery.value = BatteryEntity(
                        deviceAddress = "",
                        percent = event.percent,
                        charging = event.charging
                    )
                    is DeviceEvent.AncMode   -> _ancMode.value = event.mode
                    else -> Unit
                }
            }
        }
    }

    private fun loadCurrentData() {
        viewModelScope.launch(Dispatchers.IO) {
            val address = prefs.activeDeviceAddress.first() ?: return@launch
            _battery.value = healthRepository.getLatestBattery(address)
        }
    }

    fun vibrate() {
        viewModelScope.launch { bleManager.vibrate() }
    }

    fun syncTime() {
        viewModelScope.launch { bleManager.syncTime() }
    }

    fun disconnect() = bleManager.disconnect()
}

// =====================================================================
// Scan ViewModel
// =====================================================================

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val deviceRepository: DeviceRepository,
    private val prefs: UserPreferencesRepository,
    private val app: Application
) : ViewModel() {

    val scannedDevices: StateFlow<List<ScannedDevice>> = bleManager.scannedDevices
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    fun startScan() = bleManager.startScan()
    fun stopScan()  = bleManager.stopScan()

    fun connectToDevice(device: ScannedDevice, authKey: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val type = device.deviceType ?: return@launch
            deviceRepository.addDevice(device.address, device.name ?: "Unknown Device", type, authKey)
            deviceRepository.setActiveDevice(device.address)
            prefs.saveActiveDevice(device.address, type.name)
            if (authKey != null) prefs.saveAuthKey(authKey)

            // Bring up the foreground service (permissions were already granted on the scan
            // screen) so the connection survives process death and backgrounding.
            app.startForegroundService(DeviceService.startIntent(app))
            bleManager.connect(device.address, type, authKey)
        }
    }
}

// =====================================================================
// Health Screen ViewModel
// =====================================================================

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
    private val prefs: UserPreferencesRepository,
    private val bleManager: BleManager
) : ViewModel() {

    private val activeAddress: Flow<String?> = prefs.activeDeviceAddress

    val recentHeartRates: StateFlow<List<HeartRateEntity>> = activeAddress
        .filterNotNull()
        .flatMapLatest { healthRepository.getRecentHeartRate(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val last30DaysSteps: StateFlow<List<StepsEntity>> = activeAddress
        .filterNotNull()
        .flatMapLatest { healthRepository.getLast30DaysSteps(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentSpO2: StateFlow<List<SpO2Entity>> = activeAddress
        .filterNotNull()
        .flatMapLatest { healthRepository.getRecentSpO2(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val sleepSessions: StateFlow<List<SleepSessionEntity>> = activeAddress
        .filterNotNull()
        .flatMapLatest { healthRepository.getRecentSleepSessions(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _sleepSyncing = MutableStateFlow(false)
    val sleepSyncing: StateFlow<Boolean> = _sleepSyncing.asStateFlow()

    /** Manually pull recorded history (sleep, SpO2, stress) from the band. */
    fun syncHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_sleepSyncing.value) return@launch
            _sleepSyncing.value = true
            try {
                val address = prefs.activeDeviceAddress.first() ?: return@launch
                val now = System.currentTimeMillis()
                val last = prefs.lastHistoryFetchMs.first()
                val since = maxOf(if (last > 0) last else 0L, now - 7L * 24 * 3600 * 1000)
                bleManager.withHistorySyncLock {
                    bleManager.fetchSleepHistory(since).forEach { s ->
                        healthRepository.importSleepSession(address, s.startMs, s.endMs, s.stages)
                    }
                    bleManager.fetchSpo2History(since).forEach { s ->
                        healthRepository.importSpO2At(address, s.percent, s.timestampMs)
                    }
                    bleManager.fetchStressHistory(since).forEach { s ->
                        healthRepository.importStressAt(address, s.score, s.timestampMs)
                    }
                }
                prefs.setLastHistoryFetchMs(now)
            } catch (e: Exception) {
                android.util.Log.w("HealthViewModel", "history sync failed", e)
            } finally {
                _sleepSyncing.value = false
            }
        }
    }
}

// =====================================================================
// Settings ViewModel
// =====================================================================

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val deviceRepository: DeviceRepository,
    private val bleManager: BleManager
) : ViewModel() {

    val autoReconnect: StateFlow<Boolean> = prefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sleepAsAndroidEnabled: StateFlow<Boolean> = prefs.sleepAsAndroidEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dailyStepGoal: StateFlow<Int> = prefs.dailyStepGoal
        .stateIn(viewModelScope, SharingStarted.Eagerly, 8000)

    val disableIdleAlert: StateFlow<Boolean> = prefs.disableIdleAlert
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val savedDevices: StateFlow<List<DeviceEntity>> = deviceRepository.allDevices
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoReconnect(enabled) }
    }

    fun setSleepAsAndroid(enabled: Boolean) {
        viewModelScope.launch { prefs.setSleepAsAndroidEnabled(enabled) }
    }

    fun setStepGoal(steps: Int) {
        viewModelScope.launch { prefs.setDailyStepGoal(steps) }
    }

    fun setDisabledIdleAlert(disabled: Boolean) {
        viewModelScope.launch { prefs.setDisableIdleAlert(disabled) }
    }

    fun removeDevice(address: String) {
        viewModelScope.launch(Dispatchers.IO) { deviceRepository.removeDevice(address) }
    }

    fun saveAuthKey(key: String) {
        viewModelScope.launch { prefs.saveAuthKey(key) }
    }
}

// =====================================================================
// Notifications ViewModel (mirroring app picker)
// =====================================================================

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val app: Application,
    private val prefs: UserPreferencesRepository,
    private val logRepo: NotificationLogRepository
) : ViewModel() {

    data class NotifApp(
        val packageName: String,
        val label: String,
        val icon: androidx.compose.ui.graphics.ImageBitmap?,
        val pinned: Boolean
    )

    val masterEnabled: StateFlow<Boolean> = prefs.notifMirrorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val enabledApps: StateFlow<Set<String>> = prefs.enabledNotifApps
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _apps = MutableStateFlow<List<NotifApp>>(emptyList())
    val apps: StateFlow<List<NotifApp>> = _apps.asStateFlow()

    private val _listenerEnabled = MutableStateFlow(false)
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { loadApps() }
        refreshListenerState()
    }

    private fun loadApps() {
        val pm = app.packageManager
        val pinned = UserPreferencesRepository.PINNED_NOTIF_APPS
        val own = app.packageName
        val seen = linkedSetOf<String>()
        val entries =
            mutableListOf<Triple<String, String, androidx.compose.ui.graphics.ImageBitmap?>>()
        for (ri in pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
        )) {
            val pkg = ri.activityInfo.packageName
            if (pkg == own || !seen.add(pkg)) continue
            val label = ri.loadLabel(pm).toString().ifBlank { pkg }
            val icon = try {
                ri.loadIcon(pm)?.toBitmap()?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
            entries.add(Triple(pkg, label, icon))
        }
        _apps.value = entries
            .sortedWith(
                compareBy(
                    { e -> pinned.indexOf(e.first).let { if (it == -1) Int.MAX_VALUE else it } },
                    { e -> e.second.lowercase() }
                )
            )
            .map { (pkg, label, icon) -> NotifApp(pkg, label, icon, pkg in pinned) }
    }

    fun refreshListenerState() {
        val cn = android.content.ComponentName(
            app, nodomain.freeyourgadget.gadgetbridge.service.NotificationCatcherService::class.java
        )
        val flat = android.provider.Settings.Secure.getString(
            app.contentResolver, "enabled_notification_listeners"
        )
        _listenerEnabled.value = flat?.split(":")?.any {
            android.content.ComponentName.unflattenFromString(it) == cn
        } == true
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotifMirrorEnabled(enabled) }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setAppNotifEnabled(packageName, enabled) }
    }

    val history: StateFlow<List<NotificationLogEntity>> = logRepo.recent()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val blocklist: StateFlow<Set<String>> = prefs.notifBlocklist
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun addBlocklistEntry(entry: String) {
        viewModelScope.launch { prefs.addNotifBlocklistEntry(entry) }
    }

    fun removeBlocklistEntry(entry: String) {
        viewModelScope.launch { prefs.removeNotifBlocklistEntry(entry) }
    }

    fun clearHistory() {
        viewModelScope.launch { logRepo.clearAll() }
    }

    val quietHoursEnabled: StateFlow<Boolean> = prefs.quietHoursEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val quietHoursStartMin: StateFlow<Int> = prefs.quietHoursStartMin
        .stateIn(viewModelScope, SharingStarted.Eagerly, 23 * 60 + 30)

    val quietHoursEndMin: StateFlow<Int> = prefs.quietHoursEndMin
        .stateIn(viewModelScope, SharingStarted.Eagerly, 7 * 60)

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setQuietHoursEnabled(enabled) }
    }

    fun setQuietHours(startMin: Int, endMin: Int) {
        viewModelScope.launch { prefs.setQuietHours(startMin, endMin) }
    }
}
