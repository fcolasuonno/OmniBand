package com.omniband.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniband.ble.BleManager
import com.omniband.ble.ConnectionState
import com.omniband.ble.DeviceType
import com.omniband.ble.ScannedDevice
import com.omniband.ble.isConnected
import com.omniband.ble.protocol.ANCMode
import com.omniband.ble.protocol.DeviceEvent
import com.omniband.data.db.entity.*
import com.omniband.data.repository.DeviceRepository
import com.omniband.data.repository.HealthRepository
import com.omniband.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val _lastSpO2 = MutableStateFlow<Int?>(null)
    val lastSpO2: StateFlow<Int?> = _lastSpO2.asStateFlow()

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
                    is DeviceEvent.SpO2      -> _lastSpO2.value = event.percent
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
    private val prefs: UserPreferencesRepository
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
    private val prefs: UserPreferencesRepository
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

    fun removeDevice(address: String) {
        viewModelScope.launch(Dispatchers.IO) { deviceRepository.removeDevice(address) }
    }

    fun saveAuthKey(key: String) {
        viewModelScope.launch { prefs.saveAuthKey(key) }
    }
}
