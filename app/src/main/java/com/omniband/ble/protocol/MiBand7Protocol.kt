package com.omniband.ble.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.experimental.xor

/**
 * Protocol implementation for Xiaomi Smart Band 7 (ZeppOS).
 * Updated to use the 2021 Extended Header and B-163 ECDH flow.
 */
@SuppressLint("MissingPermission")
class MiBand7Protocol(
    private val authKey: ByteArray
) : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    companion object {
        val UUID_CHAR_CHUNKED_WRITE: UUID  = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        val UUID_CHAR_CHUNKED_READ: UUID   = UUID.fromString("00000017-0000-3512-2118-0009af100700")
        val UUID_SERVICE_HR: UUID          = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID                = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val AUTH_TIMEOUT_MS = 15_000L
        
        // Default ASCII key used by ZeppOS if no custom key is provided
        private val DEFAULT_AUTH_KEY = byteArrayOf(
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 
            0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45
        )
    }

    private var chunkedWrite: BluetoothGattCharacteristic? = null
    private var chunkedRead:  BluetoothGattCharacteristic? = null
    private var hrChar:       BluetoothGattCharacteristic? = null

    private val decoder = Huami2021Chunked.Decoder()
    private var handleSeq: Byte = 0

    var negotiatedMtu: Int = 23
        private set

    fun onMtuNegotiated(rawMtu: Int) {
        negotiatedMtu = rawMtu
        Timber.d("MiBand7: MTU set to $negotiatedMtu")
    }

    private var isAuthenticated = false

    @Volatile private var authContinuation: Continuation<Boolean>? = null
    @Volatile private var pendingEncKey:    ByteArray?              = null
    @Volatile private var privateEC:        ByteArray?              = null

    // Effective auth key (provided or default)
    private val effectiveAuthKey: ByteArray
        get() = if (authKey.all { it == 0.toByte() }) DEFAULT_AUTH_KEY else authKey

    // ── Initialization ───────────────────────────────────────────────

    override suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit
    ): Boolean {
        Timber.i("MiBand7: initializing ${gatt.device.address} (MTU=$negotiatedMtu)")

        for (svc in gatt.services) {
            if (chunkedWrite == null) chunkedWrite = svc.getCharacteristic(UUID_CHAR_CHUNKED_WRITE)
            if (chunkedRead  == null) chunkedRead  = svc.getCharacteristic(UUID_CHAR_CHUNKED_READ)
        }
        hrChar = gatt.getService(UUID_SERVICE_HR)?.getCharacteristic(UUID_CHAR_HR_MEASUREMENT)

        if (chunkedWrite == null || chunkedRead == null) {
            Timber.e("MiBand7: chunked characteristics not found")
            return false
        }

        delay(300)

        // Subscribe to chunked notifications
        if (!enableNotification(gatt, chunkedRead!!)) return false
        awaitDescriptorWrite()

        hrChar?.let {
            if (enableNotification(gatt, it)) awaitDescriptorWrite()
        }

        delay(200)

        return try {
            withTimeout(AUTH_TIMEOUT_MS) { runEcdhHandshake(gatt) }
        } catch (e: Exception) {
            Timber.e(e, "MiBand7: auth handshake failed")
            authContinuation?.resume(false)
            authContinuation = null
            false
        }
    }

    // ── ECDH Handshake (ZeppOS Flow) ─────────────────────────────────

    private suspend fun runEcdhHandshake(gatt: BluetoothGatt): Boolean {
        // Mi Band 7 uses B-163 curve for ECDH
        val priv = ByteArray(ECDH_B163.ECC_PRV_KEY_SIZE).apply { 
            SecureRandom().nextBytes(this) 
        }
        privateEC = priv
        val pub = ECDH_B163.generatePublic(priv) ?: throw Exception("EC pubkey gen failed")

        // ZeppOS public key command: [0x04][0x02][0x00][0x02][pub:48]
        val payload = ByteBuffer.allocate(4 + ECDH_B163.ECC_PUB_KEY_SIZE).apply {
            put(Huami2021Chunked.AUTH_CMD_PUB_KEY)
            put(0x02.toByte())
            put(0x00.toByte())
            put(0x02.toByte())
            put(pub)
        }.array()

        writeChunked(gatt, Huami2021Chunked.ENDPOINT_AUTH, payload)
        Timber.i("MiBand7: sent B-163 public key, awaiting band response…")

        return suspendCancellableCoroutine { cont ->
            authContinuation = cont
            cont.invokeOnCancellation { authContinuation = null }
        }
    }

    // ── Incoming data ────────────────────────────────────────────────

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean = when (characteristic.uuid) {
        UUID_CHAR_CHUNKED_READ -> {
            decoder.decode(value)?.let { msg ->
                if (msg.needsAck) sendAck(gatt, msg.handle, msg.count)
                dispatch(gatt, msg.endpoint, msg.payload)
            }
            true
        }
        UUID_CHAR_HR_MEASUREMENT -> { parseStdHr(value); true }
        else -> false
    }

    private fun sendAck(gatt: BluetoothGatt, handle: Byte, count: Byte) {
        // Ack format: [0x04][0x00][handle][0x01][count]
        val ack = byteArrayOf(0x04, 0x00, handle, 0x01, count)
        writeRaw(gatt, chunkedRead, ack)
    }

    private fun dispatch(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) {
        when (endpoint) {
            Huami2021Chunked.ENDPOINT_AUTH      -> handleAuth(gatt, payload)
            Huami2021Chunked.ENDPOINT_HEARTRATE -> parseChunkedHr(payload)
            Huami2021Chunked.ENDPOINT_BATTERY   -> parseBattery(payload)
            Huami2021Chunked.ENDPOINT_ACTIVITY  -> parseActivity(payload)
            Huami2021Chunked.ENDPOINT_SPO2      -> parseSpo2(payload)
            Huami2021Chunked.ENDPOINT_SLEEP     -> parseSleep(payload)
        }
    }

    private fun handleAuth(gatt: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty() || payload[0] != Huami2021Chunked.AUTH_RESP_PREFIX) return

        when (payload[1]) {
            Huami2021Chunked.AUTH_CMD_PUB_KEY -> {
                if (payload.size < 67) { // prefix, cmd, status, random(16), pub(48)
                    Timber.e("MiBand7: short pubkey response"); failAuth(); return
                }
                if (payload[2] != Huami2021Chunked.AUTH_SUCCESS) {
                    Timber.e("MiBand7: pubkey cmd failed (0x${payload[2].toUByte().toString(16)})"); failAuth(); return
                }
                Timber.d("MiBand7: received band public key ✓")

                try {
                    val remoteRandom = payload.copyOfRange(3, 19)
                    val remotePub = payload.copyOfRange(19, 67)
                    val shared = ECDH_B163.generateShared(privateEC!!, remotePub) ?: throw Exception("ECDH failed")
                    
                    // Derive session key: shared[8..23] XOR authKey
                    val sessionKey = ByteArray(16)
                    val keyToUse = effectiveAuthKey
                    for (i in 0 until 16) {
                        sessionKey[i] = (shared[i + 8] xor keyToUse[i])
                    }
                    pendingEncKey = sessionKey

                    // Send encrypted random: [0x05][AES(key, rand)][AES(session, rand)]
                    val enc1 = aesEcbEncrypt(keyToUse, remoteRandom)
                    val enc2 = aesEcbEncrypt(sessionKey, remoteRandom)
                    val resp = byteArrayOf(Huami2021Chunked.AUTH_CMD_SESSION_KEY) + enc1 + enc2
                    
                    writeChunked(gatt, Huami2021Chunked.ENDPOINT_AUTH, resp)
                    Timber.d("MiBand7: sent double-encrypted nonces ✓")
                } catch (e: Exception) {
                    Timber.e(e, "MiBand7: auth derivation failed"); failAuth()
                }
            }

            Huami2021Chunked.AUTH_CMD_SESSION_KEY -> {
                val ok = payload.size >= 3 && payload[2] == Huami2021Chunked.AUTH_SUCCESS
                if (ok) {
                    Timber.i("MiBand7: auth SUCCESS ✓")
                    isAuthenticated = true
                    scope.launch {
                        _events.emit(DeviceEvent.DeviceReady)
                        delay(200); syncTime(gatt)
                        delay(200); requestBattery(gatt)
                    }
                } else {
                    val code = if (payload.size >= 3) payload[2].toUByte().toString(16) else "?"
                    Timber.e("MiBand7: auth FAILED (0x$code)")
                }
                authContinuation?.resume(ok)
                cleanupAuth()
            }
        }
    }

    private fun failAuth() {
        authContinuation?.resume(false)
        cleanupAuth()
    }

    private fun cleanupAuth() {
        authContinuation = null
        pendingEncKey    = null
        privateEC        = null
    }

    // ── Parsers ───────────────────────────────────────────────────────

    private fun parseChunkedHr(p: ByteArray) {
        if (p.size < 3) return
        val bpm = p[2].toInt() and 0xFF
        if (bpm in 30..250) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    private fun parseStdHr(data: ByteArray) {
        if (data.size < 2) return
        val flags = data[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) data[1].toInt() and 0xFF
                  else ByteBuffer.wrap(data, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (bpm in 30..250) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    private fun parseBattery(p: ByteArray) {
        if (p.size < 2) return
        val level = p[1].toInt() and 0xFF
        val charging = p.size >= 3 && p[2].toInt() == 1
        scope.launch { _events.emit(DeviceEvent.Battery(level, charging)) }
    }

    private fun parseActivity(p: ByteArray) {
        if (p.size < 10) return
        val buf = ByteBuffer.wrap(p, 2, p.size - 2).order(ByteOrder.LITTLE_ENDIAN)
        val steps = buf.int
        val dist = buf.short.toInt() and 0xFFFF
        val cal = buf.short.toInt() and 0xFFFF
        scope.launch { _events.emit(DeviceEvent.Steps(steps, cal, dist.toFloat())) }
    }

    private fun parseSpo2(p: ByteArray) {
        if (p.size < 2 || p[0].toInt() and 0xFF != 0x01) return
        val v = p[1].toInt() and 0xFF
        if (v in 50..100) scope.launch { _events.emit(DeviceEvent.SpO2(v)) }
    }

    private fun parseSleep(p: ByteArray) {
        if (p.isEmpty()) return
        val stage = when (p[0].toInt() and 0xFF) {
            0x00 -> SleepStage.AWAKE; 0x01 -> SleepStage.LIGHT
            0x02 -> SleepStage.DEEP;  0x03 -> SleepStage.REM
            else -> null
        } ?: return
        scope.launch { _events.emit(DeviceEvent.SleepData(stage)) }
    }

    // ── Commands ──────────────────────────────────────────────────────

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x01))
        if (pattern == VibratePattern.SHORT || pattern == VibratePattern.DOUBLE) {
            delay(600)
            writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x00))
        }
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        val p = if (continuous) byteArrayOf(0x01, 0x00, 0x01) else byteArrayOf(0x01, 0x00, 0x00)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_HEARTRATE, p)
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        val c = Calendar.getInstance()
        val tz = (c.timeZone.rawOffset / 60_000 / 15).toByte()
        val p = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).run {
            put(0x01.toByte())
            putShort(c.get(Calendar.YEAR).toShort())
            put((c.get(Calendar.MONTH) + 1).toByte())
            put(c.get(Calendar.DAY_OF_MONTH).toByte())
            put(c.get(Calendar.HOUR_OF_DAY).toByte())
            put(c.get(Calendar.MINUTE).toByte())
            put(c.get(Calendar.SECOND).toByte())
            put(tz)
            array()
        }
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SET_TIME, p)
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_BATTERY, byteArrayOf(0x01))
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        setHeartRateMonitoring(gatt, true)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x01))
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        setHeartRateMonitoring(gatt, false)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x00))
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) =
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x01))

    override suspend fun dismissAlarm(gatt: BluetoothGatt) =
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x00))

    // ── BLE Write Helpers ─────────────────────────────────────────────

    private fun writeChunked(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) {
        val char = chunkedWrite ?: return
        val packets = Huami2021Chunked.encode(handleSeq++, endpoint, payload, negotiatedMtu)
        for (packet in packets) {
            writeRaw(gatt, char, packet, noResponse = true)
        }
    }

    private fun writeRaw(
        gatt: BluetoothGatt, 
        char: BluetoothGattCharacteristic?, 
        data: ByteArray, 
        noResponse: Boolean = false
    ) {
        if (char == null) return
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE 
                        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(char, true)) return false
        val desc = char.getDescriptor(UUID_CCCD) ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    @SuppressLint("GetInstance")
    private fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    override fun destroy() {
        authContinuation?.resume(false)
        cleanupAuth()
        scope.cancel()
    }
}
