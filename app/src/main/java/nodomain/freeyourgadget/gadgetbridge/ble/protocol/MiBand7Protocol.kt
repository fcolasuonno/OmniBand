package nodomain.freeyourgadget.gadgetbridge.ble.protocol

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
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
    private val authKey: ByteArray,
) : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<DeviceEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    companion object {
        val UUID_CHAR_CHUNKED_WRITE: UUID  = UUID.fromString("00000016-0000-3512-2118-0009af100700")
        val UUID_CHAR_CHUNKED_READ: UUID   = UUID.fromString("00000017-0000-3512-2118-0009af100700")
        val UUID_SERVICE_HR: UUID          = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID                = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val AUTH_TIMEOUT_MS = 30_000L
        
        // Default ASCII key used by ZeppOS if no custom key is provided
        private val DEFAULT_AUTH_KEY = byteArrayOf(
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45,
        )
    }

    private var chunkedWrite: BluetoothGattCharacteristic? = null
    private var chunkedRead:  BluetoothGattCharacteristic? = null
    private var hrChar:       BluetoothGattCharacteristic? = null

    private val decoder = Huami2021Chunked.Decoder()
    @Volatile
    private var handleSeq: Byte = 1
    @Volatile
    private var encryptedSeq: Int = 0
    private val writeMutex = Mutex()

    var negotiatedMtu: Int = 23
        private set

    fun onMtuNegotiated(rawMtu: Int) {
        negotiatedMtu = rawMtu
        Timber.d("MiBand7: MTU set to $negotiatedMtu")
    }

    @Volatile
    private var isInitialized = false
    @Volatile
    private var isAuthenticated = false
    @Volatile
    private var isInitializingServices = false

    @Volatile private var authContinuation: Continuation<Boolean>? = null
    @Volatile private var pendingEncKey:    ByteArray?              = null
    @Volatile
    private var pendingEncSeq: Int = 0
    @Volatile private var privateEC:        ByteArray?              = null

    private var awaitDescriptorWrite: (suspend () -> Unit)? = null
    private var awaitCharacteristicWrite: (suspend () -> Unit)? = null

    // Effective auth key (provided or default)
    private val effectiveAuthKey: ByteArray
        get() = if (authKey.all { it == 0.toByte() }) DEFAULT_AUTH_KEY else authKey

    // ── Initialization ───────────────────────────────────────────────

    override suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit,
        awaitCharacteristicWrite: suspend () -> Unit,
    ): Boolean {
        if (isInitialized) {
            Timber.w("MiBand7: already initialized, skipping…")
            return true
        }
        isInitialized = true
        this.awaitDescriptorWrite = awaitDescriptorWrite
        this.awaitCharacteristicWrite = awaitCharacteristicWrite

        Timber.i("MiBand7: initializing ${gatt.device.address} (MTU=$negotiatedMtu)")

        for (svc in gatt.services) {
            if (chunkedWrite == null) chunkedWrite = svc.getCharacteristic(UUID_CHAR_CHUNKED_WRITE)
            if (chunkedRead  == null) chunkedRead  = svc.getCharacteristic(UUID_CHAR_CHUNKED_READ)
        }
        hrChar = gatt.getService(UUID_SERVICE_HR)?.getCharacteristic(UUID_CHAR_HR_MEASUREMENT)
        val stdBattery = gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
            ?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))

        if ((chunkedWrite == null) || (chunkedRead == null)) {
            Timber.e("MiBand7: chunked characteristics not found")
            return false
        }

        delay(600)

        // Subscribe to chunked notifications
        if (!enableNotification(gatt, chunkedRead!!)) return false
        awaitDescriptorWrite()
        delay(200)

        if ((chunkedWrite!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            if (enableNotification(gatt, chunkedWrite!!)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        hrChar?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        stdBattery?.let {
            Timber.i("MiBand7: Found standard battery service, enabling notification")
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        delay(600)

        return try {
            withTimeout(AUTH_TIMEOUT_MS) { runEcdhHandshake(gatt) }
        } catch (e: Exception) {
            Timber.e(e, "MiBand7: auth handshake failed")
            authContinuation?.resume(value = false)
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

        writeChunked(gatt, Huami2021Chunked.ENDPOINT_AUTH_ZEPPOS, payload)
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
        value: ByteArray,
    ): Boolean {
        Timber.v(
            "MiBand7: RAW NOTIFY on ${characteristic.uuid}: ${
                value.joinToString {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
        val uuid = characteristic.uuid
        if (uuid != UUID_CHAR_HR_MEASUREMENT) {
            Timber.d(
                "MiBand7: onCharacteristicChanged $uuid: ${
                    value.joinToString {
                        it.toUByte().toString(16).padStart(2, '0')
                    }
                }"
            )
        }

        return when (uuid) {
            UUID_CHAR_CHUNKED_READ, UUID_CHAR_CHUNKED_WRITE -> {
                if (value.isNotEmpty() && value[0] == 0x03.toByte()) {
                    decoder.decode(value)?.let { result ->
                        if (result.needsAck) sendAck(gatt, result.handle, result.count)
                        result.message?.let { msg ->
                            dispatch(gatt, msg.endpoint, msg.payload)
                        }
                    }
                    true
                } else if (value.isNotEmpty() && value[0] == 0x04.toByte()) {
                    // ACK from band for our writes
                    true
                } else {
                    false
                }
            }

            UUID_CHAR_HR_MEASUREMENT -> {
                parseStdHr(value); true
            }

            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb") -> {
                if (value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    Timber.i("MiBand7: standard battery update -> $level%")
                    scope.launch { _events.emit(DeviceEvent.Battery(level)) }
                }
                true
            }

            else -> false
        }
    }

    private fun sendAck(gatt: BluetoothGatt, handle: Byte, count: Byte) {
        Timber.v("MiBand7: sending chunked ACK for handle=$handle, count=$count")
        // Ack format: [0x04][0x00][handle][0x01][count]
        val ack = byteArrayOf(0x04, 0x00, handle, 0x01, count)
        scope.launch {
            writeRaw(gatt, chunkedRead, ack, noResponse = true)
        }
    }

    private fun dispatch(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) {
        Timber.v(
            "MiBand7: dispatching endpoint 0x${endpoint.toString(16)}: ${
                payload.joinToString {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
        when (endpoint) {
            Huami2021Chunked.ENDPOINT_SERVICES -> {
                Timber.d("MiBand7: Got Services list (${payload.size} bytes)")
                if (!isInitializingServices) {
                    isInitializingServices = true
                    scope.launch {
                        delay(2000)
                        requestDeviceInfo(gatt)
                        delay(1000)
                        syncTime(gatt)
                        delay(1000)
                        enableRealtimeSteps(gatt)

                        // Periodic updates for battery and steps
                        while (true) {
                            requestBattery(gatt)
                            delay(1000)
                            requestCurrentSteps(gatt)
                            delay(30_000)
                        }
                    }
                }
            }

            Huami2021Chunked.ENDPOINT_AUTH, Huami2021Chunked.ENDPOINT_AUTH_ZEPPOS, Huami2021Chunked.ENDPOINT_AUTH_RESP -> {
                scope.launch { handleAuth(gatt, payload) }
            }
            Huami2021Chunked.ENDPOINT_HEARTRATE -> {
                if (payload.isNotEmpty() && payload[0] == 0x06.toByte()) {
                    parseSleep(payload)
                } else {
                    parseChunkedHr(payload)
                }
            }
            Huami2021Chunked.ENDPOINT_BATTERY -> {
                Timber.d(
                    "MiBand7: Got Battery endpoint data: ${
                        payload.joinToString {
                            it.toUByte().toString(16).padStart(2, '0')
                        }
                    }"
                )
                parseBattery(payload)
            }

            Huami2021Chunked.ENDPOINT_STEPS, 0x0015.toShort() -> {
                Timber.v(
                    "MiBand7: Got Activity/Step data from endpoint 0x${endpoint.toString(16)}: ${
                        payload.joinToString {
                            it.toUByte().toString(16).padStart(2, '0')
                        }
                    }"
                )
                parseActivity(payload)
            }
            Huami2021Chunked.ENDPOINT_SPO2      -> parseSpo2(payload)
            Huami2021Chunked.ENDPOINT_DEVICE_INFO -> parseDeviceInfo(payload)
            else -> {
                Timber.d("MiBand7: Got data for unhandled endpoint 0x${endpoint.toString(16)}: ${payload.size} bytes")
            }
        }
    }

    private suspend fun handleAuth(gatt: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty() || (payload[0] != Huami2021Chunked.AUTH_RESP_PREFIX)) return

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

                    // Initial encrypted sequence number is read from the first 4 bytes of shared secret
                    pendingEncSeq = ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN).int

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
                val ok = (payload.size >= 3) && (payload[2] == Huami2021Chunked.AUTH_SUCCESS)
                if (ok) {
                    Timber.i("MiBand7: auth SUCCESS ✓")
                    isAuthenticated = true
                    decoder.sessionKey = pendingEncKey
                    encryptedSeq = pendingEncSeq
                    scope.launch {
                        _events.emit(DeviceEvent.DeviceReady)
                        delay(600); requestBattery(gatt)
                        delay(600); requestServices(gatt)
                    }
                } else {
                    val code = if (payload.size >= 3) payload[2].toUByte().toString(16) else "?"
                    Timber.e("MiBand7: auth FAILED (0x$code)")
                }
                authContinuation?.resume(ok)
                cleanupAuth()
            }

            else -> {
                Timber.w(
                    "MiBand7: unknown auth payload: ${
                        payload.joinToString {
                            it.toUByte().toString(16).padStart(2, '0')
                        }
                    }"
                )
                authContinuation?.resume(false)
                cleanupAuth()
            }
        }
    }

    private fun failAuth() {
        authContinuation?.resume(value = false)
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
        if (bpm in (30..250)) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    private fun parseStdHr(data: ByteArray) {
        if (data.size < 2) return
        val flags = data[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) data[1].toInt() and 0xFF
                  else ByteBuffer.wrap(data, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (bpm in (30..250)) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    private fun parseBattery(p: ByteArray) {
        if (p.isEmpty()) return
        // ZeppOS battery reply: [0x04 or 0x10][?] [level] [status] ...
        if (p[0].toInt() != 0x04 && p[0].toInt() != 0x10) return

        val level = if (p.size >= 3) p[2].toInt() and 0xFF else return
        val charging = p.size >= 4 && p[3].toInt() == 1
        Timber.i(
            "MiBand7: battery update -> $level%, charging=$charging (raw: ${
                p.joinToString {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            })"
        )
        scope.launch { _events.emit(DeviceEvent.Battery(level, charging)) }
    }

    private fun parseActivity(p: ByteArray) {
        if (p.isEmpty()) return

        when (p[0].toInt()) {
            0x07 -> { // Realtime notification
                // Format: [0x07] [status] [steps:4] [dist:4] [cal:4] = 14 bytes
                if (p.size < 14) return
                val buf = ByteBuffer.wrap(p, 2, 12).order(ByteOrder.LITTLE_ENDIAN)
                val steps = buf.int
                val dist = buf.int
                val cal = buf.int
                Timber.d("MiBand7: activity notification -> steps=$steps, dist=$dist, kcal=$cal")
                scope.launch { _events.emit(DeviceEvent.Steps(steps, cal, dist.toFloat())) }
            }

            0x04, 0x10 -> { // Reply to GET command (0x10 is standard for many Huami devices)
                // Format: [0x04/0x10] [status] [?] [steps:4] [dist:4] [cal:4] = 15 bytes
                // OR Format: [0x04/0x10] [?] [steps:4] [dist:4] [cal:4] = 14 bytes
                val offset = if (p.size >= 15) 3 else 2
                if (p.size < offset + 12) return
                val buf = ByteBuffer.wrap(p, offset, 12).order(ByteOrder.LITTLE_ENDIAN)
                val steps = buf.int
                val dist = buf.int
                val cal = buf.int
                Timber.i("MiBand7: activity reply -> steps=$steps, dist=$dist, kcal=$cal")
                scope.launch { _events.emit(DeviceEvent.Steps(steps, cal, dist.toFloat())) }
            }
        }
    }

    private fun parseSpo2(p: ByteArray) {
        if (p.size < 2 || p[0].toInt() and 0xFF != 0x01) return
        val v = p[1].toInt() and 0xFF
        if (v in (50..100)) scope.launch { _events.emit(DeviceEvent.SpO2(v)) }
    }

    private fun parseSleep(p: ByteArray) {
        if (p.size < 2) return
        // ZeppOS sleep event: [0x06] [0x01=Asleep, 0x00=Awake]
        val stage = when (p[1].toInt() and 0xFF) {
            0x01 -> SleepStage.DEEP // Simplified
            0x00 -> SleepStage.AWAKE
            else -> null
        } ?: return
        scope.launch { _events.emit(DeviceEvent.SleepData(stage)) }
    }

    // ── Commands ──────────────────────────────────────────────────────

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        // ZeppOS Find Band: START=0x03, STOP=0x06
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x03))
        if (pattern == VibratePattern.SHORT || pattern == VibratePattern.DOUBLE) {
            delay(600)
            writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x06))
        }
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        // ZeppOS HR: SET=0x04, START=0x01, STOP=0x00
        val mode = if (continuous) 0x01.toByte() else 0x00.toByte()
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_HEARTRATE, byteArrayOf(0x04, mode))
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        val timestamp = Calendar.getInstance()
        val zoneId = ZoneId.systemDefault()
        val rules = zoneId.rules

        val p = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x05.toByte()) // CMD_SET_TIME
            putShort(timestamp.get(Calendar.YEAR).toShort())
            put((timestamp.get(Calendar.MONTH) + 1).toByte())
            put(timestamp.get(Calendar.DATE).toByte())
            put(timestamp.get(Calendar.HOUR_OF_DAY).toByte())
            put(timestamp.get(Calendar.MINUTE).toByte())
            put(timestamp.get(Calendar.SECOND).toByte())
            put((timestamp.get(Calendar.DAY_OF_WEEK) - 1).toByte())
            put((timestamp.get(Calendar.MILLISECOND) / 1000.0 * 256.0).toInt().toByte())
            if (rules.isDaylightSavings(Instant.now())) {
                put(0x08.toByte())
            } else {
                put(0x00.toByte())
            }
            put((rules.getOffset(Instant.now()).totalSeconds / (60 * 15)).toByte())
        }.array()
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_TIME, p)
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        Timber.i("MiBand7: FORCE Requesting battery info…")
        // ZeppOS Battery: REQUEST=0x03
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_BATTERY, byteArrayOf(0x03))
    }

    private suspend fun requestServices(gatt: BluetoothGatt) {
        Timber.d("MiBand7: Requesting services list…")
        // ZeppOS Services: REQUEST=0x01
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SERVICES, byteArrayOf(0x01))
    }

    private suspend fun requestDeviceInfo(gatt: BluetoothGatt) {
        Timber.d("MiBand7: Requesting device info…")
        // ZeppOS Device Info: REQUEST=0x01
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_DEVICE_INFO, byteArrayOf(0x01))
    }

    private suspend fun requestCurrentSteps(gatt: BluetoothGatt) {
        Timber.d("MiBand7: Requesting current steps…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_STEPS, byteArrayOf(0x03))
    }

    private suspend fun enableRealtimeSteps(gatt: BluetoothGatt) {
        Timber.d("MiBand7: Enabling realtime steps…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_STEPS, byteArrayOf(0x05, 0x01))
    }

    private fun parseDeviceInfo(p: ByteArray) {
        Timber.i(
            "MiBand7: Device Info -> ${
                p.joinToString("") {
                    it.toUByte().toString(16).padStart(2, '0')
                }
            }"
        )
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        setHeartRateMonitoring(gatt, continuous = true)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x01))
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        setHeartRateMonitoring(gatt, continuous = false)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x00))
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) =
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x01))

    override suspend fun dismissAlarm(gatt: BluetoothGatt) =
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x00))

    override suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean) {
        // Not implemented for Mi Band 7 (ZeppOS)
    }

    // ── BLE Write Helpers ─────────────────────────────────────────────

    private suspend fun writeChunked(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) =
        writeMutex.withLock {
            val char = chunkedWrite ?: return@withLock

            val handle = handleSeq++
            Timber.d(
                "MiBand7: writeChunked endpoint=0x${
                    endpoint.toString(16).padStart(4, '0')
                }, handle=$handle, size=${payload.size}"
            )

        val key =
            if (isAuthenticated && Huami2021Chunked.isEncrypted(endpoint)) decoder.sessionKey else null

            val seq = if (key != null) encryptedSeq++ else 0

        val packets = Huami2021Chunked.encode(
            handle = handle,
            endpoint = endpoint,
            payload = payload,
            mtu = negotiatedMtu,
            sessionKey = key,
            encryptedSeq = seq
        )

        val canWriteWithoutResponse =
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        for (packet in packets) {
            Timber.v(
                "MiBand7: writing packet to endpoint 0x${endpoint.toString(16)}: ${
                    packet.joinToString {
                        it.toUByte().toString(16).padStart(2, '0')
                    }
                }"
            )
            writeRaw(gatt, char, packet, noResponse = canWriteWithoutResponse)
            if (packets.size > 1) delay(50) // Increased gap for stability
        }
    }

    private suspend fun writeRaw(
        gatt: BluetoothGatt, 
        char: BluetoothGattCharacteristic?, 
        data: ByteArray, 
        noResponse: Boolean = false
    ) {
        if (char == null) return
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE 
                        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, data, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("MiBand7: writeCharacteristic failed status=$status")
            } else if (!noResponse) {
                awaitCharacteristicWrite?.invoke()
            }
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(char) && !noResponse) {
                awaitCharacteristicWrite?.invoke()
            } else if (!noResponse) {
                Timber.e("MiBand7: writeCharacteristic returned false")
            }
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
        authContinuation?.resume(value = false)
        cleanupAuth()
        awaitDescriptorWrite = null
        awaitCharacteristicWrite = null
        scope.cancel()
    }
}
