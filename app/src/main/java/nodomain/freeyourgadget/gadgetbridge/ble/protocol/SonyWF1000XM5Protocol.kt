package nodomain.freeyourgadget.gadgetbridge.ble.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Protocol implementation for Sony WF-1000XM5 earbuds.
 *
 * The WF-1000XM5 exposes both Bluetooth Classic (A2DP/HFP) and BLE interfaces.
 * This protocol targets the BLE control channel which provides:
 *  - ANC mode control (Noise Cancelling / Ambient / Off)
 *  - Battery level for L/R/Case
 *  - Noise level monitoring (ambient pass-through analysis)
 *  - Wearing state detection
 *  - Audio codec control
 *
 * Sony Headphones GATT profile:
 *   Service:  75c27625-bd42-d645-0b00-a4acd5dfb3b4  (Sony main control)
 *   Char TX:  75c27625-bd42-d645-0b01-a4acd5dfb3b4  (app → headphone)
 *   Char RX:  75c27625-bd42-d645-0b02-a4acd5dfb3b4  (headphone → app, notify)
 *
 * Sony uses a proprietary serial protocol over these characteristics:
 *   [startByte(1)] [dataType(1)] [seqId(1)] [payloadLength(2)] [payload] [checksum(1)]
 *
 * Known data types:
 *   0x0C - Battery level report (L, R, Case)
 *   0x68 - ANC mode report / set
 *   0x56 - Wearing state
 *   0x49 - Codec info
 *   0x8A - Noise level (ambient sound mode analysis)
 *
 * References:
 *   - Gadgetbridge Sony Headphones support (José Rebelo's implementation)
 *   - Protocol observed via nRF Connect + Wireshark HCI snoop
 */
@SuppressLint("MissingPermission")
class SonyWF1000XM5Protocol : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<DeviceEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    companion object {
        // Sony WF-1000XM5 BLE service and characteristics
        val UUID_SERVICE_SONY: UUID = UUID.fromString("75c27625-bd42-d645-0b00-a4acd5dfb3b4")
        val UUID_CHAR_TX: UUID = UUID.fromString("75c27625-bd42-d645-0b01-a4acd5dfb3b4")
        val UUID_CHAR_RX: UUID = UUID.fromString("75c27625-bd42-d645-0b02-a4acd5dfb3b4")
        val UUID_CHAR_BATTERY: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb") // Standard battery
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Sony protocol constants
        private const val START_BYTE       = 0x3E.toByte()
        private const val DATA_TYPE_INIT   = 0x00.toByte()
        private const val DATA_TYPE_BATTERY = 0x0C.toByte()
        private const val DATA_TYPE_ANC    = 0x68.toByte()
        private const val DATA_TYPE_WEARING = 0x56.toByte()
        private const val DATA_TYPE_NOISE  = 0x8A.toByte()
        private const val DATA_TYPE_INIT_RESP = 0x01.toByte()
        private const val DATA_TYPE_BATTERY_GET = 0x10.toByte()
        private const val DATA_TYPE_ANC_GET = 0x17.toByte()

        private const val ANC_OFF   = 0x00
        private const val ANC_NC    = 0x01  // Noise Cancelling
        private const val ANC_WIND  = 0x02  // Wind Noise Reduction
        private const val ANC_AMBIENT = 0x11 // Ambient Sound Mode

        private var seqId: Byte = 0
    }

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isInitialized = false
    private var initContinuation: CancellableContinuation<Boolean>? = null

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    override suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit,
        awaitCharacteristicWrite: suspend () -> Unit
    ): Boolean {
        Timber.i("SonyWF1000XM5: initializing ${gatt.device.address}")

        delay(300) // Let the GATT stack settle after service discovery

        txCharacteristic = gatt.getService(UUID_SERVICE_SONY)?.getCharacteristic(UUID_CHAR_TX)
        rxCharacteristic = gatt.getService(UUID_SERVICE_SONY)?.getCharacteristic(UUID_CHAR_RX)

        if ((txCharacteristic == null) || (rxCharacteristic == null)) {
            Timber.e("SonyWF1000XM5: Sony control service not found — trying standard battery service")
            val batteryChar = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
                ?.getCharacteristic(UUID_CHAR_BATTERY)
            if (batteryChar != null) {
                if (enableNotification(gatt, batteryChar)) {
                    awaitDescriptorWrite()   // FIX 3: wait before returning
                }
                _events.emit(DeviceEvent.DeviceReady)
                return true
            }
            return false
        }

        // Enable RX notifications and wait for the CCCD write to complete
        if (enableNotification(gatt, rxCharacteristic!!)) {
            awaitDescriptorWrite()   // FIX 3: replaces unreliable delay(200)
        }

        // Send Sony initialization handshake with timeout
        return try {
            withTimeout(12_000L) { runSonyHandshake(gatt) }
        } catch (_: TimeoutCancellationException) {
            Timber.e("SonyWF1000XM5: init handshake timed out")
            initContinuation?.resume(value = false)
            initContinuation = null
            false
        }
    }

    private suspend fun runSonyHandshake(gatt: BluetoothGatt): Boolean {
        return suspendCancellableCoroutine { cont ->
            initContinuation = cont
            // Sony init packet: [0x3E][0x00][seqId][0x00][0x00][checksum]
            val initPacket = buildPacket(DATA_TYPE_INIT, byteArrayOf())
            val wrote = gatt.safeWriteCharacteristic(txCharacteristic!!, initPacket)
            if (!wrote) {
                Timber.e("SonyWF1000XM5: failed to send init packet")
                cont.resume(value = false)
            }
            cont.invokeOnCancellation { initContinuation = null }
        }
    }

    // -------------------------------------------------------------------------
    // Incoming data handler
    // -------------------------------------------------------------------------

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        return when (characteristic.uuid) {
            UUID_CHAR_RX -> {
                parseSonyPacket(gatt, value)
                true
            }
            UUID_CHAR_BATTERY -> {
                if (value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    scope.launch { _events.emit(DeviceEvent.Battery(level)) }
                }
                true
            }
            else -> false
        }
    }

    private fun parseSonyPacket(gatt: BluetoothGatt, data: ByteArray) {
        if (data.size < 3 || (data[0] != START_BYTE)) {
            Timber.w("SonyWF1000XM5: invalid packet received")
            return
        }

        val dataType = data[1]
        // payload starts at byte 5 (after start, type, seq, lenHigh, lenLow)
        val payloadStart = 5
        val payload = if (data.size > payloadStart) data.copyOfRange(payloadStart, data.size - 1) else byteArrayOf()

        when (dataType) {
            DATA_TYPE_INIT_RESP -> {
                Timber.i("SonyWF1000XM5: init handshake complete ✓")
                isInitialized = true
                scope.launch {
                    _events.emit(DeviceEvent.DeviceReady)
                    delay(100)
                    requestBattery(gatt)
                    delay(100)
                    requestAncMode(gatt)
                }
                initContinuation?.resume(value = true)
            }
            DATA_TYPE_BATTERY -> parseBatteryReport(payload)
            DATA_TYPE_ANC -> parseAncReport(payload)
            DATA_TYPE_WEARING -> parseWearingState(payload)
            DATA_TYPE_NOISE -> parseNoiseLevel(payload)
            else -> Timber.v("SonyWF1000XM5: unknown data type 0x${dataType.toUByte().toString(16)}")
        }
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    private fun parseBatteryReport(payload: ByteArray) {
        if (payload.size < 3) return
        // Sony reports L/R/Case battery separately
        // Format: [leftBattery, rightBattery, caseBattery] (0-10 scale → * 10 for percent)
        val leftBattery  = (payload[0].toInt() and 0xFF) * 10
        val rightBattery = (payload[1].toInt() and 0xFF) * 10
        val caseBattery = (payload[2].toInt() and 0xFF) * 10

        // Emit average of L+R
        val avgBattery = (leftBattery + rightBattery) / 2
        scope.launch { _events.emit(DeviceEvent.Battery(avgBattery)) }
        Timber.v("SonyWF1000XM5: battery L=$leftBattery% R=$rightBattery% Case=$caseBattery%")
    }

    private fun parseAncReport(payload: ByteArray) {
        if (payload.isEmpty()) return
        val mode = when (payload[0].toInt() and 0xFF) {
            ANC_NC      -> ANCMode.NOISE_CANCELLING
            ANC_AMBIENT -> ANCMode.AMBIENT
            ANC_WIND    -> ANCMode.WIND_REDUCTION
            else        -> ANCMode.OFF
        }
        scope.launch { _events.emit(DeviceEvent.AncMode(mode)) }
        Timber.v("SonyWF1000XM5: ANC mode = $mode")
    }

    private fun parseWearingState(payload: ByteArray) {
        if (payload.isEmpty()) return
        val wearing = (payload[0].toInt() and 0x01) == 1
        Timber.v("SonyWF1000XM5: wearing=$wearing")
    }

    private fun parseNoiseLevel(payload: ByteArray) {
        if (payload.isEmpty()) return
        val level = payload[0].toFloat()
        scope.launch { _events.emit(DeviceEvent.NoiseLevel(level)) }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        // WF-1000XM5 doesn't have a traditional vibration motor for custom patterns
        // but we can send a notification sound trigger
        Timber.d("SonyWF1000XM5: vibrate requested (no hardware motor, no-op)")
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        // WF-1000XM5 does not have a heart rate sensor
        Timber.d("SonyWF1000XM5: HR monitoring not supported on this device")
    }

    override suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean) {
        // WF-1000XM5 does not support raw sensor streaming
        Timber.d("SonyWF1000XM5: raw sensor streaming not supported on this device")
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        // Not required for earbuds
        Timber.d("SonyWF1000XM5: time sync not needed")
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        val packet = buildPacket(DATA_TYPE_BATTERY_GET, byteArrayOf())
        gatt.safeWriteCharacteristic(txCharacteristic ?: return, packet)
    }

    private fun requestAncMode(gatt: BluetoothGatt) {
        val packet = buildPacket(DATA_TYPE_ANC_GET, byteArrayOf())
        gatt.safeWriteCharacteristic(txCharacteristic ?: return, packet)
    }

    fun setAncMode(gatt: BluetoothGatt, mode: ANCMode) {
        val ancByte = when (mode) {
            ANCMode.NOISE_CANCELLING -> ANC_NC
            ANCMode.AMBIENT         -> ANC_AMBIENT
            ANCMode.WIND_REDUCTION  -> ANC_WIND
            ANCMode.OFF             -> ANC_OFF
        }
        val packet = buildPacket(DATA_TYPE_ANC, byteArrayOf(ancByte.toByte(), 0x01))
        gatt.safeWriteCharacteristic(txCharacteristic ?: return, packet)
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        // Switch to Noise Cancelling for better sleep data
        Timber.i("SonyWF1000XM5: sleep started – switching to NC mode")
        setAncMode(gatt, ANCMode.NOISE_CANCELLING)
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        Timber.i("SonyWF1000XM5: sleep stopped")
        setAncMode(gatt, ANCMode.OFF)
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) {
        // Play alarm notification sound through earbuds via ambient sound
        setAncMode(gatt, ANCMode.AMBIENT)
    }

    override suspend fun dismissAlarm(gatt: BluetoothGatt) {
        setAncMode(gatt, ANCMode.NOISE_CANCELLING)
    }

    // -------------------------------------------------------------------------
    // Packet builder
    // -------------------------------------------------------------------------

    /**
     * Sony packet format:
     * [0x3E][dataType][seqId][lenHigh][lenLow][payload...][checksum]
     * checksum = XOR of all bytes from dataType to last payload byte
     */
    private fun buildPacket(dataType: Byte, payload: ByteArray): ByteArray {
        val seq = seqId++
        val len = payload.size
        val packet = ByteArray(5 + len + 1)
        packet[0] = START_BYTE
        packet[1] = dataType
        packet[2] = seq
        packet[3] = ((len shr 8) and 0xFF).toByte()
        packet[4] = (len and 0xFF).toByte()
        payload.copyInto(packet, 5)

        // Checksum: XOR from byte[1] to byte[4+len]
        var checksum = 0
        for (i in 1 until (5 + len)) {
            checksum = checksum xor packet[i].toInt()
        }
        packet[5 + len] = checksum.toByte()
        return packet
    }

    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(UUID_CCCD) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    override fun destroy() {
        scope.cancel()
    }
}
