package nodomain.freeyourgadget.gadgetbridge.ble.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.experimental.xor

/**
 * Protocol implementation for Xiaomi Smart Band 7 (ZeppOS).
 *
 * ## Authentication (ECDH B-163 + AES session key)
 *
 * ZeppOS devices do **not** use the plain AES-key auth of older Mi Bands.  Instead they
 * perform a full Elliptic-Curve Diffie-Hellman key-exchange over the NIST B-163 curve,
 * then derive a symmetric session key from the shared secret and the 16-byte pairing key
 * stored in your Xiaomi account.  All subsequent communication on "encrypted" endpoints
 * (battery, configuration, …) is protected with AES-128/ECB using a handle-derived
 * per-message key.
 *
 * ### Handshake steps
 * 1. **Phone → Band** (endpoint 0x0002): phone's B-163 public key
 *    `[CMD=0x04][0x02][0x00][0x02][pubKey:48]`
 * 2. **Band → Phone** (endpoint 0x0082): band's public key + random nonce
 *    `[0x10][CMD=0x04][status=0x01][nonce:16][bandPubKey:48]`
 * 3. Phone computes ECDH shared secret, derives session key:
 *    `sessionKey[i] = shared[i+8] XOR authKey[i]`
 * 4. **Phone → Band** (endpoint 0x0082): double-encrypted nonces
 *    `[CMD=0x05][AES(authKey, nonce):16][AES(sessionKey, nonce):16]`
 * 5. **Band → Phone** (endpoint 0x0082): auth result
 *    `[0x10][CMD=0x05][status=0x01]` = success
 *
 * ## BLE characteristics
 * | Role        | UUID suffix | Direction        |
 * |-------------|-------------|------------------|
 * | Write       | 0x0016      | Phone → Band     |
 * | Notify/Read | 0x0017      | Band → Phone     |
 * | Heart Rate  | 0x2A37      | Band → Phone (standard GATT HR service) |
 *
 * ## Obtaining the auth key
 * Use [xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)
 * with your Xiaomi/Mi account to extract the 16-byte hex auth key for your band.
 */
@SuppressLint("MissingPermission")
class MiBand7Protocol(
    private val authKey: ByteArray,
) : DeviceProtocol {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<DeviceEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: Flow<DeviceEvent> = _events.asSharedFlow()

    // ── GATT characteristic handles ───────────────────────────────────────────

    companion object {
        /** Chunked-transfer write characteristic (phone → band). */
        val UUID_CHAR_CHUNKED_WRITE: UUID  = UUID.fromString("00000016-0000-3512-2118-0009af100700")

        /** Chunked-transfer notify characteristic (band → phone). */
        val UUID_CHAR_CHUNKED_READ: UUID   = UUID.fromString("00000017-0000-3512-2118-0009af100700")

        /** Standard GATT Heart Rate service UUID. */
        val UUID_SERVICE_HR: UUID          = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        /** Standard GATT Heart Rate Measurement characteristic UUID. */
        val UUID_CHAR_HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        /** Standard GATT Client Characteristic Configuration Descriptor UUID. */
        val UUID_CCCD: UUID                = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Standard GATT battery level characteristic UUID. */
        private val UUID_CHAR_STD_BATTERY: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** Standard GATT battery service UUID. */
        private val UUID_SERVICE_STD_BATTERY: UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        /** Classic activity-control characteristic (fetch metadata path). */
        val UUID_CHAR_ACTIVITY_CONTROL: UUID =
            UUID.fromString("00000004-0000-3512-2118-0009af100700")

        /** Classic activity-data characteristic (fetch bulk-data path). */
        val UUID_CHAR_ACTIVITY_DATA: UUID =
            UUID.fromString("00000005-0000-3512-2118-0009af100700")

        /** Raw sensor control characteristic (classic writes start/stop streaming). */
        val UUID_CHAR_RAW_SENSOR_CONTROL: UUID =
            UUID.fromString("00000001-0000-3512-2118-0009af100700")

        /** Raw sensor data characteristic (accelerometer notifications). */
        val UUID_CHAR_RAW_SENSOR_DATA: UUID =
            UUID.fromString("00000002-0000-3512-2118-0009af100700")

        /** Start streaming: the band replies `10:01:03:05`. */
        private val RAW_SENSOR_START_1 = byteArrayOf(0x01, 0x03, 0x19)

        /** Start streaming (part 2): the band replies `10:01:01:05`. */
        private val RAW_SENSOR_START_2 = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x19)

        /** Start streaming (part 3): the band replies `10:02:01`. */
        private val RAW_SENSOR_START_3 = byteArrayOf(0x02)

        /** Stop streaming: the band replies `10:03:01`. */
        private val RAW_SENSOR_STOP = byteArrayOf(0x03)

        /** How long to wait for the ECDH handshake to complete before giving up. */
        private const val AUTH_TIMEOUT_MS = 30_000L

        /** Fetch handshake timeouts (the band can answer minutes late). */
        private const val FETCH_START_TIMEOUT_MS = 60_000L
        private const val FETCH_DATA_TIMEOUT_MS = 180_000L
        private const val FETCH_ACK_TIMEOUT_MS = 30_000L

        /** Size of one sleep-session record (4-byte timestamp + 590 bytes detail). */
        private const val SLEEP_RECORD_SIZE = 594

        /** How often to re-send the ECDH public key while waiting for the band's reply. */
        private const val ECDH_RETRY_MS = 5_000L

        /**
         * Default ASCII auth key used by some ZeppOS devices before a custom key is set.
         * The bytes spell "0123456789@ABCDE".
         */
        private val DEFAULT_AUTH_KEY = byteArrayOf(
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45,
        )
    }

    private var chunkedWrite: BluetoothGattCharacteristic? = null
    private var chunkedRead: BluetoothGattCharacteristic? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var activityControl: BluetoothGattCharacteristic? = null
    private var activityData: BluetoothGattCharacteristic? = null
    private var rawSensorControl: BluetoothGattCharacteristic? = null
    private var rawSensorData: BluetoothGattCharacteristic? = null
    private var sessionGatt: BluetoothGatt? = null

    /** True while raw accelerometer streaming is requested (re-enabled every 10 s). */
    @Volatile
    private var rawSensorStreaming = false
    private var rawSensorJob: Job? = null

    /** Alarm vibration loop (re-triggered until dismissed). */
    @Volatile
    private var alarmActive = false
    private var alarmJob: Job? = null

    /** One-shot flag for logging the first raw packet of a session. */
    @Volatile
    private var rawStreamLogged = false

    private val decoder = Huami2021Chunked.Decoder()

    /** Monotonically increasing handle byte for outgoing chunked messages. */
    @Volatile
    private var handleSeq: Byte = 1

    /** Monotonically increasing sequence counter embedded in encrypted payloads. */
    @Volatile
    private var encryptedSeq: Int = 0

    /** Serialises all GATT write operations (Android only allows one in-flight at a time). */
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

    // Auth-handshake state (only valid during the handshake coroutine)
    @Volatile private var authContinuation: Continuation<Boolean>? = null
    @Volatile
    private var authAttempts: Int = 0
    @Volatile
    private var pendingEncKey: ByteArray? = null
    @Volatile
    private var pendingEncSeq: Int = 0
    @Volatile
    private var privateEC: ByteArray? = null

    // GATT operation completions provided by BleManager
    private var awaitDescriptorWrite: (suspend () -> Unit)? = null
    private var awaitCharacteristicWrite: (suspend () -> Unit)? = null

    // Sleep-history fetch state (Huami fetch protocol, guarded by fetchMutex)
    private val fetchMutex = Mutex()

    private enum class FetchPhase { IDLE, AWAIT_START, COLLECTING, AWAIT_ACK }

    @Volatile
    private var fetchPhase = FetchPhase.IDLE
    @Volatile
    private var fetchMeta: CompletableDeferred<ByteArray>? = null
    private var fetchBuffer = ByteArrayOutputStream()
    private var fetchExpected = 0
    private var fetchLastCounter = -1

    /** Endpoints the band advertised in its service list (3-byte [ep_lo, ep_hi, flags] entries). */
    private val supportedEndpoints = mutableSetOf<Short>()

    /** Auth key to use: the provided key if non-zero, otherwise the hardcoded default. */
    private val effectiveAuthKey: ByteArray
        get() = if (authKey.all { it == 0.toByte() }) DEFAULT_AUTH_KEY else authKey

    // ── Initialisation ────────────────────────────────────────────────────────

    override suspend fun initialize(
        gatt: BluetoothGatt,
        awaitDescriptorWrite: suspend () -> Unit,
        awaitCharacteristicWrite: suspend () -> Unit,
    ): Boolean {
        if (isInitialized) {
            Timber.w("MiBand7: already initialized, skipping")
            return true
        }
        isInitialized = true
        this.awaitDescriptorWrite = awaitDescriptorWrite
        this.awaitCharacteristicWrite = awaitCharacteristicWrite
        sessionGatt = gatt
        rawStreamLogged = false

        Timber.i("MiBand7: initializing ${gatt.device.address} (MTU=$negotiatedMtu)")

        // Locate chunked-transfer characteristics (they can appear in any service)
        for (svc in gatt.services) {
            if (chunkedWrite == null) chunkedWrite = svc.getCharacteristic(UUID_CHAR_CHUNKED_WRITE)
            if (chunkedRead  == null) chunkedRead  = svc.getCharacteristic(UUID_CHAR_CHUNKED_READ)
            if (activityControl == null) activityControl =
                svc.getCharacteristic(UUID_CHAR_ACTIVITY_CONTROL)
            if (activityData == null) activityData = svc.getCharacteristic(UUID_CHAR_ACTIVITY_DATA)
            if (rawSensorControl == null) rawSensorControl =
                svc.getCharacteristic(UUID_CHAR_RAW_SENSOR_CONTROL)
            if (rawSensorData == null) rawSensorData =
                svc.getCharacteristic(UUID_CHAR_RAW_SENSOR_DATA)
        }
        Timber.i(
            "MiBand7: characteristics rawCtl=%s rawData=%s actCtl=%s actData=%s",
            rawSensorControl != null, rawSensorData != null,
            activityControl != null, activityData != null
        )
        hrChar = gatt.getService(UUID_SERVICE_HR)?.getCharacteristic(UUID_CHAR_HR_MEASUREMENT)
        val stdBatteryChar = gatt.getService(UUID_SERVICE_STD_BATTERY)
            ?.getCharacteristic(UUID_CHAR_STD_BATTERY)

        if (chunkedWrite == null || chunkedRead == null) {
            Timber.e("MiBand7: chunked-transfer characteristics not found — wrong device?")
            return false
        }

        delay(600)

        // Subscribe to chunked-read notifications (band → phone data path)
        if (!enableNotification(gatt, chunkedRead!!)) return false
        awaitDescriptorWrite()
        delay(200)

        // Subscribe to chunked-write notifications if the characteristic also notifies
        // (used for ACKs from the band acknowledging our writes)
        if ((chunkedWrite!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            if (enableNotification(gatt, chunkedWrite!!)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        // Subscribe to the standard HR service (provides raw BPM without using a chunked command)
        hrChar?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        // Subscribe to standard GATT battery service as a fallback
        stdBatteryChar?.let {
            Timber.i("MiBand7: standard GATT battery service found — subscribing")
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        // Subscribe to classic activity control/data (sleep-history fetch path)
        activityControl?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }
        activityData?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        // Subscribe to raw accelerometer data (Sleep as Android actigraphy path).
        // Streaming itself is started/stopped via setRawSensorEnabled().
        rawSensorControl?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }
        rawSensorData?.let {
            if (enableNotification(gatt, it)) {
                awaitDescriptorWrite()
                delay(200)
            }
        }

        delay(600)

        return try {
            withTimeout(AUTH_TIMEOUT_MS) { runEcdhHandshake(gatt) }
        } catch (e: Exception) {
            Timber.e(
                e,
                "MiBand7: ECDH handshake failed after %d attempt(s) — the band never completed the key exchange. " +
                        "Usual causes: band still connected to another phone (Zepp app), stale/incorrect auth key, " +
                        "or the band needs to be unbound/factory-reset.",
                authAttempts
            )
            authContinuation?.resume(false)
            authContinuation = null
            false
        }
    }

    // ── ECDH handshake ────────────────────────────────────────────────────────

    private suspend fun runEcdhHandshake(gatt: BluetoothGatt): Boolean {
        // Generate ephemeral B-163 key pair
        val priv = ByteArray(ECDH_B163.ECC_PRV_KEY_SIZE).apply { SecureRandom().nextBytes(this) }
        privateEC = priv
        val pub =
            ECDH_B163.generatePublic(priv) ?: throw Exception("B-163 public key generation failed")

        // Send our public key to the ZeppOS auth endpoint (0x0002)
        // Format: [CMD=0x04][0x02][0x00][0x02][pubKey:48]
        val payload = ByteBuffer.allocate(4 + ECDH_B163.ECC_PUB_KEY_SIZE).apply {
            put(Huami2021Chunked.AUTH_CMD_PUB_KEY)
            put(0x02.toByte())
            put(0x00.toByte())
            put(0x02.toByte())
            put(pub)
        }.array()

        Timber.i(
            "MiBand7: using auth key %s…%s",
            effectiveAuthKey.copyOfRange(0, 2).toHex(),
            effectiveAuthKey.copyOfRange(effectiveAuthKey.size - 2, effectiveAuthKey.size).toHex()
        )

        // The band sometimes ignores the first ECDH key (fresh GATT link, band still
        // settling, or it is busy with another phone).  Re-send the *same* key every few
        // seconds until it answers or the overall AUTH_TIMEOUT_MS expires.
        //
        // The request goes to ENDPOINT_AUTH (0x0082) — the same endpoint the band uses
        // for its responses.  Writing to 0x0002 gets ACKed at the transport level but
        // never triggers the key exchange.
        while (true) {
            authAttempts++
            writeChunked(gatt, Huami2021Chunked.ENDPOINT_AUTH, payload)
            Timber.i(
                "MiBand7: sent B-163 public key (attempt %d) — awaiting band response…",
                authAttempts
            )

            val result = withTimeoutOrNull(ECDH_RETRY_MS) {
                suspendCancellableCoroutine { cont ->
                    authContinuation = cont
                    cont.invokeOnCancellation {
                        if (authContinuation === cont) authContinuation = null
                    }
                }
            }
            if (result != null) return result

            Timber.w("MiBand7: no band response (attempt %d) — re-sending ECDH key…", authAttempts)
            delay(500)
        }
    }

    // ── Incoming data dispatch ────────────────────────────────────────────────

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val uuid = characteristic.uuid
        Timber.v("MiBand7: notify %s  [%s]", uuid, value.toHex())

        return when (uuid) {
            UUID_CHAR_CHUNKED_READ, UUID_CHAR_CHUNKED_WRITE -> {
                when {
                    value.isEmpty() -> false
                    value[0] == 0x03.toByte() -> {
                        // Data packet from the band — reassemble and dispatch
                        decoder.decode(value)?.let { result ->
                            // ACK to the band goes to the WRITE characteristic (0x0016)
                            if (result.needsAck) sendAck(gatt, result.handle, result.count)
                            result.message?.let { msg ->
                                dispatch(gatt, msg.endpoint, msg.payload)
                            }
                        }
                        true
                    }

                    value[0] == 0x04.toByte() -> {
                        // ACK from the band acknowledging one of our writes — nothing to do
                        true
                    }

                    else -> false
                }
            }

            UUID_CHAR_HR_MEASUREMENT -> {
                parseStdHr(value)
                true
            }

            UUID_CHAR_STD_BATTERY -> {
                if (value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    Timber.i("MiBand7: standard GATT battery → %d%%", level)
                    scope.launch { _events.emit(DeviceEvent.Battery(level)) }
                }
                true
            }

            UUID_CHAR_ACTIVITY_CONTROL -> {
                // Fetch metadata also arrives here (raw Huami framing, no chunk header)
                handleFetchControl(value)
                true
            }

            UUID_CHAR_ACTIVITY_DATA -> {
                // Fetch bulk data: raw [counter][payload…] frames, no chunk header
                handleFetchData(value)
                true
            }

            UUID_CHAR_RAW_SENSOR_CONTROL -> {
                // Replies to start/stop streaming (e.g. [0x10, 0x01, …]) — informational
                Timber.d("MiBand7: raw sensor control ← [%s]", value.toHex())
                true
            }

            UUID_CHAR_RAW_SENSOR_DATA -> {
                parseRawSensorData(value)
                true
            }

            else -> false
        }
    }

    /**
     * Send a chunked-transfer ACK to the band.
     *
     * ACKs go to the *read* characteristic (0x0017), which supports
     * write-without-response — matching Gadgetbridge on this device family.
     *
     * ACK format: `[0x04][0x00][handle][0x01][count]`
     */
    private fun sendAck(gatt: BluetoothGatt, handle: Byte, count: Byte) {
        Timber.v(
            "MiBand7: sending ACK  handle=0x%02x count=%d",
            handle.toInt() and 0xFF,
            count.toInt() and 0xFF
        )
        val ack = byteArrayOf(0x04, 0x00, handle, 0x01, count)
        scope.launch {
            // Write to chunkedRead (0x0017) — the band's ACK listener
            writeRaw(gatt, chunkedRead, ack, noResponse = true)
        }
    }

    private fun dispatch(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) {
        Timber.d(
            "MiBand7: dispatch endpoint=0x%04x size=%d  [%s]",
            endpoint.toInt() and 0xFFFF, payload.size, payload.toHex()
        )

        when (endpoint) {
            Huami2021Chunked.ENDPOINT_SERVICES -> {
                // Service list: [0x04][count:2 LE][ep_lo, ep_hi, flags]…
                if (payload.size >= 3 && payload[0].toInt() and 0xFF == Huami2021Chunked.SERVICES_CMD_RET_LIST.toInt()) {
                    val count =
                        (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
                    val endpoints = (0 until count).mapNotNull { i ->
                        val o = 3 + i * 3
                        if (o + 2 < payload.size) {
                            val ep =
                                ((payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8)).toShort()
                            supportedEndpoints.add(ep)
                            "0x%04x".format(ep.toInt() and 0xFFFF)
                        } else null
                    }
                    Timber.i("MiBand7: supported services (%d): %s", count, endpoints)
                    if (!isInitializingServices) {
                        isInitializingServices = true
                        scope.launch { postAuthSetup(gatt) }
                    }
                } else {
                    Timber.d("MiBand7: unexpected services payload [%s]", payload.toHex())
                }
            }

            Huami2021Chunked.ENDPOINT_CONNECTION -> {
                when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
                    Huami2021Chunked.CONNECTION_CMD_PING.toInt() -> {
                        // Band keepalive ping — must be answered or the link is dropped
                        Timber.d("MiBand7: connection PING — replying PONG")
                        scope.launch {
                            writeChunked(
                                gatt, Huami2021Chunked.ENDPOINT_CONNECTION,
                                byteArrayOf(Huami2021Chunked.CONNECTION_CMD_PONG)
                            )
                        }
                    }

                    Huami2021Chunked.CONNECTION_CMD_MTU_RESPONSE.toInt() -> {
                        if (payload.size >= 3) {
                            val deviceMtu = (payload[1].toInt() and 0xFF) or
                                    ((payload[2].toInt() and 0xFF) shl 8) + 3
                            Timber.i("MiBand7: device announced chunked MTU: %d", deviceMtu)
                        }
                    }

                    else -> Timber.d("MiBand7: unhandled connection payload [%s]", payload.toHex())
                }
            }

            Huami2021Chunked.ENDPOINT_AUTH,
            Huami2021Chunked.ENDPOINT_AUTH_ZEPPOS -> {
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
                parseBattery(payload)
            }

            Huami2021Chunked.ENDPOINT_STEPS -> {
                parseActivity(payload)
            }

            Huami2021Chunked.ENDPOINT_SPO2 -> {
                parseSpo2(payload)
            }

            Huami2021Chunked.ENDPOINT_DEVICE_INFO -> {
                Timber.i("MiBand7: device info  [%s]", payload.toHex())
            }

            Huami2021Chunked.ENDPOINT_ACTIVITY_FETCH -> {
                // Fetch metadata over the chunked path (same layout as classic 0x0004)
                handleFetchControl(payload)
            }

            Huami2021Chunked.ENDPOINT_NOTIFICATION -> {
                when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
                    Huami2021Chunked.NOTIF_CMD_CAPABILITIES_RESPONSE.toInt() -> {
                        Timber.d("MiBand7: notification capabilities [%s]", payload.toHex())
                        notifCapWaiter?.complete(payload)
                    }

                    Huami2021Chunked.NOTIF_CMD_REPLY_ACK.toInt(),
                    Huami2021Chunked.NOTIF_CMD_REPLY.toInt() ->
                        Timber.i("MiBand7: notification ack [%s]", payload.toHex())

                    Huami2021Chunked.NOTIF_CMD_ICON_REQUEST.toInt() -> {
                        // `[0x10][package][00][format][width:2 LE][height:2 LE]`.
                        // Serving icons needs the file-transfer session (TGA upload +
                        // ICON_REQUEST_ACK) — not implemented yet, so parse + log the
                        // exact parameters the band asks for.
                        var pkgEnd = -1
                        for (i in 1 until payload.size) {
                            if (payload[i] == 0x00.toByte()) {
                                pkgEnd = i
                                break
                            }
                        }
                        val pkg = if (pkgEnd > 1) {
                            payload.copyOfRange(1, pkgEnd).toString(Charsets.UTF_8)
                        } else {
                            "?"
                        }
                        val o = if (pkgEnd > 1) pkgEnd + 1 else 1
                        // Tail: [format][width:2 LE][height:2 LE] = 5 bytes at o..o+4.
                        val fmt = payload.getOrNull(o)?.toInt()?.and(0xFF)
                        val w = if (o + 5 <= payload.size) {
                            (payload[o + 1].toInt() and 0xFF) or ((payload[o + 2].toInt() and 0xFF) shl 8)
                        } else null
                        val h = if (o + 5 <= payload.size) {
                            (payload[o + 3].toInt() and 0xFF) or ((payload[o + 4].toInt() and 0xFF) shl 8)
                        } else null
                        Timber.i(
                            "MiBand7: notification icon request pkg=%s format=0x%02x %dx%d (unanswered — file transfer not implemented)",
                            pkg, fmt ?: -1, w ?: -1, h ?: -1
                        )
                    }

                    else -> Timber.d(
                        "MiBand7: unhandled notification payload [%s]",
                        payload.toHex()
                    )
                }
            }

            Huami2021Chunked.ENDPOINT_CONFIG -> {
                when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
                    Huami2021Chunked.CONFIG_CMD_ACK.toInt() ->
                        Timber.i(
                            "MiBand7: config SET ack, status=0x%02x",
                            payload.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                        )

                    Huami2021Chunked.CONFIG_CMD_RESPONSE.toInt() ->
                        Timber.d("MiBand7: config response: [%s]", payload.toHex())

                    else -> Timber.d("MiBand7: unhandled config payload [%s]", payload.toHex())
                }
            }

            else -> {
                Timber.d(
                    "MiBand7: unhandled endpoint 0x%04x  (%d bytes)",
                    endpoint.toInt() and 0xFFFF, payload.size
                )
            }
        }
    }

    // ── Auth response handler ─────────────────────────────────────────────────

    private suspend fun handleAuth(gatt: BluetoothGatt, payload: ByteArray) {
        if (payload.isEmpty() || payload[0] != Huami2021Chunked.AUTH_RESP_PREFIX) return

        when (payload[1]) {
            Huami2021Chunked.AUTH_CMD_PUB_KEY -> {
                // Band's public key response: [0x10][0x04][status][nonce:16][bandPub:48]
                if (payload.size < 67) {
                    Timber.e("MiBand7: public key response too short (%d bytes)", payload.size)
                    failAuth(); return
                }
                if (payload[2] != Huami2021Chunked.AUTH_SUCCESS) {
                    Timber.e(
                        "MiBand7: public key exchange failed (status=0x%02x)",
                        payload[2].toInt() and 0xFF
                    )
                    failAuth(); return
                }

                try {
                    // The band can answer a stale/duplicated key request even when this
                    // instance is not mid-handshake — never NPE on a missing private key.
                    val priv = privateEC ?: run {
                        Timber.e("MiBand7: auth response received but no handshake in progress — ignoring")
                        return
                    }
                    val remoteNonce = payload.copyOfRange(3, 19)   // 16 bytes
                    val remotePub = payload.copyOfRange(19, 67)  // 48 bytes

                    val shared = ECDH_B163.generateShared(priv, remotePub)
                        ?: throw Exception("ECDH shared-secret computation failed")

                    // First 4 bytes of the shared secret seed the encrypted-sequence counter
                    pendingEncSeq = ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN).int

                    // Session key: shared[8..23] XOR authKey
                    val sessionKey = ByteArray(16) { i -> (shared[i + 8] xor effectiveAuthKey[i]) }
                    pendingEncKey = sessionKey

                    // Reply with double-encrypted nonces on the auth endpoint (0x0082)
                    val enc1 = aesEcbEncrypt(effectiveAuthKey, remoteNonce)  // AES(authKey, nonce)
                    val enc2 =
                        aesEcbEncrypt(sessionKey, remoteNonce)        // AES(sessionKey, nonce)
                    val resp = byteArrayOf(Huami2021Chunked.AUTH_CMD_SESSION_KEY) + enc1 + enc2

                    writeChunked(gatt, Huami2021Chunked.ENDPOINT_AUTH, resp)
                    Timber.d("MiBand7: sent double-encrypted nonces")
                } catch (e: Exception) {
                    Timber.e(e, "MiBand7: session key derivation failed")
                    failAuth()
                }
            }

            Huami2021Chunked.AUTH_CMD_SESSION_KEY -> {
                // Auth result: [0x10][0x05][status]
                val success = payload.size >= 3 && payload[2] == Huami2021Chunked.AUTH_SUCCESS
                if (success) {
                    Timber.i("MiBand7: authentication SUCCESS ✓")
                    isAuthenticated = true
                    decoder.sessionKey = pendingEncKey
                    encryptedSeq = pendingEncSeq

                    scope.launch {
                        _events.emit(DeviceEvent.DeviceReady)
                        // Phase 2: request the supported-services list.  The band only
                        // answers data requests (battery, steps, …) after this exchange,
                        // so the actual device setup runs in postAuthSetup() when the
                        // list arrives.
                        requestServices(gatt)
                    }
                } else {
                    val code = if (payload.size >= 3) payload[2].toInt() and 0xFF else -1
                    if (code == 0x25) {
                        Timber.e(
                            "MiBand7: authentication FAILED — status 0x25: WRONG AUTH KEY. " +
                                    "Re-extract the beaconkey for THIS band (matching its MAC) from the " +
                                    "Xiaomi account it is actually paired with, and paste it in Settings."
                        )
                    } else {
                        Timber.e("MiBand7: authentication FAILED (status=0x%02x)", code)
                    }
                }
                authContinuation?.resume(success)
                cleanupAuth()
            }

            else -> {
                Timber.w(
                    "MiBand7: unknown auth command 0x%02x  [%s]",
                    payload[1].toInt() and 0xFF, payload.toHex()
                )
                authContinuation?.resume(false)
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

    // ── Post-auth device setup ────────────────────────────────────────────────

    /**
     * Called once after the band sends its services list (endpoint 0x0001).
     * Performs initial device configuration and starts the periodic polling loop.
     */
    private suspend fun postAuthSetup(gatt: BluetoothGatt) {
        delay(2_000)
        requestDeviceInfo(gatt)
        delay(1_000)
        syncTime(gatt)
        delay(1_000)
        enableRealtimeSteps(gatt)
        delay(1_000)

        // Live HR for the dashboard: START once, then CONTINUE every second to keep
        // the band streaming (Gadgetbridge pattern — without CONTINUE the stream stalls).
        // Runs as a child of the protocol scope, so it dies with the connection.
        setHeartRateMonitoring(gatt, continuous = true)
        scope.launch {
            while (true) {
                continueHeartRateStreaming(gatt)
                delay(1_000)
            }
        }

        // Poll battery and steps periodically.  Battery is encrypted so it must be polled;
        // steps are also polled as a fallback alongside real-time push notifications.
        while (true) {
            requestBattery(gatt)
            delay(1_000)
            requestCurrentSteps(gatt)
            delay(30_000)
        }
    }

    /** Keep-alive for the HR stream: fetches the next batch of 0x2A37 samples. */
    private suspend fun continueHeartRateStreaming(gatt: BluetoothGatt) {
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_HEARTRATE, byteArrayOf(0x04, 0x02))
    }

    // ── Sleep history fetch (Huami fetch protocol) ────────────────────────────
    //
    // Handshake (control writes go over chunked endpoint 0x004b, encrypted):
    //   1. Phone → band: [0x01][0x48][timestamp 8B]          (sleep sessions since …)
    //   2. Band → phone: [0x10][0x01][status][len u32][startTS 8B]
    //   3. Phone → band: [0x02]                              (begin transfer)
    //      Band → phone: [counter][payload…] on classic 0x0005 until `len` bytes
    //   4. Band → phone: [0x10][0x02][status]([crc32])
    //   5. Phone → band: [0x03][0x01 drop | 0x09 keep]
    //   6. Band → phone: [0x10][0x03][…]                     (done)
    //
    // Control responses arrive on chunked 0x004b and/or classic 0x0004 — both feed
    // the same metadata handler. Bulk data arrives on classic 0x0005 (raw frames).

    /**
     * Pull sleep sessions recorded since [sinceMs] (epoch millis).
     * Returns parsed sessions (possibly empty). Protocol failures yield an empty
     * list; coroutine cancellation propagates.
     */
    suspend fun fetchSleepSessions(sinceMs: Long): List<SleepSessionRecord> =
        fetchMutex.withLock {
            if (!isAuthenticated) {
                Timber.w("MiBand7: sleep fetch requested while not authenticated")
                return emptyList()
            }
            try {
                val result = fetchRawData(
                    Huami2021Chunked.FETCH_TYPE_SLEEP_SESSION,
                    sinceMs
                ) ?: return emptyList()
                parseSleepRecords(result.bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "MiBand7: sleep fetch failed")
                emptyList()
            } finally {
                fetchPhase = FetchPhase.IDLE
                fetchMeta = null
                // Let the band's fetch machine settle before the next dialog.
                delay(3_000)
            }
        }

    /**
     * Pull historical SpO2 samples (fetch type 0x25) recorded since [sinceMs].
     * Record layout (Gadgetbridge): `[version=0x02][timestamp u32][spo2 1B][60B]…`.
     */
    suspend fun fetchSpo2History(sinceMs: Long): List<SpO2SampleRecord> =
        fetchMutex.withLock {
            if (!isAuthenticated) return emptyList()
            try {
                val result = fetchRawData(Huami2021Chunked.FETCH_TYPE_SPO2_NORMAL, sinceMs)
                    ?: return emptyList()
                parseSpo2Records(result.bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "MiBand7: SpO2 fetch failed")
                emptyList()
            } finally {
                fetchPhase = FetchPhase.IDLE
                fetchMeta = null
                // Let the band's fetch machine settle before the next dialog.
                delay(3_000)
            }
        }

    /**
     * Pull automatic stress samples (fetch type 0x13) recorded since [sinceMs].
     * Layout: minute slots from the transfer start timestamp; `0xFF` = no data
     * (advance a minute), otherwise the stress score 0–100 for that minute.
     */
    suspend fun fetchStressHistory(sinceMs: Long): List<StressSampleRecord> =
        fetchMutex.withLock {
            if (!isAuthenticated) return emptyList()
            try {
                val result = fetchRawData(Huami2021Chunked.FETCH_TYPE_STRESS_AUTO, sinceMs)
                    ?: return emptyList()
                parseStressRecords(result.bytes, result.startTsMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "MiBand7: stress fetch failed")
                emptyList()
            } finally {
                fetchPhase = FetchPhase.IDLE
                fetchMeta = null
                // Let the band's fetch machine settle before the next dialog.
                delay(3_000)
            }
        }

    /** Raw fetch result: reassembled bytes plus the transfer start timestamp (ms). */
    private data class FetchResult(val bytes: ByteArray, val startTsMs: Long)

    /**
     * Generic Huami fetch: START_DATE → FETCH_DATA → collect → CRC → ACK.
     * @return null when the band has no data or any step fails (already logged).
     */
    private suspend fun fetchRawData(fetchType: Byte, sinceMs: Long): FetchResult? {
        val tag = "0x%02x".format(fetchType.toInt() and 0xFF)
        // 1. START_DATE. The band may first answer with short rejections
        // ([10 01 05], [10 01 03], …) and only later send the real 15-byte
        // success — so non-success replies are ignored and we keep waiting
        // until the deadline instead of failing fast.
        var meta: ByteArray? = null
        var attempt = 0
        val deadlineMs = System.currentTimeMillis() + FETCH_START_TIMEOUT_MS
        while (meta == null && System.currentTimeMillis() < deadlineMs) {
            attempt++
            if (attempt > 1) {
                // Give the band's fetch state machine time to settle —
                // rapid-fire START_DATEs get crossed responses.
                delay(3_000)
            }
            Timber.i("MiBand7: requesting fetch type %s (attempt %d)…", tag, attempt)
            fetchPhase = FetchPhase.AWAIT_START
            writeFetchControl(
                byteArrayOf(
                    Huami2021Chunked.FETCH_CMD_START_DATE,
                    fetchType,
                ) + timeBytes(sinceMs)
            )
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) break
            val m = awaitFetchMeta(remaining, "start-date[$tag]")
            if (m == null) {
                Timber.w("MiBand7: fetch %s start-date attempt %d timed out", tag, attempt)
                continue
            }
            if (m.size >= 7 && m[0] == 0x10.toByte() &&
                m[1] == Huami2021Chunked.FETCH_CMD_START_DATE && m[2] == Huami2021Chunked.AUTH_SUCCESS
            ) {
                meta = m
            } else {
                Timber.d("MiBand7: fetch %s ignoring non-success reply [%s]", tag, m.toHex())
            }
        }
        meta ?: return null
        fetchExpected = u32le(meta, 3)
        val startTsMs = if (meta.size >= 15) calendarBytesToMs(meta, 7) else sinceMs
        Timber.i(
            "MiBand7: fetch %s meta: len=%d tsRaw=[%s] base=%s",
            tag, fetchExpected,
            if (meta.size >= 15) meta.copyOfRange(7, 15).toHex() else "?",
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(startTsMs))
        )
        if (fetchExpected == 0) {
            sendFetchAck(Huami2021Chunked.FETCH_ACK_DROP)
            awaitFetchMeta(FETCH_ACK_TIMEOUT_MS, "ack")
            return FetchResult(ByteArray(0), startTsMs)
        }

        // 2. FETCH_DATA + collect bulk frames
        fetchBuffer = ByteArrayOutputStream()
        fetchLastCounter = -1
        fetchPhase = FetchPhase.COLLECTING
        writeFetchControl(byteArrayOf(Huami2021Chunked.FETCH_CMD_FETCH_DATA))
        val resp = try {
            awaitFetchMeta(FETCH_DATA_TIMEOUT_MS, "fetch-data")
        } catch (e: Exception) {
            Timber.w("MiBand7: fetch %s transfer aborted (%s)", tag, e.message)
            sendFetchAck(Huami2021Chunked.FETCH_ACK_KEEP)
            return null
        } ?: run {
            Timber.w("MiBand7: fetch %s transfer timed out — keeping data on band", tag)
            sendFetchAck(Huami2021Chunked.FETCH_ACK_KEEP)
            return null
        }
        if (resp.size < 3 || resp[0] != 0x10.toByte() ||
            resp[1] != Huami2021Chunked.FETCH_CMD_FETCH_DATA || resp[2] != Huami2021Chunked.AUTH_SUCCESS
        ) {
            Timber.w("MiBand7: fetch %s unexpected fetch-data response [%s]", tag, resp.toHex())
            sendFetchAck(Huami2021Chunked.FETCH_ACK_KEEP)
            return null
        }
        val bytes = fetchBuffer.toByteArray()
        if (resp.size >= 7) {
            val expected = u32le(resp, 3)
            if (crc32(bytes) != expected) {
                Timber.w("MiBand7: fetch %s data CRC mismatch — keeping data on band", tag)
                sendFetchAck(Huami2021Chunked.FETCH_ACK_KEEP)
                awaitFetchMeta(FETCH_ACK_TIMEOUT_MS, "ack")
                return null
            }
        }
        sendFetchAck(Huami2021Chunked.FETCH_ACK_DROP)
        awaitFetchMeta(FETCH_ACK_TIMEOUT_MS, "ack")
        return FetchResult(bytes, startTsMs)
    }

    private suspend fun writeFetchControl(payload: ByteArray) {
        val g = sessionGatt ?: throw IllegalStateException("no GATT for fetch")
        Timber.d("MiBand7: fetch control → [%s]", payload.toHex())
        if (Huami2021Chunked.ENDPOINT_ACTIVITY_FETCH in supportedEndpoints) {
            // Chunked path (encrypted once authenticated)
            writeChunked(g, Huami2021Chunked.ENDPOINT_ACTIVITY_FETCH, payload)
        } else {
            // Classic path: raw write to the activity-control characteristic.
            // Used when the band does not advertise the 0x004b chunked fetch service.
            val char =
                activityControl ?: throw IllegalStateException("no activity-control characteristic")
            writeClassic(g, char, payload)
        }
    }

    private suspend fun awaitFetchMeta(timeoutMs: Long, what: String): ByteArray? {
        val d = CompletableDeferred<ByteArray>()
        fetchMeta = d
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: run {
            Timber.w("MiBand7: sleep fetch '%s' timed out", what)
            if (fetchMeta === d) fetchMeta = null
            null
        }
    }

    private suspend fun sendFetchAck(ackByte: Byte) {
        fetchPhase = FetchPhase.AWAIT_ACK
        writeFetchControl(byteArrayOf(Huami2021Chunked.FETCH_CMD_ACK, ackByte))
    }

    /** Metadata handler — fed by chunked 0x004b and classic 0x0004 alike. */
    private fun handleFetchControl(payload: ByteArray) {
        if (payload.isEmpty() || payload[0] != 0x10.toByte()) {
            Timber.v("MiBand7: ignoring non-response fetch control [%s]", payload.toHex())
            return
        }
        Timber.d("MiBand7: fetch control ← [%s]", payload.toHex())
        val waiter = fetchMeta
        if (waiter != null && fetchPhase != FetchPhase.IDLE) {
            waiter.complete(payload)
        } else {
            Timber.v("MiBand7: fetch control with no waiter (phase=%s)", fetchPhase)
        }
    }

    /** Bulk-data handler — raw `[counter][payload…]` frames on classic 0x0005. */
    private fun handleFetchData(value: ByteArray) {
        if (fetchPhase != FetchPhase.COLLECTING || value.isEmpty()) return
        val counter = value[0].toInt() and 0xFF
        if (counter != ((fetchLastCounter + 1) and 0xFF)) {
            Timber.w(
                "MiBand7: fetch data counter jump (got=%d, last=%d) — aborting",
                counter,
                fetchLastCounter
            )
            fetchPhase = FetchPhase.IDLE
            fetchMeta?.completeExceptionally(IllegalStateException("counter jump $counter"))
            return
        }
        fetchLastCounter = counter
        fetchBuffer.write(value, 1, value.size - 1)
    }

    /** Huami timestamp: [year_lo, year_hi, month, day, hour, min, sec, tzQuarterHours]. */
    private fun timeBytes(sinceMs: Long): ByteArray {
        val cal = Calendar.getInstance().apply { timeInMillis = sinceMs }
        val tzQuarterHours = (cal.timeZone.getOffset(sinceMs) / (15 * 60 * 1000)).toByte()
        return byteArrayOf(
            (cal.get(Calendar.YEAR) and 0xFF).toByte(),
            ((cal.get(Calendar.YEAR) shr 8) and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DATE).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            // Seconds MUST be truncated: Gadgetbridge notes that sending real seconds
            // causes failures on ZeppOS devices (Amazfit GTR 3 and likely others).
            0x00,
            tzQuarterHours,
        )
    }

    private fun u16le(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)

    private fun u32le(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or
                ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)

    /**
     * Parse a Huami calendar timestamp `[y_lo, y_hi, month, day, hour, min, sec, tz]`
     * at offset [o] into epoch millis. `tz` is the UTC offset in 15-minute units.
     */
    private fun calendarBytesToMs(a: ByteArray, o: Int): Long {
        val year = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
        val tzOffsetSeconds = a[o + 7].toInt() * 900
        return try {
            java.time.OffsetDateTime.of(
                year,
                a[o + 2].toInt() and 0xFF,
                a[o + 3].toInt() and 0xFF,
                a[o + 4].toInt() and 0xFF,
                a[o + 5].toInt() and 0xFF,
                a[o + 6].toInt() and 0xFF,
                0,
                java.time.ZoneOffset.ofTotalSeconds(tzOffsetSeconds)
            ).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Timber.w(e, "MiBand7: bad calendar bytes [%s]", a.copyOfRange(o, o + 8).toHex())
            System.currentTimeMillis()
        }
    }

    private fun crc32(data: ByteArray): Int {
        val c = CRC32()
        c.update(data)
        return c.value.toInt()
    }

    /**
     * Parse 594-byte sleep records: `[tsSession u32][midnight u32][…]`,
     * detail at 0x0a/0x0c (sleep start/end, minutes), stage table at 0x54
     * (`[start u16][end u16][type u8]`, minutes since midnight−24h;
     * types 4=light, 5=deep, 8=REM, 7=awake).
     */
    private fun parseSleepRecords(bytes: ByteArray): List<SleepSessionRecord> {
        if (bytes.size % SLEEP_RECORD_SIZE != 0) {
            Timber.w(
                "MiBand7: sleep data length %d not a multiple of %d — parsing whole blocks",
                bytes.size, SLEEP_RECORD_SIZE
            )
        }
        val out = mutableListOf<SleepSessionRecord>()
        var o = 0
        while (o + SLEEP_RECORD_SIZE <= bytes.size) {
            parseSleepRecord(bytes, o)?.let { out.add(it) }
            o += SLEEP_RECORD_SIZE
        }
        Timber.i("MiBand7: parsed %d sleep session(s)", out.size)
        return shiftFutureToNow(
            out,
            maxTsOf = { s -> maxOf(s.endMs, s.stages.maxOfOrNull { it.endMs } ?: s.endMs) },
            shift = { s, skewMs ->
                s.copy(
                    startMs = s.startMs - skewMs,
                    endMs = s.endMs - skewMs,
                    stages = s.stages.map {
                        it.copy(startMs = it.startMs - skewMs, endMs = it.endMs - skewMs)
                    }
                )
            }
        )
    }

    private fun parseSleepRecord(b: ByteArray, o: Int): SleepSessionRecord? {
        val midnight = u32le(b, o + 4).toLong() and 0xFFFFFFFFL
        val sleepStartMin = u16le(b, o + 0x0a)
        val sleepEndMin = u16le(b, o + 0x0c)
        if (sleepEndMin <= sleepStartMin) return null
        val base = midnight - 86_400L
        val startMs = (base + sleepStartMin * 60L) * 1000L
        val endMs = (base + sleepEndMin * 60L) * 1000L
        val numStages = b[o + 0x54].toInt() and 0xFF
        val stages = mutableListOf<SleepStageSample>()
        for (i in 0 until numStages) {
            val so = o + 0x56 + 5 * i
            if (so + 5 > o + SLEEP_RECORD_SIZE) break
            val s = u16le(b, so)
            val e = u16le(b, so + 2)
            val stage = when (b[so + 4].toInt() and 0xFF) {
                4 -> SleepStage.LIGHT
                5 -> SleepStage.DEEP
                8 -> SleepStage.REM
                7 -> SleepStage.AWAKE
                else -> null
            } ?: continue
            if (e <= s) continue
            stages.add(SleepStageSample((base + s * 60L) * 1000L, (base + e * 60L) * 1000L, stage))
        }
        return SleepSessionRecord(startMs, endMs, stages)
    }

    /**
     * Parse SpO2 history: `[version=0x02][timestamp u32][spo2 1B][60B]…` (65 bytes each).
     * A negative raw byte marks an automatic measurement (value + 128).
     */
    private fun parseSpo2Records(bytes: ByteArray): List<SpO2SampleRecord> {
        if (bytes.isEmpty()) return emptyList()
        if (bytes[0].toInt() and 0xFF != 0x02) {
            Timber.w("MiBand7: unexpected SpO2 data version 0x%02x", bytes[0].toInt() and 0xFF)
            return emptyList()
        }
        val out = mutableListOf<SpO2SampleRecord>()
        var o = 1
        while (o + 65 <= bytes.size) {
            val tsSec = u32le(bytes, o).toLong() and 0xFFFFFFFFL
            val raw = bytes[o + 4].toInt()
            val percent = if (raw < 0) raw + 128 else raw
            if (percent in 1..100) {
                out.add(SpO2SampleRecord(tsSec * 1000L, percent))
            }
            o += 65
        }
        Timber.i("MiBand7: parsed %d SpO2 sample(s)", out.size)
        return out
    }

    /**
     * Parse automatic stress history: minute slots from [startTsMs].
     * `0xFF` advances the clock one minute; any other byte is the 0–100 score
     * for the *current* minute (several scores may share a minute).
     * Bands: 0–39 relaxed, 40–59 mild, 60–79 moderate, 80–100 high.
     */
    private fun parseStressRecords(bytes: ByteArray, startTsMs: Long): List<StressSampleRecord> {
        Timber.d("MiBand7: stress raw (%dB): [%s]", bytes.size, bytes.toHex())
        val out = mutableListOf<StressSampleRecord>()
        var minute = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0xFF) {
                minute++
                continue
            }
            if (v in 0..100) {
                out.add(StressSampleRecord(startTsMs + minute * 60_000L, v))
            }
        }
        Timber.i("MiBand7: parsed %d stress sample(s)", out.size)
        return shiftFutureToNow(
            out,
            maxTsOf = { s -> s.timestampMs },
            shift = { s, skewMs -> s.copy(timestampMs = s.timestampMs - skewMs) }
        )
    }

    /**
     * The band's fetch timestamps are unreliable (varying tz bytes, bases landing
     * in the future). If a batch's newest sample lies ahead of the phone clock,
     * shift the whole batch back so it ends now — preserving order and spacing
     * while keeping health data out of the future (which breaks sorting and age).
     */
    private fun <T> shiftFutureToNow(
        items: List<T>,
        maxTsOf: (T) -> Long,
        shift: (T, Long) -> T,
    ): List<T> {
        val maxTs = items.maxOfOrNull(maxTsOf) ?: return items
        val skewMs = maxTs - System.currentTimeMillis()
        if (skewMs <= 0) return items
        Timber.w("MiBand7: fetch timestamps %d ms in the future — shifting batch back", skewMs)
        return items.map { shift(it, skewMs) }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    /** Parse a chunked HR notification from the HEARTRATE endpoint. */
    private fun parseChunkedHr(p: ByteArray) {
        // Format: [CMD][sub-cmd][bpm]  (at least 3 bytes)
        if (p.size < 3) return
        val bpm = p[2].toInt() and 0xFF
        if (bpm in 30..250) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    /** Parse a standard GATT HR Measurement characteristic value. */
    private fun parseStdHr(data: ByteArray) {
        if (data.size < 2) return
        val flags = data[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) {
            data[1].toInt() and 0xFF  // 8-bit value
        } else {
            ByteBuffer.wrap(data, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        }
        if (bpm in 30..250) scope.launch { _events.emit(DeviceEvent.HeartRate(bpm)) }
    }

    /**
     * Parse a battery endpoint response.
     *
     * ZeppOS battery reply format (endpoint 0x0029, 21 bytes total):
     * ```
     * [CMD=0x04][?][LEVEL:1][STATUS:1][lastCharge: 2016-11-26 18:35][numCharges]
     * [lastCharge: 2016-11-26 23:43:59][numCharges][chargeLevel]
     * ```
     * - `CMD`    = 0x04 (CMD_BATTERY_REPLY)
     * - `LEVEL`  = battery percentage 0–100
     * - `STATUS` = 0 (normal), 1 (charging), 3 (fully charged)
     */
    private fun parseBattery(p: ByteArray) {
        if (p.size < 4) {
            Timber.w("MiBand7: battery response too short (%d bytes)", p.size)
            return
        }
        if (p[0].toInt() and 0xFF != 0x04) {
            Timber.w("MiBand7: unexpected battery command 0x%02x", p[0].toInt() and 0xFF)
            return
        }
        val level = p[2].toInt() and 0xFF
        val status = p[3].toInt() and 0xFF
        val charging = status == 1 || status == 3
        Timber.i("MiBand7: battery → %d%% (status=%d)", level, status)
        scope.launch { _events.emit(DeviceEvent.Battery(level, charging)) }
    }

    /**
     * Parse a step / activity endpoint payload.
     *
     * The band carries two payload sub-types on endpoint 0x0016. Both embed the same
     * 13-byte data block:
     * ```
     * [?][steps:2 LE][? ?][distanceM:4 LE][calories:4 LE]
     * ```
     *
     * **Real-time push notification (CMD=0x07), 14 bytes total:**
     * ```
     * [0x07][ data:13 ]   (block starts at p[1])
     * ```
     * **Reply to an explicit step request (CMD=0x04), 15 bytes total:**
     * ```
     * [0x04][status][ data:13 ]   (block starts at p[2])
     * ```
     */
    private fun parseActivity(p: ByteArray) {
        if (p.isEmpty()) return
        val u16: (ByteArray, Int) -> Int = { arr, base ->
            (arr[base].toInt() and 0xFF) or ((arr[base + 1].toInt() and 0xFF) shl 8)
        }
        val u32: (ByteArray, Int) -> Int = { arr, base ->
            (arr[base].toInt() and 0xFF) or ((arr[base + 1].toInt() and 0xFF) shl 8) or
                    ((arr[base + 2].toInt() and 0xFF) shl 16) or ((arr[base + 3].toInt() and 0xFF) shl 24)
        }

        // Both sub-types carry the same 13-byte block starting at `base`:
        // [?][steps:2 LE][? ?][distanceM:4 LE][calories:4 LE]
        fun emit(base: Int, tag: String) {
            val steps = u16(p, base + 1)
            val distM = u32(p, base + 5)
            val cal = u32(p, base + 9)
            Timber.d("MiBand7: steps %s → steps=%d dist=%dm kcal=%d", tag, steps, distM, cal)
            if (steps > 0) scope.launch {
                _events.emit(
                    DeviceEvent.Steps(
                        steps,
                        cal,
                        distM.toFloat()
                    )
                )
            }
        }
        when (p[0].toInt() and 0xFF) {
            0x07 -> {
                // [0x07][13-byte block] → block starts at p[1]
                if (p.size < 14) return
                emit(1, "push")
            }

            0x04 -> {
                // [0x04][status][13-byte block] → block starts at p[2]
                if (p.size < 15) return
                emit(2, "reply")
            }

            else -> Timber.v("MiBand7: ignoring activity sub-command 0x%02x", p[0].toInt() and 0xFF)
        }
    }

    /** Parse an SpO₂ measurement result. */
    private fun parseSpo2(p: ByteArray) {
        if (p.size < 2 || p[0].toInt() and 0xFF != 0x01) return
        val v = p[1].toInt() and 0xFF
        if (v in 50..100) scope.launch { _events.emit(DeviceEvent.SpO2(v)) }
    }

    /**
     * Parse raw accelerometer data from the classic `0x0002` characteristic.
     *
     * Type `0x00`: `[0x00][index][x:2s][y:2s][z:2s]…` (6 bytes per sample, int16
     * little-endian). Raw values span roughly ±4100 for ±1 g, so each axis is
     * scaled as `g = raw * -9.81 / 4100` (Gadgetbridge scale factors).
     * Every sample is emitted as [DeviceEvent.RawAccelerometer] for actigraphy.
     */
    private fun parseRawSensorData(value: ByteArray) {
        if (value.size < 2) return
        if (!rawStreamLogged) {
            rawStreamLogged = true
            Timber.i("MiBand7: raw accelerometer stream live (%d bytes first packet)", value.size)
        }
        when (value[0].toInt() and 0xFF) {
            0x00 -> {
                if ((value.size - 2) % 6 != 0) {
                    Timber.w(
                        "MiBand7: raw sensor type-0 length not divisible by 6 (%d)",
                        value.size
                    )
                    return
                }
                var o = 2
                while (o + 6 <= value.size) {
                    val x = (((value[o + 1].toInt() and 0xFF) shl 8) or (value[o].toInt() and 0xFF))
                    val xi = if (x >= 0x8000) x - 0x10000 else x
                    val y =
                        (((value[o + 3].toInt() and 0xFF) shl 8) or (value[o + 2].toInt() and 0xFF))
                    val yi = if (y >= 0x8000) y - 0x10000 else y
                    val z =
                        (((value[o + 5].toInt() and 0xFF) shl 8) or (value[o + 4].toInt() and 0xFF))
                    val zi = if (z >= 0x8000) z - 0x10000 else z
                    val gx = (xi * -9.81f) / 4100f
                    val gy = (yi * -9.81f) / 4100f
                    val gz = (zi * -9.81f) / 4100f
                    scope.launch { _events.emit(DeviceEvent.RawAccelerometer(gx, gy, gz)) }
                    o += 6
                }
            }

            else -> Timber.v(
                "MiBand7: ignoring raw sensor type 0x%02x (%d bytes)",
                value[0].toInt() and 0xFF, value.size
            )
        }
    }

    /**
     * Parse a sleep-state event from the heart-rate endpoint (CMD=0x06).
     * ZeppOS encodes only the transition: `[0x06][0x01=fell asleep, 0x00=woke up]`.
     * Sleep onset is recorded as LIGHT (the band does not report depth here).
     */
    private fun parseSleep(p: ByteArray) {
        if (p.size < 2) return
        val stage = when (p[1].toInt() and 0xFF) {
            0x01 -> SleepStage.LIGHT
            0x00 -> SleepStage.AWAKE
            else -> null
        } ?: return
        Timber.i("MiBand7: sleep state → %s", stage)
        scope.launch { _events.emit(DeviceEvent.SleepData(stage)) }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    override suspend fun vibrate(gatt: BluetoothGatt, pattern: VibratePattern) {
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x03))
        if (pattern == VibratePattern.SHORT || pattern == VibratePattern.DOUBLE) {
            delay(600)
            writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x06))
        }
    }

    override suspend fun setHeartRateMonitoring(gatt: BluetoothGatt, continuous: Boolean) {
        val mode = if (continuous) 0x01.toByte() else 0x00.toByte()
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_HEARTRATE, byteArrayOf(0x04, mode))
    }

    override suspend fun syncTime(gatt: BluetoothGatt) {
        val ts = Calendar.getInstance()
        val rules = ZoneId.systemDefault().rules
        val now = Instant.now()

        val p = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x05.toByte())                                                      // CMD_SET_TIME
            putShort(ts.get(Calendar.YEAR).toShort())
            put((ts.get(Calendar.MONTH) + 1).toByte())
            put(ts.get(Calendar.DATE).toByte())
            put(ts.get(Calendar.HOUR_OF_DAY).toByte())
            put(ts.get(Calendar.MINUTE).toByte())
            put(ts.get(Calendar.SECOND).toByte())
            put((ts.get(Calendar.DAY_OF_WEEK) - 1).toByte())
            put(
                (ts.get(Calendar.MILLISECOND) / 1000.0 * 256.0).toInt().toByte()
            )  // 1/256 s fractions
            put(if (rules.isDaylightSavings(now)) 0x08.toByte() else 0x00.toByte())
            put((rules.getOffset(now).totalSeconds / (60 * 15)).toByte())          // UTC offset in 15-min units
        }.array()

        writeChunked(gatt, Huami2021Chunked.ENDPOINT_TIME, p)
    }

    /**
     * Enable or disable the inactivity (idle) reminder.
     *
     * Config SET frame (endpoint 0x000a, encrypted):
     * ```
     * [0x05 SET][HEALTH 0x08][version 0x03][0x00][numArgs 0x01]
     * [arg 0x41][type BOOL 0x0b][value 0x00|0x01]
     * ```
     * The band replies with an ACK `[0x06][status]` on the config endpoint.
     */
    override suspend fun setInactivityWarnings(gatt: BluetoothGatt, enabled: Boolean) {
        Timber.i("MiBand7: %s inactivity (idle) reminder", if (enabled) "enabling" else "disabling")
        val payload = byteArrayOf(
            Huami2021Chunked.CONFIG_CMD_SET,
            Huami2021Chunked.CONFIG_GROUP_HEALTH,
            Huami2021Chunked.CONFIG_GROUP_HEALTH_VERSION,
            0x00,
            0x01,
            Huami2021Chunked.CONFIG_ARG_INACTIVITY_ENABLED,
            Huami2021Chunked.CONFIG_TYPE_BOOL,
            if (enabled) 0x01 else 0x00,
        )
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_CONFIG, payload)
    }

    override suspend fun requestBattery(gatt: BluetoothGatt) {
        Timber.d("MiBand7: requesting battery info…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_BATTERY, byteArrayOf(0x03))
    }

    private suspend fun requestServices(gatt: BluetoothGatt) {
        Timber.d("MiBand7: requesting services list…")
        writeChunked(
            gatt, Huami2021Chunked.ENDPOINT_SERVICES,
            byteArrayOf(Huami2021Chunked.SERVICES_CMD_GET_LIST)
        )
    }

    private suspend fun requestDeviceInfo(gatt: BluetoothGatt) {
        Timber.d("MiBand7: requesting device info…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_DEVICE_INFO, byteArrayOf(0x01))
    }

    private suspend fun requestCurrentSteps(gatt: BluetoothGatt) {
        Timber.d("MiBand7: requesting current steps…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_STEPS, byteArrayOf(0x03))
    }

    private suspend fun enableRealtimeSteps(gatt: BluetoothGatt) {
        Timber.d("MiBand7: enabling real-time step notifications…")
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_STEPS, byteArrayOf(0x05, 0x01))
    }

    override suspend fun onSleepTrackingStarted(gatt: BluetoothGatt) {
        setHeartRateMonitoring(gatt, continuous = true)
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x01))
        setRawSensorEnabled(gatt, enabled = true)
    }

    override suspend fun onSleepTrackingStopped(gatt: BluetoothGatt) {
        // NOTE: HR streaming is intentionally left running — the dashboard's 1s CONTINUE
        // loop owns it while connected; stopping here would blank the dashboard after
        // every sleep session. Only SpO2 (sleep-specific) is switched off.
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_SPO2, byteArrayOf(0x01, 0x00))
        setRawSensorEnabled(gatt, enabled = false)
    }

    override suspend fun triggerAlarm(gatt: BluetoothGatt) {
        if (alarmActive) return
        Timber.i("MiBand7: alarm triggered — vibrating until dismissed")
        alarmActive = true
        alarmJob?.cancel()
        // A single START vibrates only briefly — re-trigger every 10 s until dismissed
        // (protocol-scope child, dies with the connection).
        alarmJob = scope.launch {
            while (alarmActive) {
                writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x03))
                delay(10_000)
            }
        }
    }

    override suspend fun dismissAlarm(gatt: BluetoothGatt) {
        if (!alarmActive && alarmJob?.isActive != true) {
            // Still send STOP — cheap and covers races (e.g. trigger lost).
            Timber.d("MiBand7: alarm dismiss (was not active)")
        } else {
            Timber.i("MiBand7: alarm dismissed")
        }
        alarmActive = false
        alarmJob?.cancel()
        alarmJob = null
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_FIND_DEVICE, byteArrayOf(0x06))
    }

    // ── Notification mirroring (endpoint 0x001e, encrypted) ───────────────────
    //
    // Frame (Gadgetbridge layout): `[0x03 SEND][id:4 LE][type][0x00 SHOW]
    //   [appPackage][00][title][00][body][00][appName][00][hasReply]`
    // (+ one trailing 0x00 when the band reports capabilities version >= 5).
    // Dismiss: `[0x03][id:4 LE][0xfa][0x02][00 × 5]` (12 bytes).

    /** Cached notification-service capabilities version (null = not queried yet). */
    @Volatile
    private var notifVersion: Int? = null
    private val notifVersionMutex = Mutex()

    /**
     * Mirror a phone notification on the band.
     * @param id        Android notification id (band-side handle).
     * @param appPackage Sending app's package name (drives the band icon lookup).
     * @param title     Notification title (truncated to 64 chars).
     * @param body      Notification text (truncated to 512 chars).
     * @param appName   Human-readable app name shown under the text.
     */
    override suspend fun sendNotification(
        gatt: BluetoothGatt,
        id: Int,
        appPackage: String,
        title: String,
        body: String,
        appName: String,
    ) {
        val version = ensureNotifCapabilities(gatt)
        Timber.i("MiBand7: forwarding notification #%d from %s", id, appPackage)
        val payload = ByteArrayOutputStream().apply {
            write(Huami2021Chunked.NOTIF_CMD_SEND.toInt())
            write(intToLeBytes(id))
            write(Huami2021Chunked.NOTIF_TYPE_NORMAL.toInt())
            write(Huami2021Chunked.NOTIF_SUBCMD_SHOW.toInt())
            write(appPackage.toByteArray(Charsets.UTF_8))
            write(0)
            write(title.take(64).toByteArray(Charsets.UTF_8))
            write(0)
            write(body.take(512).toByteArray(Charsets.UTF_8))
            write(0)
            write(appName.take(64).toByteArray(Charsets.UTF_8))
            write(0)
            write(0) // hasReply = false
            if (version >= 5) write(0) // silent flag
        }.toByteArray()
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_NOTIFICATION, payload)
    }

    /** Remove a notification from the band (best-effort). */
    override suspend fun dismissNotification(gatt: BluetoothGatt, id: Int) {
        Timber.d("MiBand7: dismissing notification #%d", id)
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(Huami2021Chunked.NOTIF_CMD_SEND)
            putInt(id)
            put(Huami2021Chunked.NOTIF_TYPE_NORMAL)
            put(Huami2021Chunked.NOTIF_SUBCMD_DISMISS_FROM_PHONE)
            put(0); put(0); put(0); put(0); put(0)
        }.array()
        writeChunked(gatt, Huami2021Chunked.ENDPOINT_NOTIFICATION, payload)
    }

    /**
     * Query the notification-service capabilities version once per connection.
     * @return version, or 4 (no v5 extras) when the band does not answer.
     */
    private suspend fun ensureNotifCapabilities(gatt: BluetoothGatt): Int {
        notifVersion?.let { return it }
        return notifVersionMutex.withLock {
            notifVersion?.let { return it }
            val waiter = CompletableDeferred<ByteArray>()
            notifCapWaiter = waiter
            try {
                writeChunked(
                    gatt, Huami2021Chunked.ENDPOINT_NOTIFICATION,
                    byteArrayOf(Huami2021Chunked.NOTIF_CMD_CAPABILITIES_REQUEST)
                )
                val resp = withTimeoutOrNull(5_000) { waiter.await() }
                val version = if (resp != null && resp.size >= 2 &&
                    resp[0] == Huami2021Chunked.NOTIF_CMD_CAPABILITIES_RESPONSE
                ) {
                    resp[1].toInt() and 0xFF
                } else {
                    4
                }
                Timber.i("MiBand7: notification service version=%d", version)
                notifVersion = version
                version
            } finally {
                if (notifCapWaiter === waiter) notifCapWaiter = null
            }
        }
    }

    @Volatile
    private var notifCapWaiter: CompletableDeferred<ByteArray>? = null

    private fun intToLeBytes(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    override suspend fun setRawSensorEnabled(gatt: BluetoothGatt, enabled: Boolean) {
        rawSensorStreaming = enabled
        if (enabled) {
            Timber.i("MiBand7: starting raw accelerometer streaming")
            sendRawSensorStart(gatt)
            // The band drops the stream after a while — re-enable every 10 s
            // (Gadgetbridge pattern) while streaming is requested.
            if (rawSensorJob?.isActive != true) {
                rawSensorJob = scope.launch {
                    while (true) {
                        delay(10_000)
                        if (!rawSensorStreaming) break
                        sendRawSensorStart(gatt)
                    }
                }
            }
        } else {
            Timber.i("MiBand7: stopping raw accelerometer streaming")
            rawSensorJob?.cancel()
            rawSensorJob = null
            val control = rawSensorControl
            if (control == null) {
                Timber.w("MiBand7: no raw-sensor control characteristic found")
                return
            }
            writeClassic(gatt, control, RAW_SENSOR_STOP)
        }
    }

    private suspend fun sendRawSensorStart(gatt: BluetoothGatt) {
        val control = rawSensorControl
        if (control == null) {
            Timber.w("MiBand7: no raw-sensor control characteristic found")
            return
        }
        writeClassic(gatt, control, RAW_SENSOR_START_1)
        delay(200)
        writeClassic(gatt, control, RAW_SENSOR_START_2)
        delay(200)
        writeClassic(gatt, control, RAW_SENSOR_START_3)
    }

    /**
     * Write to a classic (non-chunked) characteristic, choosing the write type from
     * the characteristic's advertised properties — mirroring Gadgetbridge, which
     * uses with-response writes unless the characteristic is WWR-only. Hardcoding
     * the wrong type gets the write silently dropped by the stack/band.
     */
    private suspend fun writeClassic(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
    ) {
        val props = char.properties
        val noResponse = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        Timber.d(
            "MiBand7: classic write %s noResponse=%s [%s]",
            char.uuid, noResponse, data.toHex()
        )
        writeMutex.withLock {
            writeRaw(gatt, char, data, noResponse = noResponse)
        }
    }

    // ── Low-level BLE write helpers ───────────────────────────────────────────

    /**
     * Encodes [payload] for [endpoint] using [Huami2021Chunked.encode] and writes each packet
     * to the chunked-write characteristic, honouring the [writeMutex] to serialise operations.
     */
    private suspend fun writeChunked(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) =
        writeMutex.withLock {
            val char = chunkedWrite ?: return@withLock
            val handle = handleSeq++

            val key = if (isAuthenticated && Huami2021Chunked.isEncrypted(endpoint)) {
                decoder.sessionKey
            } else {
                null
            }
            val seq = if (key != null) encryptedSeq++ else 0

            Timber.d(
                "MiBand7: writeChunked endpoint=0x%04x handle=0x%02x len=%d encrypted=%s",
                endpoint.toInt() and 0xFFFF, handle.toInt() and 0xFF, payload.size, key != null
            )

            val packets = Huami2021Chunked.encode(
                handle = handle,
                endpoint = endpoint,
                payload = payload,
                mtu = negotiatedMtu,
                sessionKey = key,
                encryptedSeq = seq,
            )

            val noResponse =
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

            for ((idx, packet) in packets.withIndex()) {
                Timber.v("MiBand7: write packet %d/%d  [%s]", idx + 1, packets.size, packet.toHex())
                writeRaw(gatt, char, packet, noResponse = noResponse)
                if (packets.size > 1) delay(50) // Pace multi-packet writes for BT stack stability
            }
        }

    @Suppress("DEPRECATION")  // Pre-Tiramisu GATT API branch is intentionally kept
    private suspend fun writeRaw(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic?,
        data: ByteArray,
        noResponse: Boolean = false,
    ) {
        if (char == null) return
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, data, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("MiBand7: writeCharacteristic returned %d for %s", status, char.uuid)
            } else if (!noResponse) {
                awaitCharacteristicWrite?.invoke()
            }
        } else {
            char.value = data
            char.writeType = writeType
            if (gatt.writeCharacteristic(char)) {
                if (!noResponse) awaitCharacteristicWrite?.invoke()
            } else {
                Timber.e("MiBand7: writeCharacteristic(legacy) returned false for %s", char.uuid)
            }
        }
    }

    @Suppress("DEPRECATION")  // Pre-Tiramisu GATT API branch is intentionally kept
    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(char, true)) return false
        val desc = char.getDescriptor(UUID_CCCD) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
        awaitDescriptorWrite = null
        awaitCharacteristicWrite = null
        sessionGatt = null
        notifVersion = null
        notifCapWaiter?.cancel()
        notifCapWaiter = null
        fetchPhase = FetchPhase.IDLE
        fetchMeta?.cancel()
        fetchMeta = null
        scope.cancel()
    }

    // ── Debug helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
