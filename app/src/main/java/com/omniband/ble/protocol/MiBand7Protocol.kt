package com.omniband.ble.protocol

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.annotation.SuppressLint
import kotlin.coroutines.resume

/**
 * Protocol implementation for Xiaomi Smart Band 7 (Mi Band 7).
 *
 * The Mi Band 7 uses the Huami/ZeppOS "2021" protocol which employs:
 *  - BLE GATT for all communication
 *  - ECDH + AES-128 for authentication
 *  - A command/response framing protocol over a small set of characteristics
 *
 * Key UUIDs (Huami 2021 / ZeppOS protocol):
 *   Service:        0000fee0-0000-1000-8000-00805f9b34fb  (main data)
 *   Auth service:   0000fee1-0000-1000-8000-00805f9b34fb
 *   Char command:   00000016-0000-3512-2118-0009af100700  (write commands)
 *   Char activity:  00000007-0000-3512-2118-0009af100700  (activity data notify)
 *   Char auth:      00000009-0000-3512-2118-0009af100700  (auth write/notify)
 *   Char heart rate:00000038-0000-3512-2118-0009af100700  (heart rate notify)
 *   Char battery:   00000006-0000-3512-2118-0009af100700  (battery notify)
 *
 * References:
 *   - Gadgetbridge Xiaomi/Huami device support
 *   - patyork/miband-7-monitor (JS reverse-engineering)
 */
class MiBand7Protocol(
    private val authKey: ByteArray  // 16-byte auth key obtained from Xiaomi account
) : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    // -------------------------------------------------------------------------
    // UUID constants
    // -------------------------------------------------------------------------
    companion object {
        // Main service
        val UUID_SERVICE_MAIN = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
        val UUID_SERVICE_AUTH = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")

        // Alternative Main Service UUID for some firmware versions
        val UUID_SERVICE_MAIN_ALT = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")

        // Huami 2021 protocol characteristics (used by Mi Band 7)
        val UUID_CHAR_COMMAND   = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        val UUID_CHAR_ACTIVITY  = UUID.fromString("00000007-0000-3512-2118-0009af100700")
        val UUID_CHAR_AUTH      = UUID.fromString("00000009-0000-3512-2118-0009af100700")
        val UUID_CHAR_HEARTRATE = UUID.fromString("00000038-0000-3512-2118-0009af100700")
        val UUID_CHAR_BATTERY   = UUID.fromString("00000006-0000-3512-2118-0009af100700")
        val UUID_CHAR_STEPS     = UUID.fromString("00000007-0000-3512-2118-0009af100700")
        val UUID_CHAR_SPO2      = UUID.fromString("00000045-0000-3512-2118-0009af100700")

        // CCCD descriptor for enabling notifications
        val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Auth protocol steps
        private const val AUTH_SEND_KEY   = 0x01
        private const val AUTH_REQUEST_RND = 0x02
        private const val AUTH_SEND_ENC   = 0x03

        // Command types
        private const val CMD_SET_TIME       = 0x01
        private const val CMD_BATTERY        = 0x06
        private const val CMD_HR_CONTINUOUS  = 0x15
        private const val CMD_VIBRATE        = 0x10

        // Sequence counter for command framing
        private var seqCounter = 0
    }

    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var heartRateCharacteristic: BluetoothGattCharacteristic? = null
    private var activityCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var spo2Characteristic: BluetoothGattCharacteristic? = null

    private var isAuthenticated = false
    private var authContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private var lastServerRandomNumber: ByteArray? = null

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    override suspend fun initialize(gatt: BluetoothGatt): Boolean {
        Timber.i("MiBand7Protocol: initializing device ${gatt.device.address}")

        // Discover characteristics
        val mainService = gatt.getService(UUID_SERVICE_MAIN) ?: gatt.getService(UUID_SERVICE_MAIN_ALT)
        val authService = gatt.getService(UUID_SERVICE_AUTH)

        if (mainService == null) {
            Timber.e("MiBand7Protocol: Main service not found. Available services: ${gatt.services.map { it.uuid }}")
            return false
        }

        authCharacteristic    = authService?.getCharacteristic(UUID_CHAR_AUTH)
        commandCharacteristic = mainService.getCharacteristic(UUID_CHAR_COMMAND)
        heartRateCharacteristic = mainService.getCharacteristic(UUID_CHAR_HEARTRATE)
        activityCharacteristic  = mainService.getCharacteristic(UUID_CHAR_ACTIVITY)
        batteryCharacteristic   = mainService.getCharacteristic(UUID_CHAR_BATTERY)
        spo2Characteristic      = mainService.getCharacteristic(UUID_CHAR_SPO2)

        if (authCharacteristic == null || commandCharacteristic == null) {
            Timber.e("MiBand7Protocol: Required characteristics not found (auth=${authCharacteristic != null}, cmd=${commandCharacteristic != null})")
            return false
        }

        // Enable notifications on auth characteristic first
        enableNotification(gatt, authCharacteristic!!)
        delay(300)
        enableNotification(gatt, heartRateCharacteristic)
        delay(100)
        enableNotification(gatt, activityCharacteristic)
        delay(100)
        enableNotification(gatt, batteryCharacteristic)
        delay(100)

        // Run authentication handshake
        return runAuthHandshake(gatt)
    }

    private suspend fun runAuthHandshake(gatt: BluetoothGatt): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            authContinuation = cont

            // Step 1: Send auth key to the band
            val keyPayload = ByteArray(18).also { buf ->
                buf[0] = AUTH_SEND_KEY.toByte()
                buf[1] = 0x00
                authKey.copyInto(buf, 2)
            }
            val wrote = gatt.safeWriteCharacteristic(authCharacteristic!!, keyPayload)
            if (!wrote) {
                Timber.e("MiBand7: failed to write auth key")
                cont.resume(false)
            }

            cont.invokeOnCancellation { authContinuation = null }
        }
    }

    // -------------------------------------------------------------------------
    // GATT characteristic changed handler
    // -------------------------------------------------------------------------

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return when (characteristic.uuid) {
            UUID_CHAR_AUTH -> {
                handleAuthResponse(gatt, value)
                true
            }
            UUID_CHAR_HEARTRATE -> {
                parseHeartRate(value)
                true
            }
            UUID_CHAR_ACTIVITY -> {
                parseActivity(value)
                true
            }
            UUID_CHAR_BATTERY -> {
                parseBattery(value)
                true
            }
            UUID_CHAR_SPO2 -> {
                parseSpO2(value)
                true
            }
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Auth handshake logic
    // Step 1: App → Band: send auth key prefix byte + key
    // Step 2: Band → App: random number (16 bytes)
    // Step 3: App → Band: AES-ECB encrypt(random number, auth key)
    // Step 4: Band → App: success/failure
    // -------------------------------------------------------------------------

    private fun handleAuthResponse(gatt: BluetoothGatt, data: ByteArray) {
        if (data.size < 3) return
        val requestType = data[0].toInt() and 0xFF
        val status = data[2].toInt() and 0xFF

        when (requestType) {
            AUTH_SEND_KEY -> {
                if (status == 0x01) {
                    // Band accepted key, request random number
                    Timber.d("MiBand7: auth key accepted, requesting random number")
                    val rndRequest = byteArrayOf(AUTH_REQUEST_RND.toByte(), 0x00)
                    gatt.safeWriteCharacteristic(authCharacteristic!!, rndRequest)
                } else {
                    Timber.e("MiBand7: auth key rejected (status=$status)")
                    authContinuation?.resume(false)
                }
            }
            AUTH_REQUEST_RND -> {
                if (data.size < 19) return
                // Band sent us the random number (bytes 3..18)
                val randomNumber = data.copyOfRange(3, 19)
                lastServerRandomNumber = randomNumber
                Timber.d("MiBand7: got random number, encrypting…")

                // Encrypt with AES-128-ECB using our auth key
                val encrypted = aesEncrypt(randomNumber, authKey)
                val encPayload = ByteArray(18).also { buf ->
                    buf[0] = AUTH_SEND_ENC.toByte()
                    buf[1] = 0x00
                    encrypted.copyInto(buf, 2)
                }
                gatt.safeWriteCharacteristic(authCharacteristic!!, encPayload)
            }
            AUTH_SEND_ENC -> {
                isAuthenticated = status == 0x01
                if (isAuthenticated) {
                    Timber.i("MiBand7: authentication successful ✓")
                    scope.launch {
                        _events.emit(DeviceEvent.DeviceReady)
                        syncTime(gatt)
                        delay(200)
                        requestBattery(gatt)
                    }
                    authContinuation?.resume(true)
                } else {
                    Timber.e("MiBand7: authentication failed (status=$status)")
                    authContinuation?.resume(false)
                }
            }
        }
    }

    @SuppressLint("InsecureCipher")
    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    private fun parseHeartRate(data: ByteArray) {
        // HR measurement characteristic: [flags(1), value(1+)]
        if (data.isEmpty()) return
        val bpm = if (data.size >= 2) {
            val flags = data[0].toInt() and 0xFF
            if (flags and 0x01 == 0) {  // UINT8 format
                data[1].toInt() and 0xFF
            } else {  // UINT16 format
                ByteBuffer.wrap(data, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            }
        } else {
            data[0].toInt() and 0xFF
        }

        if (bpm in 30..300) {
            scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
            Timber.v("MiBand7: HR = $bpm bpm")
        }
    }

    private fun parseActivity(data: ByteArray) {
        if (data.size < 8) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val category = buf.get().toInt() and 0xFF
        if (category == 0x01) { // Steps data
            val steps = buf.int
            val meters = buf.short.toInt() and 0xFFFF
            val calories = buf.short.toInt() and 0xFFFF
            scope.launch {
                _events.emit(DeviceEvent.Steps(steps, calories, meters.toFloat()))
            }
            Timber.v("MiBand7: steps=$steps, dist=${meters}m, cal=$calories")
        }
    }

    private fun parseBattery(data: ByteArray) {
        if (data.isEmpty()) return
        val level = data[0].toInt() and 0xFF
        val charging = if (data.size > 1) (data[1].toInt() and 0x01) == 1 else false
        scope.launch { _events.emit(DeviceEvent.Battery(level, charging)) }
        Timber.v("MiBand7: battery=$level% charging=$charging")
    }

    private fun parseSpO2(data: ByteArray) {
        if (data.size < 2) return
        val status = data[0].toInt() and 0xFF
        if (status == 0x01) {  // measurement complete
            val spo2 = data[1].toInt() and 0xFF
            scope.launch { _events.emit(DeviceEvent.SpO2(spo2)) }
        }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        val payload = when (pattern) {
            VibratePattern.SHORT  -> byteArrayOf(0x10, 0x00, 0x01)
            VibratePattern.LONG   -> byteArrayOf(0x10, 0x00, 0x03)
            VibratePattern.DOUBLE -> byteArrayOf(0x10, 0x00, 0x02)
            VibratePattern.ALARM  -> byteArrayOf(0x10, 0x00, 0x04)
        }
        sendCommand(gatt, CMD_VIBRATE, payload)
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        // Enable/disable continuous heart rate monitoring
        val payload = if (continuous) {
            byteArrayOf(0x15, 0x02, 0x01)  // start continuous
        } else {
            byteArrayOf(0x15, 0x02, 0x00)  // stop
        }
        sendCommand(gatt, CMD_HR_CONTINUOUS, payload)
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        val cal = Calendar.getInstance()
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(cal[Calendar.YEAR].toShort())
            put((cal[Calendar.MONTH] + 1).toByte())
            put(cal[Calendar.DAY_OF_MONTH].toByte())
            put(cal[Calendar.HOUR_OF_DAY].toByte())
            put(cal[Calendar.MINUTE].toByte())
        }.array()
        sendCommand(gatt, CMD_SET_TIME, payload)
        Timber.i("MiBand7: synced time")
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        sendCommand(gatt, CMD_BATTERY, byteArrayOf())
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        Timber.i("MiBand7: sleep tracking started – enabling HR + SpO2")
        setHeartRateMonitoring(gatt, true)
        // Enable SpO2 monitoring during sleep
        sendCommand(gatt, 0x45, byteArrayOf(0x01))
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        Timber.i("MiBand7: sleep tracking stopped")
        setHeartRateMonitoring(gatt, false)
        sendCommand(gatt, 0x45, byteArrayOf(0x00))
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) {
        vibrate(gatt, VibratePattern.ALARM)
    }

    override suspend fun dismissAlarm(gatt: BluetoothGatt) {
        // Stop vibration
        sendCommand(gatt, CMD_VIBRATE, byteArrayOf(0x10, 0x00, 0x00))
    }

    // -------------------------------------------------------------------------
    // Framing helpers
    // -------------------------------------------------------------------------

    /**
     * Huami 2021 command framing:
     * [type(1)] [seqHigh(1)] [seqLow(1)] [payload...]
     */
    private fun sendCommand(gatt: BluetoothGatt, type: Int, payload: ByteArray) {
        val seq = seqCounter++
        val frame = ByteArray(3 + payload.size).also { buf ->
            buf[0] = type.toByte()
            buf[1] = ((seq shr 8) and 0xFF).toByte()
            buf[2] = (seq and 0xFF).toByte()
            payload.copyInto(buf, 3)
        }
        commandCharacteristic?.let { char ->
            gatt.safeWriteCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    // -------------------------------------------------------------------------
    // Notification helper
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        characteristic ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(UUID_CCCD)?.let { descriptor ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }
}
