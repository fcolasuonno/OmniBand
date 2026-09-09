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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Central BLE connection manager (singleton).
 *
 * ## Responsibilities
 * - BLE device scanning
 * - GATT connection lifecycle (connect → MTU negotiation → service discovery → protocol init)
 * - Delegating incoming notifications to the active [DeviceProtocol]
 * - Exposing a [deviceEvents] flow of typed [DeviceEvent]s to the rest of the app
 * - Automatic reconnection via [ReconnectionManager]
 *
 * ## GATT serialisation
 * Android allows only **one outstanding GATT operation** at a time. This manager uses two
 * `Channel`-backed suspend functions (`awaitDescriptorWrite`, `awaitCharacteristicWrite`) that
 * are handed to the protocol's [DeviceProtocol.initialize] method.  The protocol calls these
 * after every write to block until the corresponding `onDescriptorWrite` / `onCharacteristicWrite`
 * callback fires.
 *
 * Both channels are recreated on every new GATT connection and drained after a disconnect to
 * prevent stale completions from leaking into the next initialisation.
 *
 * ## GATT error 133
 * The infamous "GATT error 133" is addressed at three points:
 * 1. `gatt.refresh()` (via reflection) is called before `gatt.close()` to clear the Android
 *    GATT cache, which can become stale after an unexpected disconnect.
 * 2. A 600 ms cooldown between `disconnect()` and the next `connectGatt()` lets the BT stack
 *    settle.
 * 3. The first connection uses `autoConnect=false` (faster); subsequent reconnections rely on
 *    [ReconnectionManager]'s backoff loop rather than `autoConnect=true`.
 */
@SuppressLint("MissingPermission")
@Singleton
class BleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    /**
     * Emits [DeviceEvent]s from the currently connected device.
     * Produces no items when disconnected.
     */
    val deviceEvents: Flow<DeviceEvent> = _connectionState.flatMapLatest { state ->
        if (state is ConnectionState.Connected) activeProtocol?.events ?: flowOf()
        else flowOf()
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var activeGatt: BluetoothGatt? = null
    private var activeProtocol: DeviceProtocol? = null
    private var targetDevice: BluetoothDevice? = null
    private var targetDeviceType: DeviceType? = null
    private var storedAuthKey: String? = null
    private var negotiatedMtu: Int = 23
    private var initializationJob: Job? = null
    private var isScanning = false

    // Guards against duplicate GATT connections (reconnection loop vs explicit connect
    // racing; Android BT stack can fire callback pairs for the same event).
    private val connectMutex = Mutex()
    @Volatile
    private var connectGeneration = 0
    private val initLock = Any()
    @Volatile
    private var initializingGatt: BluetoothGatt? = null

    /**
     * One-shot channels that carry GATT operation completions from the callback thread to the
     * initialisation coroutine. Recreated on every new connection so stale signals from a
     * previous session cannot accidentally unblock a new handshake.
     */
    private var descriptorWriteChannel = Channel<Unit>(Channel.UNLIMITED)
    private var characteristicWriteChannel = Channel<Unit>(Channel.UNLIMITED)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val reconnectionManager = ReconnectionManager(
        isBluetoothEnabled = { bluetoothAdapter?.isEnabled == true },
        onReconnectAttempt = { attempt ->
            _connectionState.value = ConnectionState.Reconnecting(
                address    = targetDevice?.address ?: "",
                deviceType = targetDeviceType ?: DeviceType.XIAOMI_SMART_BAND_7,
                attempt = attempt,
            )
            targetDevice?.let { managerScope.launch { connectToDevice(it) } }
            true  // connectGatt is asynchronous; result arrives in onConnectionStateChange
        },
        onMaxAttemptsReached = {
            _connectionState.value = ConnectionState.Disconnected
        },
    )

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startScan() {
        if (!hasPermissions()) { Timber.e("BleManager: missing BLE permissions"); return }
        if (isScanning) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

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
        Timber.i("BleManager: scan stopped — %d device(s) found", _scannedDevices.value.size)
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
            Timber.e("BleManager: scan failed (error %d)", errorCode)
            isScanning = false
        }
    }

    // ── Connection management ─────────────────────────────────────────────────

    /**
     * Connect to a known device by MAC address.
     * Ignored if already connecting/initialising/connected to the same address.
     * The state is set **synchronously** (before the coroutine is launched) so a
     * duplicate call in the same tick is always rejected.
     */
    fun connect(address: String, deviceType: DeviceType, authKey: String? = null) {
        val busyAddress = when (val state = _connectionState.value) {
            is ConnectionState.Connecting,
            is ConnectionState.Initializing,
            is ConnectionState.Connected -> state.address

            else -> null
        }
        if (busyAddress == address) {
            Timber.d("BleManager: already connecting to %s — ignoring duplicate request", address)
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            Timber.e("BleManager: cannot resolve device %s", address); return
        }
        targetDevice     = device
        targetDeviceType = deviceType
        storedAuthKey    = authKey

        reconnectionManager.stop()
        _connectionState.value = ConnectionState.Connecting(address, deviceType)
        managerScope.launch { connectToDevice(device) }
    }

    /**
     * Opens a GATT connection to [device].
     *
     * Serialized with a mutex + generation counter so overlapping callers
     * (ReconnectionManager attempts, explicit connects) can never produce two
     * simultaneous GATT objects to the same band — the classic cause of doubled
     * protocol handshakes and GATT write failures.
     */
    private suspend fun connectToDevice(device: BluetoothDevice) {
        val generation = ++connectGeneration
        connectMutex.withLock {
            if (generation != connectGeneration) {
                Timber.d("BleManager: stale connect request discarded (superseded)")
                return
            }
            val existing = activeGatt
            if (existing?.device?.address == device.address) {
                Timber.d(
                    "BleManager: GATT to %s already active/in-progress — skipping duplicate",
                    device.address
                )
                return
            }
            closeGatt()
            delay(600)  // Let the BT stack settle (mitigates GATT error 133)

            Timber.i(
                "BleManager: connecting to %s (%s)",
                device.address,
                targetDeviceType?.displayName
            )

            // Use the modern 5-arg connectGatt on API 26+ (suppress for older API warning)
            @Suppress("DEPRECATION")
            activeGatt = device.connectGatt(
                context,
                /* autoConnect = */
                false,    // explicit connect is faster; reconnect logic is our own
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
            )
        }
    }

    /** User-initiated disconnect. Stops reconnection and clears the target device. */
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
            refreshGattCache(gatt)   // Clear stale service cache (GATT error 133 mitigation)
            gatt.close()
            activeGatt = null
        }
    }

    /**
     * Calls the hidden `BluetoothGatt.refresh()` method via reflection to clear the Android
     * GATT service cache.  Required after unexpected disconnects to prevent the "Services
     * not found" variant of GATT error 133.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = try {
        val refresh: Method = gatt.javaClass.getMethod("refresh")
        (refresh.invoke(gatt) as? Boolean) ?: false
    } catch (e: Exception) {
        Timber.w(e, "BleManager: GATT cache refresh unavailable")
        false
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address    = gatt.device.address
            val deviceType = targetDeviceType ?: return

            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    // A second GATT can connect while the first is still tracked (race between
                    // reconnect attempts). Keep exactly one: close any duplicate immediately.
                    val tracked = activeGatt
                    if (tracked != null && tracked !== gatt) {
                        Timber.w(
                            "BleManager: duplicate GATT connected for %s — closing the extra one",
                            address
                        )
                        refreshGattCache(gatt)
                        gatt.close()
                        return
                    }

                    Timber.i("BleManager: GATT connected to %s — requesting MTU…", address)
                    _connectionState.value = ConnectionState.Initializing(address, deviceType)
                    reconnectionManager.stop()

                    // Recreate channels so no stale completions from a previous session leak in
                    descriptorWriteChannel.close()
                    characteristicWriteChannel.close()
                    descriptorWriteChannel = Channel(Channel.UNLIMITED)
                    characteristicWriteChannel = Channel(Channel.UNLIMITED)

                    if (!gatt.requestMtu(512)) {
                        Timber.w("BleManager: requestMtu returned false — proceeding to service discovery")
                        mainHandler.postDelayed({ gatt.discoverServices() }, 600)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.w("BleManager: GATT disconnected from %s (status=%d)", address, status)
                    handleDisconnected(gatt, address, deviceType)
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    Timber.e("BleManager: GATT error %d on %s", status, address)
                    handleGattError(gatt, address, deviceType, status)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Timber.d("BleManager: MTU negotiated to %d bytes", mtu)
            } else {
                Timber.w(
                    "BleManager: MTU negotiation failed (status=%d) — using %d",
                    status,
                    negotiatedMtu
                )
            }
            mainHandler.postDelayed({ gatt.discoverServices() }, 600)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("BleManager: service discovery failed (status=%d)", status)
                handleGattError(gatt, gatt.device.address, targetDeviceType ?: return, status)
                return
            }

            // Ignore events from stale/duplicate GATT objects (only the tracked one may init).
            if (gatt !== activeGatt) {
                Timber.w("BleManager: services discovered on non-active GATT — ignoring")
                return
            }

            // Thread-safe guard: the BT stack can fire this callback twice (on different
            // binder threads) for a single connection — never start two initializations.
            val shouldInitialize = synchronized(initLock) {
                if (initializingGatt === gatt || initializationJob?.isActive == true) {
                    false
                } else {
                    initializingGatt = gatt
                    true
                }
            }
            if (!shouldInitialize) {
                Timber.d("BleManager: already initializing — ignoring extra onServicesDiscovered")
                return
            }

            Timber.i(
                "BleManager: %d services discovered on %s",
                gatt.services.size,
                gatt.device.address
            )
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            initializationJob = managerScope.launch {
                try {
                    val protocol = createProtocol(targetDeviceType!!)
                    activeProtocol?.destroy()
                    activeProtocol = protocol

                    if (protocol is MiBand7Protocol) {
                        protocol.onMtuNegotiated(negotiatedMtu)
                    }

                    val success = protocol.initialize(
                        gatt,
                        awaitDescriptorWrite = { descriptorWriteChannel.receive() },
                        awaitCharacteristicWrite = { characteristicWriteChannel.receive() },
                    )

                    if (success) {
                        _connectionState.value =
                            ConnectionState.Connected(gatt.device.address, targetDeviceType!!)
                        Timber.i("BleManager: device fully initialized ✓")
                        // Step back to BALANCED priority — HIGH drains battery
                        launch { delay(2_000); gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
                    } else {
                        Timber.e("BleManager: protocol initialization failed")
                        _connectionState.value = ConnectionState.Error("Protocol init failed")
                        closeGatt()
                    }
                } finally {
                    synchronized(initLock) {
                        if (initializingGatt === gatt) initializingGatt = null
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(
                    "BleManager: descriptor write FAILED uuid=%s status=%d",
                    descriptor.uuid,
                    status
                )
            } else {
                Timber.v("BleManager: descriptor write OK uuid=%s", descriptor.uuid)
            }
            descriptorWriteChannel.trySend(Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, value)
        }

        @Deprecated("Required for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            activeProtocol?.onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(
                    "BleManager: characteristic write FAILED uuid=%s status=%d",
                    characteristic.uuid,
                    status
                )
            } else {
                Timber.v("BleManager: characteristic write OK uuid=%s", characteristic.uuid)
            }
            characteristicWriteChannel.trySend(Unit)
        }
    }

    // ── Disconnect / error handling ───────────────────────────────────────────

    private fun handleDisconnected(gatt: BluetoothGatt, address: String, deviceType: DeviceType) {
        // Duplicate/stale GATT dropped: clean it up silently, do NOT restart reconnection.
        if (gatt !== activeGatt) {
            Timber.d("BleManager: stale GATT for %s dropped — closing only", address)
            refreshGattCache(gatt)
            gatt.close()
            return
        }

        initializationJob?.cancel()
        initializationJob = null
        activeProtocol?.destroy()
        activeProtocol = null
        closeGatt()

        if (targetDevice != null) {
            _connectionState.value = ConnectionState.Reconnecting(address, deviceType, 0)
            reconnectionManager.start(managerScope)
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun handleGattError(
        gatt: BluetoothGatt,
        address: String,
        deviceType: DeviceType,
        status: Int
    ) {
        Timber.e(
            "BleManager: GATT error %d on %s — refreshing cache & reconnecting",
            status,
            address
        )

        if (gatt !== activeGatt) {
            Timber.d("BleManager: GATT error on stale GATT — closing only, no reconnect")
            refreshGattCache(gatt)
            gatt.close()
            return
        }

        initializationJob?.cancel()
        initializationJob = null
        refreshGattCache(gatt)
        gatt.close()
        activeGatt = null
        activeProtocol?.destroy()
        activeProtocol = null

        if (targetDevice != null) {
            _connectionState.value = ConnectionState.Reconnecting(address, deviceType, 0)
            reconnectionManager.start(managerScope)
        } else {
            _connectionState.value = ConnectionState.Error("GATT error $status")
        }
    }

    // ── Protocol factory ──────────────────────────────────────────────────────

    private fun createProtocol(deviceType: DeviceType): DeviceProtocol = when (deviceType) {
        DeviceType.XIAOMI_SMART_BAND_7 -> {
            val key = storedAuthKey?.let { hexToBytes(it) } ?: ByteArray(16)
            MiBand7Protocol(key)
        }
        DeviceType.SONY_WF1000XM5 -> SonyWF1000XM5Protocol()
    }

    // ── Public command API ────────────────────────────────────────────────────

    suspend fun vibrate(pattern: VibratePattern = VibratePattern.SHORT) {
        activeGatt?.let { activeProtocol?.vibrate(it, pattern) }
    }

    suspend fun syncTime() {
        activeGatt?.let { activeProtocol?.syncTime(it) }
    }

    suspend fun setInactivityWarnings(enabled: Boolean) {
        activeGatt?.let { activeProtocol?.setInactivityWarnings(it, enabled) }
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

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .all {
                    ContextCompat.checkSelfPermission(
                        context,
                        it
                    ) == PackageManager.PERMISSION_GRANTED
                }
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("-", "").removePrefix("0x").removePrefix("0X")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Release all resources. Call from `onDestroy` of the hosting service. */
    fun destroy() {
        reconnectionManager.stop()
        initializationJob?.cancel()
        initializationJob = null
        closeGatt()
        activeProtocol?.destroy()
        descriptorWriteChannel.close()
        characteristicWriteChannel.close()
        managerScope.cancel()
    }
}
