package nodomain.freeyourgadget.gadgetbridge.ble.protocol

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import kotlinx.coroutines.flow.Flow

/**
 * Abstract contract every device protocol must implement.
 * Each protocol is responsible for:
 *  - Authentication / handshake with the device
 *  - Parsing incoming GATT notifications into [DeviceEvent]s
 *  - Sending commands to the device
 */
interface DeviceProtocol {

    /** Emits events parsed from the device (heart rate, steps, battery, etc.) */
    val events: Flow<DeviceEvent>

    /**
     * Called once right after GATT services are discovered.
     * Implementations subscribe to notifications, run auth, and mark the device ready.
     *
     * @param awaitDescriptorWrite  Suspend function that completes when the most recently
     *   issued [BluetoothGatt.writeDescriptor] call is acknowledged by the remote device via
     *   [BluetoothGattCallback.onDescriptorWrite]. Protocols MUST call this after every
     *   descriptor write to serialise GATT operations — Android only allows one outstanding
     *   GATT operation at a time.
     * @return true if initialisation was successful
     */
    suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit
    ): Boolean

    /**
     * Called when a GATT characteristic has a new value.
     * @return true if the characteristic was consumed by this protocol
     */
    fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean

    /** Trigger vibration / find-my-device on the gadget */
    suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern = VibratePattern.SHORT)

    /** Tell the band to start/stop sending continuous heart rate */
    suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean)

    /** Tell the band to start/stop sending raw sensor data (accelerometer) */
    suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean)

    /** Sync time to the device */
    suspend fun syncTime(gatt: BluetoothGatt)

    /** Send battery level request */
    suspend fun requestBattery(gatt: BluetoothGatt)

    /** Called when sleep tracking begins (Sleep as Android integration) */
    suspend fun onSleepTrackingStarted(gatt: BluetoothGatt)

    /** Called when sleep tracking ends */
    suspend fun onSleepTrackingStopped(gatt: BluetoothGatt)

    /** Trigger alarm on the device (e.g., vibrate to wake user) */
    suspend fun triggerAlarm(gatt: BluetoothGatt)

    /** Stop alarm */
    suspend fun dismissAlarm(gatt: BluetoothGatt)

    /** Clean up resources */
    fun destroy()
}

// ---------------------------------------------------------------------------
// Events emitted by protocols
// ---------------------------------------------------------------------------

sealed class DeviceEvent {
    data class HeartRate(val bpm: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class Steps(val count: Int, val calories: Int = 0, val distance: Float = 0f) : DeviceEvent()
    data class Battery(val percent: Int, val charging: Boolean = false) : DeviceEvent()
    data class SleepData(val stage: SleepStage, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class SpO2(val percent: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class StressLevel(val score: Int, val timestamp: Long = System.currentTimeMillis()) : DeviceEvent()
    data class Spo2Alert(val value: Int) : DeviceEvent()
    data object DeviceReady : DeviceEvent()
    data class RawAccelerometer(val x: Float, val y: Float, val z: Float) : DeviceEvent()
    data class NoiseLevel(val db: Float) : DeviceEvent()   // Sony WF-1000XM5 ambient sound level
    data class AncMode(val mode: ANCMode) : DeviceEvent()  // Sony WF-1000XM5 ANC
}

enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

enum class VibratePattern { SHORT, LONG, DOUBLE, ALARM }

enum class ANCMode { OFF, NOISE_CANCELLING, AMBIENT, WIND_REDUCTION }

// ---------------------------------------------------------------------------
// Utility: safe GATT write with WriteType
// ---------------------------------------------------------------------------

fun BluetoothGatt.safeWriteCharacteristic(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION") characteristic.value     = value
        @Suppress("DEPRECATION") characteristic.writeType = writeType
        @Suppress("DEPRECATION") writeCharacteristic(characteristic)
    }
}
