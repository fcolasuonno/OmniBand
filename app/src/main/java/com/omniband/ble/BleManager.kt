package com.omniband.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.omniband.ble.protocol.DeviceEvent
import com.omniband.ble.protocol.DeviceProtocol
import com.omniband.ble.protocol.MiBand7Protocol
import com.omniband.ble.protocol.SonyWF1000XM5Protocol
import com.omniband.ble.reconnect.ReconnectionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.reflect.Method
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import android.annotation.SuppressLint

/**
 * Central BLE connection manager.
 *
 * Responsibilities:
 *  - Scan for supported devices
 *  - Establish and maintain GATT connections
 *  - Delegate protocol handling to [DeviceProtocol] implementations
 *  - Drive [ReconnectionManager] on disconnect
 *  - Expose [ConnectionState] and [DeviceEvent] streams via Flow
 *
 * Connection stability techniques:
 *  - Always connect with autoConnect=false initially (faster first connect)
 *  - After first successful connect, bond with the device so Android's
 *    Bluetooth stack can auto-reconnect without scanning
 *  - On GATT error 133: close gatt → clear cache → wait → reopen
 *  - Use the main thread's Looper for GATT callbacks (Android requirement)
 *  - Request high connection priority (low latency) during data sync
 *  - Drop to balanced priority after sync to save power
 */
@Singleton
class BleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    /** All device events from whichever device is currently connected */
    val deviceEvents: Flow<DeviceEvent> = _connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) {
            activeProtocol?.events ?: flowOf()
        } else {
            flowOf()
        }
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var activeGatt: BluetoothGatt? = null
    private var activeProtocol: DeviceProtocol? = null
    private var targetDevice: BluetoothDevice? = null
    private var targetDeviceType: DeviceType? = null
    private var authKey: String? = null

    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reconnectionManager = ReconnectionManager(
        context = context,
        onReconnectAttempt = { attempt ->
            _connectionState.value = ConnectionState.Reconnecting(
                address = targetDevice?.address ?: "",
                deviceType = targetDeviceType ?: DeviceType.XIAOMI_SMART_BAND_7,
                attempt = attempt
            )
            targetDevice?.let { connectToDevice(it) } ?: false
        }
    ) {
        _connectionState.value = ConnectionState.Disconnected
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    fun startScan() {
        if (!hasPermissions()) {
            Timber.e("BleManager: missing BLE permissions")
            return
        }
        if (isScanning) return

        val filters = buildScanFilters()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        isScanning = true
        _scannedDevices.value = emptyList()
        Timber.i("BleManager: scan started")

        // Auto-stop after 30 seconds
        mainHandler.postDelayed({ stopScan() }, 30_000)
    }

    fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Timber.i("BleManager: scan stopped, found ${_scannedDevices.value.size} devices")
    }

    private fun buildScanFilters(): List<ScanFilter> {
        return DeviceType.entries.mapNotNull { deviceType ->
            deviceType.serviceUuid?.let { uuid ->
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(uuid))
                    .build()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (hasPermissions()) device.name ?: result.scanRecord?.deviceName else result.scanRecord?.deviceName
            
            // Try matching by name first, then by service UUIDs in the scan record
            var deviceType = DeviceType.fromDeviceName(name)
            
            if (deviceType == null) {
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }
                deviceType = DeviceType.entries.find { type ->
                    type.serviceUuid?.lowercase() in (serviceUuids ?: emptyList())
                }
            }

            val scanned = ScannedDevice(
                name = name ?: "Unknown Device",
                address = device.address,
                rssi = result.rssi,
                deviceType = deviceType
            )
            
            val current = _scannedDevices.value.toMutableList()
            val existing = current.indexOfFirst { it.address == scanned.address }
            if (existing >= 0) {
                // Update existing with better info if available
                if ((current[existing].deviceType == null) && (scanned.deviceType != null)) {
                    current[existing] = scanned
                } else {
                    // Just update RSSI
                    current[existing] = current[existing].copy(rssi = scanned.rssi)
                }
            } else {
                current.add(scanned)
            }
            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BleManager: scan failed with error $errorCode")
            isScanning = false
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    fun connect(address: String, deviceType: DeviceType, authKey: String? = null) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            Timber.e("BleManager: cannot get remote device for $address")
            return
        }
        this.targetDevice = device
        this.targetDeviceType = deviceType
        this.authKey = authKey

        reconnectionManager.stop()
        managerScope.launch {
            _connectionState.value = ConnectionState.Connecting(address, deviceType)
            connectToDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        // Close any existing connection first
        closeGatt()
        delay(600)  // Brief pause helps avoid GATT error 133

        Timber.i("BleManager: connecting to ${device.address}")
        activeGatt = device.connectGatt(
            context,
            false,  // autoConnect=false for faster initial connect
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
        return activeGatt != null
    }

    fun disconnect() {
        reconnectionManager.stop()
        closeGatt()
        activeProtocol?.destroy()
        activeProtocol = null
        targetDevice = null
        targetDeviceType = null
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("BleManager: disconnected")
    }

    private fun closeGatt() {
        activeGatt?.let { gatt ->
            gatt.disconnect()
            // Refresh GATT cache before closing — prevents stale service discovery on reconnect
            refreshGattCache(gatt)
            gatt.close()
            activeGatt = null
        }
    }

    /**
     * Clears Android's GATT cache via reflection.
     * This is the fix for the dreaded GATT error 133 (GATT_ERROR / GATT_INTERNAL_ERROR).
     *
     * The root cause: Android caches GATT services. After a disconnect, the cache
     * can become stale. On the next connect, service discovery fails internally
     * and Android bubbles it up as error 133. Calling refresh() forces Android
     * to re-run service discovery fresh from the device.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val refresh: Method = gatt.javaClass.getMethod("refresh")
            val result = (refresh.invoke(gatt) as? Boolean) ?: false
            Timber.d("BleManager: GATT cache refresh = $result")
            result
        } catch (e: Exception) {
            Timber.e(e, "BleManager: failed to refresh GATT cache")
            false
        }
    }

    // -------------------------------------------------------------------------
    // GATT callback
    // -------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            val deviceType = targetDeviceType ?: return

            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("BleManager: GATT connected to $address, discovering services…")
                    _connectionState.value = ConnectionState.Initializing(address, deviceType)
                    reconnectionManager.stop()
                    
                    // Request high MTU for faster data transfer
                    gatt.requestMtu(512)
                    
                    // On some devices, discovering services immediately after connection 
                    // fails because the stack is still internalizing the connection.
                    // We wait 1200ms to ensure stability.
                    mainHandler.postDelayed(
                        {
                            Timber.d("BleManager: Triggering service discovery...")
                            if (activeGatt?.discoverServices() == false) {
                                Timber.e("BleManager: Failed to start service discovery")
                            }
                        },
                        1200,
                    )
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    handleDisconnection(address, deviceType, status)
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    // GATT error 133 most commonly — close and trigger reconnect
                    Timber.e("BleManager: GATT error $status on $address")
                    handleGattError(address, deviceType, status)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.d("BleManager: MTU changed to $mtu (status=$status)")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("BleManager: service discovery failed with status $status")
                handleGattError(gatt.device.address, targetDeviceType!!, status)
                return
            }

            Timber.i("BleManager: services discovered on ${gatt.device.address}")
            // Request connection priority BEFORE initializing protocol
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            managerScope.launch {
                val protocol = createProtocol(targetDeviceType!!)
                activeProtocol?.destroy()
                activeProtocol = protocol

                val success = protocol.initialize(gatt)
                if (success) {
                    _connectionState.value = ConnectionState.Connected(
                        gatt.device.address,
                        targetDeviceType!!
                    )
                    Timber.i("BleManager: device fully initialized ✓")
                    // Drop to balanced after init to save power
                    delay(2000)
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } else {
                    Timber.e("BleManager: protocol initialization failed")
                    _connectionState.value = ConnectionState.Error("Protocol init failed")
                    closeGatt()
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in Java but still called on older Android versions")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleManager: descriptor write failed uuid=${descriptor.uuid} status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleManager: characteristic write failed uuid=${characteristic.uuid} status=$status")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disconnection / error handling
    // -------------------------------------------------------------------------

    private fun handleDisconnection(address: String, deviceType: DeviceType, status: Int) {
        Timber.w("BleManager: disconnected from $address (status=$status)")
        activeProtocol?.destroy()
        activeProtocol = null

        // Only trigger reconnect if we have a target device
        if (targetDevice != null) {
            _connectionState.value = ConnectionState.Reconnecting(address, deviceType, 0)
            reconnectionManager.start(managerScope)
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun handleGattError(address: String, deviceType: DeviceType, status: Int) {
        Timber.e("BleManager: GATT error $status — closing GATT and triggering reconnect")
        activeGatt?.let { gatt ->
            refreshGattCache(gatt)  // Clear stale cache (fixes error 133)
            gatt.close()
            activeGatt = null
        }
        activeProtocol?.destroy()
        activeProtocol = null

        if (targetDevice != null) {
            _connectionState.value = ConnectionState.Reconnecting(address, deviceType, 0)
            reconnectionManager.start(managerScope)
        } else {
            _connectionState.value = ConnectionState.Error("GATT error $status")
        }
    }

    // -------------------------------------------------------------------------
    // Protocol factory
    // -------------------------------------------------------------------------

    private fun createProtocol(deviceType: DeviceType): DeviceProtocol {
        return when (deviceType) {
            DeviceType.XIAOMI_SMART_BAND_7 -> {
                val key = authKey?.let { hexStringToByteArray(it) } ?: ByteArray(16)
                MiBand7Protocol(key)
            }
            DeviceType.SONY_WF1000XM5 -> SonyWF1000XM5Protocol()
        }
    }

    // -------------------------------------------------------------------------
    // Forwarding commands to active protocol
    // -------------------------------------------------------------------------

    suspend fun vibrate(pattern: com.omniband.ble.protocol.VibratePattern =
                            com.omniband.ble.protocol.VibratePattern.SHORT) {
        activeGatt?.let { gatt ->
            activeProtocol?.vibrate(gatt, pattern)
        }
    }

    suspend fun syncTime() {
        activeGatt?.let { gatt -> activeProtocol?.syncTime(gatt) }
    }

    suspend fun onSleepTrackingStarted() {
        activeGatt?.let { gatt -> activeProtocol?.onSleepTrackingStarted(gatt) }
    }

    suspend fun onSleepTrackingStopped() {
        activeGatt?.let { gatt -> activeProtocol?.onSleepTrackingStopped(gatt) }
    }

    suspend fun triggerAlarm() {
        activeGatt?.let { gatt -> activeProtocol?.triggerAlarm(gatt) }
    }

    suspend fun dismissAlarm() {
        activeGatt?.let { gatt -> activeProtocol?.dismissAlarm(gatt) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun hasPermissions(): Boolean {
        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("-", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun destroy() {
        reconnectionManager.stop()
        closeGatt()
        activeProtocol?.destroy()
        managerScope.cancel()
    }
}
