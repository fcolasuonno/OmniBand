package nodomain.freeyourgadget.gadgetbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.DeviceEvent
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.DeviceProtocol
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.MiBand7Protocol
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.SonyWF1000XM5Protocol
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.VibratePattern
import nodomain.freeyourgadget.gadgetbridge.ble.reconnect.ReconnectionManager
import timber.log.Timber
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central BLE connection manager.
 *
 * ═══════════════════════════════════════════════════════════════════
 * BUG FIXES IN THIS REVISION
 * ═══════════════════════════════════════════════════════════════════
 *
 * Fix 2 — MTU NEVER REACHED THE PROTOCOL
 *   ✗ Was: onMtuChanged called protocol.onMtuNegotiated() but activeProtocol
 *          is null at that point — created later in onServicesDiscovered.
 *   ✓ Fix: Store negotiatedMtu locally. In onServicesDiscovered, after creating
 *          the protocol object but BEFORE calling initialize(), invoke
 *          (protocol as? MiBand7Protocol)?.onMtuNegotiated(negotiatedMtu).
 *
 * Fix 3 — DESCRIPTOR WRITE RACE
 *   ✗ Was: initialize(gatt) with no way to wait for onDescriptorWrite. Protocol
 *          used delay(300) between descriptor writes — too short on some phones,
 *          causing the second descriptor write to be silently dropped.
 *   ✓ Fix: Added _descriptorWriteChannel (Channel<Unit>, UNLIMITED capacity).
 *          onDescriptorWrite emits to it. initialize() receives a suspend lambda
 *          that calls channel.receive(), so the protocol fully serializes ops.
 *
 * Additional fixes:
 *   • discoverServices() called from onMtuChanged, not from a blind postDelayed.
 *   • onCharacteristicChanged(gatt, char) deprecated overload properly delegates
 *     so older Android versions still work.
 *   • handleGattError() now also calls refreshGattCache before closing, fixing
 *     the stale-cache variant of GATT error 133.
 * ═══════════════════════════════════════════════════════════════════
 */
@SuppressLint("MissingPermission")
@Singleton
class BleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Public state ────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    val deviceEvents: Flow<DeviceEvent> = _connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) activeProtocol?.events ?: flowOf()
        else flowOf()
    }

    // ── Internal state ───────────────────────────────────────────────

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var activeGatt:     BluetoothGatt?    = null
    private var activeProtocol: DeviceProtocol?   = null
    private var targetDevice:   BluetoothDevice?  = null
    private var targetDeviceType: DeviceType?     = null
    private var storedAuthKey:  String?           = null

    // FIX 2: stored here, applied to protocol right before initialize()
    private var negotiatedMtu: Int = 23  // conservative default (ATT minimum)

    // FIX 3: each onDescriptorWrite sends Unit into this channel;
    // the protocol's awaitDescriptorWrite lambda receives from it.
    private var descriptorWriteChannel = Channel<Unit>(Channel.UNLIMITED)

    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initializationJob: Job? = null

    private val reconnectionManager = ReconnectionManager(
        context = context,
        onReconnectAttempt = { attempt ->
            _connectionState.value = ConnectionState.Reconnecting(
                address    = targetDevice?.address ?: "",
                deviceType = targetDeviceType ?: DeviceType.XIAOMI_SMART_BAND_7,
                attempt    = attempt
            )
            targetDevice?.let { connectToDevice(it) } ?: false
        },
        onMaxAttemptsReached = {
            _connectionState.value = ConnectionState.Disconnected
        }
    )

    // ── Scanning ────────────────────────────────────────────────────

    fun startScan() {
        if (!hasPermissions()) { Timber.e("BleManager: missing BLE permissions"); return }
        if (isScanning) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // We don't use strict name filters here because many devices include 
        // a suffix (e.g. "Xiaomi Smart Band 7 ABCD") that would fail an exact match.
        // We filter manually in the callback instead.
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        isScanning = true
        _scannedDevices.value = emptyList()
        Timber.i("BleManager: scan started")
        mainHandler.postDelayed({ stopScan() }, 30_000)
    }

    fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Timber.i("BleManager: scan stopped, found ${_scannedDevices.value.size} device(s)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val deviceType = DeviceType.fromDeviceName(name)
            val entry = ScannedDevice(name, result.device.address, result.rssi, deviceType)
            val list  = _scannedDevices.value.toMutableList()
            val idx   = list.indexOfFirst { it.address == entry.address }
            if (idx >= 0) list[idx] = entry else list.add(entry)
            _scannedDevices.value = list
        }
        override fun onScanFailed(errorCode: Int) {
            Timber.e("BleManager: scan failed (error $errorCode)")
            isScanning = false
        }
    }

    // ── Connection ──────────────────────────────────────────────────

    fun connect(address: String, deviceType: DeviceType, authKey: String? = null) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            Timber.e("BleManager: cannot get remote device $address"); return
        }
        targetDevice     = device
        targetDeviceType = deviceType
        storedAuthKey    = authKey

        reconnectionManager.stop()
        managerScope.launch {
            _connectionState.value = ConnectionState.Connecting(address, deviceType)
            connectToDevice(device)
        }
    }

    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        closeGatt()
        delay(600)  // Let the BT stack settle — reduces GATT error 133

        Timber.i("BleManager: connecting to ${device.address} (${targetDeviceType?.displayName})")

        // autoConnect=false → faster initial connection
        @Suppress("DEPRECATION")
        activeGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK
        )
        return activeGatt != null
    }

    fun disconnect() {
        reconnectionManager.stop()
        closeGatt()
        activeProtocol?.destroy()
        activeProtocol   = null
        targetDevice     = null
        targetDeviceType = null
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("BleManager: disconnected by user")
    }

    private fun closeGatt() {
        activeGatt?.let { gatt ->
            gatt.disconnect()
            refreshGattCache(gatt)   // clears stale cache → prevents error 133 on re-connect
            gatt.close()
            activeGatt = null
        }
    }

    // ── GATT cache refresh (fixes error 133 on reconnect) ──────────

    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = try {
        val refresh: Method = gatt.javaClass.getMethod("refresh")
        val result = (refresh.invoke(gatt) as? Boolean) ?: false
        Timber.d("BleManager: GATT cache refresh = $result")
        result
    } catch (e: Exception) {
        Timber.w(e, "BleManager: GATT cache refresh unavailable")
        false
    }

    // ── GATT callback ────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address    = gatt.device.address
            val deviceType = targetDeviceType ?: return

            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("BleManager: GATT connected to $address — requesting MTU…")
                    _connectionState.value = ConnectionState.Initializing(address, deviceType)
                    reconnectionManager.stop()

                    // Fresh channel for this connection to avoid stale signals from previous attempts
                    descriptorWriteChannel = Channel(Channel.UNLIMITED)

                    // FIX 2: request MTU first; service discovery is triggered in onMtuChanged
                    // so the protocol gets the negotiated MTU before initialize() is called.
                    if (!gatt.requestMtu(512)) {
                        Timber.w("BleManager: requestMtu returned false, proceeding to discovery")
                        mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.w("BleManager: GATT disconnected from $address (status=$status)")
                    handleDisconnect(address, deviceType)
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    Timber.e("BleManager: GATT error $status on $address")
                    handleGattError(address, deviceType, status)
                }
            }
        }

        /**
         * FIX 2: Service discovery now starts HERE, after MTU is negotiated
         * and stored, NOT from a fire-and-forget postDelayed in onConnectionStateChange.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Timber.d("BleManager: MTU negotiated to $mtu bytes")
            } else {
                Timber.w("BleManager: MTU negotiation failed (status=$status), using default $negotiatedMtu")
            }
            // Always proceed to service discovery regardless of MTU result
            // Increased delay to 600ms to allow some BT stacks to settle after MTU change
            mainHandler.postDelayed({ gatt.discoverServices() }, 600)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("BleManager: service discovery failed (status=$status)")
                handleGattError(gatt.device.address, targetDeviceType ?: return, status)
                return
            }
            Timber.i("BleManager: ${gatt.services.size} services discovered on ${gatt.device.address}")

            // Boost connection priority for faster auth & data sync
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            initializationJob?.cancel()
            initializationJob = managerScope.launch {
                // Create protocol
                val protocol = createProtocol(targetDeviceType!!)
                activeProtocol?.destroy()
                activeProtocol = protocol

                // FIX 2: set negotiated MTU BEFORE calling initialize()
                if (protocol is MiBand7Protocol) {
                    protocol.onMtuNegotiated(negotiatedMtu)
                    Timber.d("BleManager: set MTU=$negotiatedMtu on MiBand7Protocol ✓")
                }

                // FIX 3: pass the awaitDescriptorWrite lambda backed by the Channel
                val success = protocol.initialize(gatt) {
                    descriptorWriteChannel.receive()
                }

                if (success) {
                    _connectionState.value = ConnectionState.Connected(
                        gatt.device.address,
                        targetDeviceType!!
                    )
                    Timber.i("BleManager: device fully initialized ✓")
                    // Drop connection priority after auth to save power
                    launch {
                        delay(2_000)
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    }
                } else {
                    Timber.e("BleManager: protocol initialization failed")
                    _connectionState.value = ConnectionState.Error("Protocol init failed")
                    closeGatt()
                }
            }
        }

        /**
         * FIX 3: emit to _descriptorWriteChannel so the protocol's
         * awaitDescriptorWrite() lambda can unblock and continue.
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleManager: descriptor write FAILED uuid=${descriptor.uuid} status=$status")
            } else {
                Timber.v("BleManager: descriptor write OK uuid=${descriptor.uuid}")
            }
            // Always signal — the protocol needs to unblock even on failure
            // so it can log the error rather than hanging forever.
            descriptorWriteChannel.trySend(Unit)
        }

        /** API 33+: new overload with value parameter */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, value)
        }

        /** API < 33: deprecated overload — must still be implemented for older devices */
        @Deprecated("Deprecated in Android 13 but required for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleManager: characteristic write FAILED uuid=${characteristic.uuid} status=$status")
            }
        }
    }

    // ── Disconnect / error handling ──────────────────────────────────

    private fun handleDisconnect(address: String, deviceType: DeviceType) {
        initializationJob?.cancel()
        initializationJob = null
        activeProtocol?.destroy()
        activeProtocol = null

        if (targetDevice != null) {
            _connectionState.value = ConnectionState.Reconnecting(address, deviceType, 0)
            reconnectionManager.start(managerScope)
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun handleGattError(address: String, deviceType: DeviceType, status: Int) {
        Timber.e("BleManager: GATT error $status on $address — refreshing cache & reconnecting")
        initializationJob?.cancel()
        initializationJob = null
        activeGatt?.let { gatt ->
            refreshGattCache(gatt)  // flush stale cache that causes error 133
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

    // ── Protocol factory ─────────────────────────────────────────────

    private fun createProtocol(deviceType: DeviceType): DeviceProtocol = when (deviceType) {
        DeviceType.XIAOMI_SMART_BAND_7 -> {
            val key = storedAuthKey?.let { hexToBytes(it) } ?: ByteArray(16)
            MiBand7Protocol(key)
        }
        DeviceType.SONY_WF1000XM5 -> SonyWF1000XM5Protocol()
    }

    // ── Command forwarding ───────────────────────────────────────────

    suspend fun vibrate(pattern: VibratePattern = VibratePattern.SHORT) {
        activeGatt?.let { activeProtocol?.vibrate(it, pattern) }
    }

    suspend fun syncTime() {
        activeGatt?.let { activeProtocol?.syncTime(it) }
    }

    suspend fun requestBattery() {
        activeGatt?.let { activeProtocol?.requestBattery(it) }
    }

    suspend fun setHeartRateMonitoring(enabled: Boolean) {
        activeGatt?.let { activeProtocol?.setHeartRateMonitoring(it, enabled) }
    }

    suspend fun setRawSensorEnabled(enabled: Boolean) {
        activeGatt?.let { activeProtocol?.setRawSensorEnabled(it, enabled) }
    }

    suspend fun onSleepTrackingStarted() {
        activeGatt?.let { activeProtocol?.onSleepTrackingStarted(it) }
    }

    suspend fun onSleepTrackingStopped() {
        activeGatt?.let { activeProtocol?.onSleepTrackingStopped(it) }
    }

    suspend fun triggerAlarm() {
        activeGatt?.let { activeProtocol?.triggerAlarm(it) }
    }

    suspend fun dismissAlarm() {
        activeGatt?.let { activeProtocol?.dismissAlarm(it) }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("-", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun destroy() {
        reconnectionManager.stop()
        initializationJob?.cancel()
        initializationJob = null
        closeGatt()
        activeProtocol?.destroy()
        descriptorWriteChannel.close()
        managerScope.cancel()
    }
}
