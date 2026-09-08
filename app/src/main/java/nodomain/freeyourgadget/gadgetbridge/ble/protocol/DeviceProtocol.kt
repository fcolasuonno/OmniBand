package nodomain.freeyourgadget.gadgetbridge.ble.protocol

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import kotlinx.coroutines.flow.Flow

/**
 * Abstract contract every device protocol must implement.
 *
 * A protocol is responsible for:
 * - Running the authentication / handshake with the physical device
 * - Subscribing to GATT notifications
 * - Parsing incoming notification payloads into [DeviceEvent]s emitted on [events]
 * - Sending commands to the device (vibrate, HR control, time sync, …)
 *
 * ## GATT operation serialisation
 * Android's BT stack allows **only one outstanding GATT operation** at a time.  The
 * [BleManager][nodomain.freeyourgadget.gadgetbridge.ble.BleManager] provides two suspend
 * lambdas — [awaitDescriptorWrite] and [awaitCharacteristicWrite] — that block until the
 * corresponding `onDescriptorWrite` / `onCharacteristicWrite` callback fires.  Protocols
 * **must** call these after every write to avoid operation overlap.
 *
 * ## Lifecycle
 * 1. [initialize] is called once, right after GATT service discovery completes.
 * 2. [onCharacteristicChanged] is called for every incoming GATT notification.
 * 3. Command methods (`vibrate`, `syncTime`, …) may be called at any time after init succeeds.
 * 4. [destroy] is called on disconnect; implementations should cancel their coroutine scope.
 */
interface DeviceProtocol {

    /** Typed events parsed from the device (heart rate, steps, battery, …). */
    val events: Flow<DeviceEvent>

    /**
     * Called once immediately after GATT service discovery.
     *
     * Implementations should:
     * 1. Locate required characteristics.
     * 2. Enable GATT notifications (calling [awaitDescriptorWrite] after each descriptor write).
     * 3. Run the authentication handshake.
     * 4. Emit [DeviceEvent.DeviceReady] when the device is fully ready to accept commands.
     *
     * @param gatt                    Active GATT connection.
     * @param awaitDescriptorWrite    Suspends until the pending `writeDescriptor` is acknowledged.
     * @param awaitCharacteristicWrite Suspends until the pending `writeCharacteristic` is acknowledged.
     * @return `true` if initialisation was successful; `false` to signal a permanent failure.
     */
    suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit,
        awaitCharacteristicWrite: suspend () -> Unit,
    ): Boolean

    /**
     * Called for every GATT characteristic notification.
     * @return `true` if this protocol handled the characteristic; `false` to pass it on.
     */
    fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean

    /** Trigger a vibration / find-my-device alert on the gadget. */
    suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern = VibratePattern.SHORT)

    /** Enable or disable continuous heart-rate monitoring on the device. */
    suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean)

    /**
     * Enable or disable raw sensor data streaming (accelerometer).
     *
     * Note: Mi Band 7 / ZeppOS does not support this — the method is a no-op for that device.
     * Implementations for other devices (future or community-contributed) may use it to
     * stream 1 Hz actigraphy data required by Sleep as Android.
     */
    suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean)

    /** Push the current phone time to the device. */
    suspend fun syncTime(gatt: BluetoothGatt)

    /** Request the current battery level (response comes via [events]). */
    suspend fun requestBattery(gatt: BluetoothGatt)

    /** Called by Sleep as Android integration when a sleep-tracking session begins. */
    suspend fun onSleepTrackingStarted(gatt: BluetoothGatt)

    /** Called by Sleep as Android integration when a sleep-tracking session ends. */
    suspend fun onSleepTrackingStopped(gatt: BluetoothGatt)

    /** Trigger the alarm alert on the device (e.g., Smart Wake vibration). */
    suspend fun triggerAlarm(gatt: BluetoothGatt)

    /** Stop the alarm alert on the device. */
    suspend fun dismissAlarm(gatt: BluetoothGatt)

    /** Release all resources (cancel coroutine scope, clear GATT handles). */
    fun destroy()
}

// ── Typed events ──────────────────────────────────────────────────────────────

/** All data emitted from a connected device flows through this sealed hierarchy. */
sealed class DeviceEvent {
    data class HeartRate(val bpm: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class Steps(val count: Int, val calories: Int = 0, val distance: Float = 0f) : DeviceEvent()
    data class Battery(val percent: Int, val charging: Boolean = false) : DeviceEvent()
    data class SleepData(val stage: SleepStage, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class SpO2(val percent: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class StressLevel(val score: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()

    /**
     * Raw 3-axis accelerometer reading.
     * Emitted only by devices that support raw sensor streaming.
     * **Not emitted by Mi Band 7** — ZeppOS has no raw sensor API.
     */
    data class RawAccelerometer(val x: Float, val y: Float, val z: Float) : DeviceEvent()

    /** Ambient noise level in dB (Sony WF-1000XM5 Ambient Sound mode). */
    data class NoiseLevel(val db: Float) : DeviceEvent()

    /** ANC mode changed (Sony WF-1000XM5). */
    data class AncMode(val mode: ANCMode) : DeviceEvent()

    /** Device completed its auth handshake and is ready to accept commands. */
    data object DeviceReady : DeviceEvent()
}

enum class SleepStage { AWAKE, LIGHT, DEEP, REM }
enum class VibratePattern { SHORT, LONG, DOUBLE, ALARM }
enum class ANCMode { OFF, NOISE_CANCELLING, AMBIENT, WIND_REDUCTION }

// ── Utility: safe cross-version GATT write ────────────────────────────────────

/**
 * Writes [value] to [characteristic] using the correct API for the running Android version.
 * Returns `true` if the write was accepted by the stack, `false` on error.
 */
@Suppress("DEPRECATION")  // Pre-Tiramisu GATT API branch is intentionally kept
fun BluetoothGatt.safeWriteCharacteristic(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
} else {
    characteristic.value = value
    characteristic.writeType = writeType
    writeCharacteristic(characteristic)
}
