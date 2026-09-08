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
 * Protocol implementation for Sony WF-1000XM5 wireless earbuds.
 *
 * The WF-1000XM5 exposes both Bluetooth Classic (A2DP / HFP — handled by Android's BT stack)
 * and a proprietary BLE control channel.  This protocol targets the BLE channel, which provides:
 * - ANC mode control (Noise Cancelling / Ambient Sound / Off / Wind Noise Reduction)
 * - Battery levels for Left, Right, and Case (reported on a 0–10 scale × 10 = percent)
 * - Ambient noise level (dB) in Ambient Sound mode
 * - Wearing state detection
 *
 * ## BLE profile
 * | Role    | UUID                                     |
 * |---------|------------------------------------------|
 * | Service | `75c27625-bd42-d645-0b00-a4acd5dfb3b4`  |
 * | TX (→)  | `75c27625-bd42-d645-0b01-a4acd5dfb3b4`  |
 * | RX (←) | `75c27625-bd42-d645-0b02-a4acd5dfb3b4`  |
 *
 * ## Sony serial protocol
 * ```
 * [0x3E][dataType:1][seqId:1][lenHigh:1][lenLow:1][payload:N][checksum:1]
 * checksum = XOR of bytes[1..4+N]
 * ```
 *
 * ## Known data types
 * | Type | Direction | Description              |
 * |------|-----------|--------------------------|
 * | 0x00 | →         | Init handshake           |
 * | 0x01 | ←         | Init response            |
 * | 0x0C | ←         | Battery report (L/R/Case)|
 * | 0x10 | →         | Request battery          |
 * | 0x17 | →         | Request ANC mode         |
 * | 0x56 | ←         | Wearing state            |
 * | 0x68 | ↔         | ANC mode report / set    |
 * | 0x8A | ←         | Ambient noise level (dB) |
 *
 * ## References
 * - Gadgetbridge Sony Headphones implementation by José Rebelo
 * - Protocol reverse-engineered via nRF Connect + Wireshark HCI snoop log
 */
@SuppressLint("MissingPermission")
class SonyWF1000XM5Protocol : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<DeviceEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    companion object {
        val UUID_SERVICE_SONY: UUID = UUID.fromString("75c27625-bd42-d645-0b00-a4acd5dfb3b4")
        val UUID_CHAR_TX: UUID = UUID.fromString("75c27625-bd42-d645-0b01-a4acd5dfb3b4")
        val UUID_CHAR_RX: UUID = UUID.fromString("75c27625-bd42-d645-0b02-a4acd5dfb3b4")

        /** Standard GATT battery level (used as fallback when Sony service is absent). */
        val UUID_CHAR_BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ── Data type constants ───────────────────────────────────────────────
        private const val DATA_TYPE_INIT = 0x00.toByte()
        private const val DATA_TYPE_INIT_RESP = 0x01.toByte()
        private const val DATA_TYPE_BATTERY = 0x0C.toByte()
        private const val DATA_TYPE_BATTERY_GET = 0x10.toByte()
        private const val DATA_TYPE_ANC_GET = 0x17.toByte()
        private const val DATA_TYPE_WEARING = 0x56.toByte()
        private const val DATA_TYPE_ANC = 0x68.toByte()
        private const val DATA_TYPE_NOISE = 0x8A.toByte()

        // ── ANC mode byte values ──────────────────────────────────────────────
        private const val ANC_OFF = 0x00
        private const val ANC_NC = 0x01  // Noise Cancelling
        private const val ANC_WIND = 0x02  // Wind Noise Reduction
        private const val ANC_AMBIENT = 0x11  // Ambient Sound Mode
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isInitialized = false
    private var initContinuation: CancellableContinuation<Boolean>? = null

    /**
     * Per-instance sequence counter for outgoing Sony packets.
     *
     * **Bug fix:** was previously declared in `companion object`, making it shared across ALL
     * protocol instances. Moving it here ensures each connection gets its own independent
     * counter, which is required by the Sony protocol.
     */
    private var seqId: Byte = 0

    // ── Initialisation ────────────────────────────────────────────────────────

    override suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit,
        awaitCharacteristicWrite: suspend () -> Unit,
    ): Boolean {
        Timber.i("SonyWF1000XM5: initializing %s", gatt.device.address)
        delay(300)  // Let the GATT stack settle after service discovery

        txCharacteristic = gatt.getService(UUID_SERVICE_SONY)?.getCharacteristic(UUID_CHAR_TX)
        rxCharacteristic = gatt.getService(UUID_SERVICE_SONY)?.getCharacteristic(UUID_CHAR_RX)

        if (txCharacteristic == null || rxCharacteristic == null) {
            Timber.e("SonyWF1000XM5: Sony service not found — falling back to standard battery service")
            val svc = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
            val batteryChar = svc?.getCharacteristic(UUID_CHAR_BATTERY)
            if (batteryChar != null) {
                if (enableNotification(gatt, batteryChar)) awaitDescriptorWrite()
                _events.emit(DeviceEvent.DeviceReady)
                return true
            }
            return false
        }

        // Subscribe to RX notifications and wait for the CCCD write to complete
        if (enableNotification(gatt, rxCharacteristic!!)) awaitDescriptorWrite()

        return try {
            withTimeout(12_000L) { runSonyHandshake(gatt) }
        } catch (_: TimeoutCancellationException) {
            Timber.e("SonyWF1000XM5: init handshake timed out")
            initContinuation?.resume(false)
            initContinuation = null
            false
        }
    }

    private suspend fun runSonyHandshake(gatt: BluetoothGatt): Boolean =
        suspendCancellableCoroutine { cont ->
            initContinuation = cont
            val wrote = gatt.safeWriteCharacteristic(
                txCharacteristic!!,
                buildPacket(DATA_TYPE_INIT, byteArrayOf())
            )
            if (!wrote) {
                Timber.e("SonyWF1000XM5: failed to write init packet")
                cont.resume(false)
            }
            cont.invokeOnCancellation { initContinuation = null }
        }

    // ── Incoming data handler ─────────────────────────────────────────────────

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = when (characteristic.uuid) {
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

    private fun parseSonyPacket(gatt: BluetoothGatt, data: ByteArray) {
        if (data.size < 3 || data[0] != 0x3E.toByte()) {
            Timber.w(
                "SonyWF1000XM5: invalid packet (size=%d, first=0x%02x)", data.size,
                data.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
            )
            return
        }

        val dataType = data[1]
        // Payload starts at byte 5: [0x3E][type][seq][lenH][lenL][payload…][checksum]
        val payload = if (data.size > 5) data.copyOfRange(5, data.size - 1) else byteArrayOf()

        when (dataType) {
            DATA_TYPE_INIT_RESP -> {
                Timber.i("SonyWF1000XM5: init handshake complete ✓")
                isInitialized = true
                scope.launch {
                    _events.emit(DeviceEvent.DeviceReady)
                    delay(100); requestBattery(gatt)
                    delay(100); requestAncMode(gatt)
                }
                initContinuation?.resume(true)
            }
            DATA_TYPE_BATTERY -> parseBatteryReport(payload)
            DATA_TYPE_ANC -> parseAncReport(payload)
            DATA_TYPE_WEARING -> parseWearingState(payload)
            DATA_TYPE_NOISE -> parseNoiseLevel(payload)
            else -> Timber.v("SonyWF1000XM5: unknown type 0x%02x", dataType.toInt() and 0xFF)
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    /**
     * Battery report from the earbuds.
     *
     * Sony encodes battery on a **0–10 scale** where each unit represents 10 %.
     * Left and right batteries are averaged for the single [DeviceEvent.Battery] value.
     */
    private fun parseBatteryReport(payload: ByteArray) {
        if (payload.size < 3) return
        val left = (payload[0].toInt() and 0xFF) * 10
        val right = (payload[1].toInt() and 0xFF) * 10
        val case = (payload[2].toInt() and 0xFF) * 10
        val avg = (left + right) / 2
        Timber.v("SonyWF1000XM5: battery L=%d%% R=%d%% Case=%d%%", left, right, case)
        scope.launch { _events.emit(DeviceEvent.Battery(avg)) }
    }

    private fun parseAncReport(payload: ByteArray) {
        if (payload.isEmpty()) return
        val mode = when (payload[0].toInt() and 0xFF) {
            ANC_NC      -> ANCMode.NOISE_CANCELLING
            ANC_AMBIENT -> ANCMode.AMBIENT
            ANC_WIND    -> ANCMode.WIND_REDUCTION
            else        -> ANCMode.OFF
        }
        Timber.v("SonyWF1000XM5: ANC mode = %s", mode)
        scope.launch { _events.emit(DeviceEvent.AncMode(mode)) }
    }

    private fun parseWearingState(payload: ByteArray) {
        if (payload.isEmpty()) return
        Timber.v("SonyWF1000XM5: wearing=%s", (payload[0].toInt() and 0x01) == 1)
    }

    private fun parseNoiseLevel(payload: ByteArray) {
        if (payload.isEmpty()) return
        scope.launch { _events.emit(DeviceEvent.NoiseLevel(payload[0].toFloat())) }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        Timber.d("SonyWF1000XM5: vibrate — no hardware motor, no-op")
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        Timber.d("SonyWF1000XM5: HR monitoring not supported")
    }

    override suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean) {
        Timber.d("SonyWF1000XM5: raw sensor streaming not supported")
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        Timber.d("SonyWF1000XM5: time sync not required for earbuds")
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        gatt.safeWriteCharacteristic(
            txCharacteristic ?: return,
            buildPacket(DATA_TYPE_BATTERY_GET, byteArrayOf())
        )
    }

    private fun requestAncMode(gatt: BluetoothGatt) {
        gatt.safeWriteCharacteristic(
            txCharacteristic ?: return,
            buildPacket(DATA_TYPE_ANC_GET, byteArrayOf())
        )
    }

    fun setAncMode(gatt: BluetoothGatt, mode: ANCMode) {
        val ancByte = when (mode) {
            ANCMode.NOISE_CANCELLING -> ANC_NC
            ANCMode.AMBIENT -> ANC_AMBIENT
            ANCMode.WIND_REDUCTION -> ANC_WIND
            ANCMode.OFF -> ANC_OFF
        }.toByte()
        gatt.safeWriteCharacteristic(
            txCharacteristic ?: return,
            buildPacket(DATA_TYPE_ANC, byteArrayOf(ancByte, 0x01))
        )
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        Timber.i("SonyWF1000XM5: sleep started — enabling NC for better isolation")
        setAncMode(gatt, ANCMode.NOISE_CANCELLING)
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        Timber.i("SonyWF1000XM5: sleep stopped")
        setAncMode(gatt, ANCMode.OFF)
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) = setAncMode(gatt, ANCMode.AMBIENT)
    override suspend fun dismissAlarm(gatt: BluetoothGatt) =
        setAncMode(gatt, ANCMode.NOISE_CANCELLING)

    override fun destroy() {
        scope.cancel()
    }

    // ── Packet builder ────────────────────────────────────────────────────────

    /**
     * Builds a Sony serial packet.
     * ```
     * [0x3E][dataType][seqId][lenHigh][lenLow][payload…][checksum]
     * checksum = XOR of bytes[1..4+payloadLen]
     * ```
     */
    private fun buildPacket(dataType: Byte, payload: ByteArray): ByteArray {
        val seq = seqId++
        val len = payload.size
        val packet = ByteArray(6 + len)
        packet[0] = 0x3E.toByte()     // Start byte
        packet[1] = dataType
        packet[2] = seq
        packet[3] = ((len shr 8) and 0xFF).toByte()
        packet[4] = (len and 0xFF).toByte()
        payload.copyInto(packet, 5)
        var checksum = 0
        for (i in 1 until 5 + len) checksum = checksum xor packet[i].toInt()
        packet[5 + len] = checksum.toByte()
        return packet
    }

    private fun enableNotification(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic
    ): Boolean {
        if (!gatt.setCharacteristicNotification(char, true)) return false
        val desc = char.getDescriptor(UUID_CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION") gatt.writeDescriptor(desc)
        }
    }
}
