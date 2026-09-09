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
import kotlinx.coroutines.withTimeoutOrNull
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

        /** How long to wait for the ECDH handshake to complete before giving up. */
        private const val AUTH_TIMEOUT_MS = 30_000L

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

        Timber.i("MiBand7: initializing ${gatt.device.address} (MTU=$negotiatedMtu)")

        // Locate chunked-transfer characteristics (they can appear in any service)
        for (svc in gatt.services) {
            if (chunkedWrite == null) chunkedWrite = svc.getCharacteristic(UUID_CHAR_CHUNKED_WRITE)
            if (chunkedRead  == null) chunkedRead  = svc.getCharacteristic(UUID_CHAR_CHUNKED_READ)
        }
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

            else -> false
        }
    }

    /**
     * Send a chunked-transfer ACK to the band.
     *
     * **Bug fix:** ACKs must be written to the *write* characteristic (0x0016), not the
     * notify characteristic (0x0017).  The notify characteristic is read-only from the
     * phone's perspective; writing to it would silently fail on most Android stacks.
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
            // Write to chunkedWrite (0x0016) — the phone→band channel
            writeRaw(gatt, chunkedWrite, ack, noResponse = true)
        }
    }

    private fun dispatch(gatt: BluetoothGatt, endpoint: Short, payload: ByteArray) {
        Timber.d(
            "MiBand7: dispatch endpoint=0x%04x size=%d  [%s]",
            endpoint.toInt() and 0xFFFF, payload.size, payload.toHex()
        )

        when (endpoint) {
            Huami2021Chunked.ENDPOINT_SERVICES -> {
                // Service list: [0x04][count:2 LE][endpoint:2 LE]…
                if (payload.size >= 3 && payload[0].toInt() and 0xFF == Huami2021Chunked.SERVICES_CMD_RET_LIST.toInt()) {
                    val count =
                        (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
                    val endpoints = (0 until count).mapNotNull { i ->
                        val o = 3 + i * 2
                        if (o + 1 < payload.size) {
                            "0x%04x".format((payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8))
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

        // Poll battery and steps periodically.  Battery is encrypted so it must be polled;
        // steps are also polled as a fallback alongside real-time push notifications.
        while (true) {
            requestBattery(gatt)
            delay(1_000)
            requestCurrentSteps(gatt)
            delay(30_000)
        }
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
     * Parse a sleep-state event from the heart-rate endpoint (CMD=0x06).
     * ZeppOS encodes sleep stage as: `[0x06][0x01=Asleep, 0x00=Awake]`.
     */
    private fun parseSleep(p: ByteArray) {
        if (p.size < 2) return
        val stage = when (p[1].toInt() and 0xFF) {
            0x01 -> SleepStage.DEEP
            0x00 -> SleepStage.AWAKE
            else -> null
        } ?: return
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
        // Raw accelerometer access is not implemented in the ZeppOS chunked protocol for
        // Mi Band 7.  Sleep as Android will operate in HR-only mode for sleep tracking.
        Timber.d("MiBand7: setRawSensorEnabled($enabled) — not supported on this device")
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
        scope.cancel()
    }

    // ── Debug helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
