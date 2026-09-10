package nodomain.freeyourgadget.gadgetbridge.ble.protocol

import android.annotation.SuppressLint
import nodomain.freeyourgadget.gadgetbridge.ble.protocol.Huami2021Chunked.ENDPOINT_AUTH
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Huami 2021 Extended Header / Chunked Transfer Protocol (ZeppOS)
 * ==============================================================
 * Used by Xiaomi Smart Band 7 and later ZeppOS devices.
 *
 * Each logical message is split into one or more BLE packets of at most (MTU − 3) bytes.
 *
 * ## Packet layout
 *
 * **First packet (11-byte header):**
 * ```
 * [0x03][flags:1][0x00][handle:1][count:1][origLen:4 LE][endpoint:2 LE][payload chunk...]
 * ```
 *
 * **Continuation packets (5-byte header):**
 * ```
 * [0x03][flags:1][0x00][handle:1][count:1][payload chunk...]
 * ```
 *
 * **Flags (bitfield):**
 * | Bit | Meaning          |
 * |-----|-----------------|
 * | 0   | First packet    |
 * | 1   | Last packet     |
 * | 2   | Needs ACK       |
 * | 3   | Payload is AES-encrypted |
 *
 * ## Encryption
 * When `flags & 0x08` is set the payload is AES-128/ECB encrypted.
 * The per-message key is: `messageKey[i] = sessionKey[i] XOR handle`.
 * The plaintext block is: `payload | encryptedSeq(4 LE) | CRC32(payload|seq)(4 LE)`,
 * zero-padded to the next 16-byte AES-block boundary before encryption.
 *
 * ## ACK format (phone → band, written to the *write* characteristic 0x0016)
 * ```
 * [0x04][0x00][handle:1][0x01][count:1]
 * ```
 */
object Huami2021Chunked {

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * Service list exchange (phase 2, right after authentication).
     * Request: `[0x03]`; reply: `[0x04][count:2 LE][endpoint:2 LE]…`.
     * Data services must only be initialised after this list is received.
     */
    const val ENDPOINT_SERVICES: Short = 0x0000.toShort()

    /**
     * ZeppOS ECDH key-exchange endpoint (0x0002).
     * Historically documented as the phone's public-key destination, but working
     * ZeppOS implementations (Gadgetbridge) send the initial key to [ENDPOINT_AUTH]
     * (0x0082); writes to 0x0002 get ACKed without triggering the exchange.
     */
    const val ENDPOINT_AUTH_ZEPPOS: Short = 0x0002.toShort()

    /**
     * Authentication response endpoint (0x0082).
     * The band replies with its public key + nonce here.
     * The phone then sends double-encrypted nonces back on this same endpoint.
     */
    const val ENDPOINT_AUTH: Short = 0x0082.toShort()

    /** "Find my device" vibration control. */
    const val ENDPOINT_FIND_DEVICE: Short = 0x001a.toShort()

    /** Heart-rate measurement and monitoring control. */
    const val ENDPOINT_HEARTRATE: Short = 0x001d.toShort()

    /**
     * Battery level and charging state (encrypted).
     */
    const val ENDPOINT_BATTERY: Short = 0x0029.toShort()

    /** Time synchronisation. */
    const val ENDPOINT_TIME: Short = 0x0047.toShort()

    /** Real-time step / activity counters. */
    const val ENDPOINT_STEPS: Short = 0x0016.toShort()

    /** Historical activity data fetch. */
    const val ENDPOINT_ACTIVITY_FETCH: Short = 0x004b.toShort()

    /** Blood-oxygen (SpO₂) measurement control. */
    const val ENDPOINT_SPO2: Short = 0x0045.toShort()

    /**
     * Connection management / keepalive (encrypted).
     * The band sends PING (`0x03`); the phone must reply with PONG (`0x04`).
     * MTU request/reply (0x01/0x02) negotiates the chunked-transfer MTU.
     */
    const val ENDPOINT_CONNECTION: Short = 0x0015.toShort()

    /** Static device information (firmware version, hardware revision, …). */
    const val ENDPOINT_DEVICE_INFO: Short = 0x0043.toShort()

    /**
     * Device configuration service (encrypted).
     * Commands: capabilities request 0x01 / response 0x02, get 0x03 / response 0x04,
     * set 0x05, ack 0x06.
     */
    const val ENDPOINT_CONFIG: Short = 0x000a.toShort()

    /** User profile (height, weight, date-of-birth, gender). */
    const val ENDPOINT_USER_INFO: Short = 0x0022.toShort()

    // ── Authentication command / status bytes ─────────────────────────────────

    /** Command: phone sends its B-163 EC public key. */
    const val AUTH_CMD_PUB_KEY: Byte = 0x04

    /** Command: phone sends the double-encrypted nonces. */
    const val AUTH_CMD_SESSION_KEY: Byte = 0x05

    /** First byte of every auth response from the band. */
    const val AUTH_RESP_PREFIX: Byte = 0x10

    /** Status byte indicating a successful operation (second byte of most responses). */
    const val AUTH_SUCCESS: Byte = 0x01

    // ── Service-list commands (endpoint 0x0000) ───────────────────────────────

    /** Request the list of supported services. */
    const val SERVICES_CMD_GET_LIST: Byte = 0x03

    /** Reply containing the supported-services list. */
    const val SERVICES_CMD_RET_LIST: Byte = 0x04

    // ── Connection keepalive commands (endpoint 0x0015) ──────────────────────

    const val CONNECTION_CMD_MTU_REQUEST: Byte = 0x01
    const val CONNECTION_CMD_MTU_RESPONSE: Byte = 0x02
    const val CONNECTION_CMD_PING: Byte = 0x03
    const val CONNECTION_CMD_PONG: Byte = 0x04

    // ── Configuration service (endpoint 0x000a) ───────────────────────────────

    const val CONFIG_CMD_CAPABILITIES_REQUEST: Byte = 0x01
    const val CONFIG_CMD_CAPABILITIES_RESPONSE: Byte = 0x02
    const val CONFIG_CMD_REQUEST: Byte = 0x03
    const val CONFIG_CMD_RESPONSE: Byte = 0x04
    const val CONFIG_CMD_SET: Byte = 0x05
    const val CONFIG_CMD_ACK: Byte = 0x06

    /** Config group: HEALTH (inactivity reminder, sleep, HR settings, …). */
    const val CONFIG_GROUP_HEALTH: Byte = 0x08

    /** Config group version for HEALTH (highest known). */
    const val CONFIG_GROUP_HEALTH_VERSION: Byte = 0x03

    /** Argument: inactivity (idle) reminder enabled. */
    const val CONFIG_ARG_INACTIVITY_ENABLED: Byte = 0x41

    /** Config argument type: boolean. */
    const val CONFIG_TYPE_BOOL: Byte = 0x0b

    // ── Notification service (endpoint 0x001e, encrypted) ─────────────────────

    /** Notification service endpoint. */
    const val ENDPOINT_NOTIFICATION: Short = 0x001e.toShort()

    const val NOTIF_CMD_CAPABILITIES_REQUEST: Byte = 0x01
    const val NOTIF_CMD_CAPABILITIES_RESPONSE: Byte = 0x02
    const val NOTIF_CMD_SEND: Byte = 0x03
    const val NOTIF_CMD_REPLY: Byte = 0x04
    const val NOTIF_CMD_DISMISS: Byte = 0x05
    const val NOTIF_CMD_REPLY_ACK: Byte = 0x06
    const val NOTIF_CMD_ICON_REQUEST: Byte = 0x10

    const val NOTIF_TYPE_NORMAL: Byte = 0xfa.toByte()
    const val NOTIF_TYPE_SMS: Byte = 0x05
    const val NOTIF_SUBCMD_SHOW: Byte = 0x00
    const val NOTIF_SUBCMD_DISMISS_FROM_PHONE: Byte = 0x02

    // ── Activity fetch (endpoint 0x004b, Huami fetch protocol) ────────────────

    /** Fetch handshake: start-date request. */
    const val FETCH_CMD_START_DATE: Byte = 0x01

    /** Fetch handshake: begin data transfer. */
    const val FETCH_CMD_FETCH_DATA: Byte = 0x02

    /** Fetch handshake: acknowledge transfer. */
    const val FETCH_CMD_ACK: Byte = 0x03

    /** Fetch data type: sleep sessions (594-byte records). */
    const val FETCH_TYPE_SLEEP_SESSION: Byte = 0x48

    /** Fetch data type: SpO2 samples (65-byte records). */
    const val FETCH_TYPE_SPO2_NORMAL: Byte = 0x25

    /** Fetch data type: automatic stress samples (1 byte/minute). */
    const val FETCH_TYPE_STRESS_AUTO: Byte = 0x13

    /** ACK byte: data received, drop from band. */
    const val FETCH_ACK_DROP: Byte = 0x01

    /** ACK byte: keep data on band (transfer failed). */
    const val FETCH_ACK_KEEP: Byte = 0x09

    // ── Encryption endpoint list ──────────────────────────────────────────────

    /**
     * Returns `true` for endpoints whose payloads must be AES-128/ECB encrypted once a
     * session key has been established.  Unencrypted endpoints (steps, HR push events,
     * device-info) can be received before authentication is complete.
     */
    fun isEncrypted(endpoint: Short): Boolean = when (endpoint) {
        ENDPOINT_BATTERY,
        ENDPOINT_CONNECTION,
        ENDPOINT_NOTIFICATION,
        ENDPOINT_ACTIVITY_FETCH,
        ENDPOINT_CONFIG,
        ENDPOINT_FIND_DEVICE,
        ENDPOINT_USER_INFO,
        0x0023.toShort(), // Workout
        0x003e.toShort(), // Connection parameters
        0x0018.toShort(), // Notification mirroring
        0x0031.toShort(), // Voice assistant (1)
        0x004c.toShort(), // Voice assistant (2)
        0x0042.toShort(), // Shortcut cards
        0x0019.toShort(), // Watch-face management
        0x003c.toShort(), // Vibration patterns
        0x0040.toShort(), // Display item ordering
        0x003f.toShort(), // Silent mode
        0x0041.toShort(), // World clocks
        0x0044.toShort(), // HTTP proxy
        0x0046.toShort(), // Contacts
        0x0049.toShort(), // Voice memos
        0x004d.toShort(), // Maps
        0x0033.toShort(), // Wi-Fi
        0x0034.toShort()  // FTP server
            -> true

        else -> false
    }

    // ── Message ───────────────────────────────────────────────────────────────

    /**
     * A fully reassembled and (if encrypted) decrypted logical message.
     *
     * @param endpoint  Service endpoint this message belongs to.
     * @param payload   Decrypted application payload bytes.
     * @param handle    Handle byte from the chunked header (echoed in ACKs).
     * @param count     Count byte from the final chunk (echoed in ACKs).
     */
    data class Message(
        val endpoint: Short,
        val payload: ByteArray,
        val handle: Byte,
        val count: Byte,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Message
            return endpoint == other.endpoint &&
                    payload.contentEquals(other.payload) &&
                    handle == other.handle &&
                    count == other.count
        }

        override fun hashCode(): Int {
            var result = endpoint.toInt()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + handle.toInt()
            result = 31 * result + count.toInt()
            return result
        }
    }

    // ── Encoder ───────────────────────────────────────────────────────────────

    /**
     * Encodes [payload] into one or more BLE packets using the 2021 extended header format.
     *
     * If [sessionKey] is non-null the payload is encrypted with AES-128/ECB.  The per-message
     * key is derived as `sessionKey[i] XOR handle`.  A 4-byte little-endian [encryptedSeq] and
     * a 4-byte CRC32 are appended to the plaintext before encryption.
     *
     * @param handle        Monotonically increasing handle byte (phone's outgoing counter).
     * @param endpoint      Destination service endpoint.
     * @param payload       Raw (unencrypted) application payload.
     * @param mtu           Negotiated ATT MTU in bytes (default 23).
     * @param sessionKey    16-byte session key, or null for unencrypted messages.
     * @param encryptedSeq  Sequence number embedded in the ciphertext (prevents replay).
     * @return              Ordered list of BLE packets ready to write to the device.
     */
    fun encode(
        handle: Byte,
        endpoint: Short,
        payload: ByteArray,
        mtu: Int = 23,
        sessionKey: ByteArray? = null,
        encryptedSeq: Int = 0,
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val encrypt = sessionKey != null
        val originalLength = payload.size

        val dataToSend: ByteArray = if (encrypt) {
            val messageKey = ByteArray(16) { i ->
                (sessionKey[i].toInt() xor (handle.toInt() and 0xFF)).toByte()
            }

            // Plaintext block: payload | seq(4 LE) | CRC32(payload|seq)(4 LE), padded to 16n
            var blockLen = originalLength + 8
            val overflow = blockLen % 16
            if (overflow > 0) blockLen += 16 - overflow

            val block = ByteArray(blockLen)
            System.arraycopy(payload, 0, block, 0, originalLength)
            ByteBuffer.wrap(block, originalLength, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(encryptedSeq)

            val crc = CRC32().also { it.update(block, 0, originalLength + 4) }
            ByteBuffer.wrap(block, originalLength + 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(crc.value.toInt())

            aesEcbEncrypt(messageKey, block)
        } else {
            payload
        }

        // ATT overhead is 3 bytes; the remaining budget goes to the GATT payload.
        // Capped like Gadgetbridge: MTU floor 23, payload ceiling 512.
        val effectiveMtu = minOf(512, maxOf(23, mtu) - 3)
        var offset = 0
        var count: Byte = 0
        var headerSize = 11  // First-packet header

        while (offset < dataToSend.size) {
            val isFirst = offset == 0
            val chunkSize = minOf(dataToSend.size - offset, effectiveMtu - headerSize)
            val isLast = offset + chunkSize >= dataToSend.size

            var flags = 0
            if (isFirst) flags = flags or 0x01
            if (isLast) flags = flags or 0x02 or 0x04   // Last always needs ACK
            if (encrypt) flags = flags or 0x08

            val packet = ByteBuffer.allocate(headerSize + chunkSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put(0x03.toByte())         // Packet type / magic
                    put(flags.toByte())
                    put(0x00.toByte())          // Reserved
                    put(handle)
                    put(count)
                    if (isFirst) {
                        putInt(originalLength)  // Unencrypted length for decoder buffer allocation
                        putShort(endpoint)
                    }
                    put(dataToSend, offset, chunkSize)
                }.array()

            packets.add(packet)
            offset += chunkSize
            count++
            headerSize = 5  // Continuation-packet header
        }
        return packets
    }

    // ── Decoder ───────────────────────────────────────────────────────────────

    /**
     * Stateful decoder that reassembles multi-packet chunked messages.
     *
     * A single instance should be used for the entire BLE session.  Call [decode] for every
     * notification received on the chunked-read characteristic (UUID 0x0017).
     *
     * **Not thread-safe** — call from a single coroutine/thread.
     */
    class Decoder {

        /**
         * AES-128 session key.  Must be set once authentication completes; until then
         * encrypted messages cannot be decoded and will be discarded.
         */
        @Volatile
        var sessionKey: ByteArray? = null

        private var currentHandle: Byte? = null
        private var lastCount: Int = -1
        private var currentEndpoint: Short = 0
        private var currentLength: Int = 0
        private var buffer: ByteBuffer? = null
        private var isEncryptedMessage = false

        /**
         * Result of processing one raw BLE notification.
         *
         * @param message   Fully reassembled message, or null when more packets are needed.
         * @param needsAck  Whether the phone must send an ACK to the band.
         * @param handle    Handle byte to echo in the ACK.
         * @param count     Count byte to echo in the ACK.
         */
        data class DecodeResult(
            val message: Message?,
            val needsAck: Boolean,
            val handle: Byte,
            val count: Byte,
        )

        /**
         * Process one raw BLE notification from the chunked-read characteristic.
         *
         * @return [DecodeResult] describing the outcome, or null if [data] is malformed.
         */
        fun decode(data: ByteArray): DecodeResult? {
            if (data.size < 5 || data[0] != 0x03.toByte()) return null

            val flags = data[1].toInt()
            val isFirst = (flags and 0x01) != 0
            val isLast = (flags and 0x02) != 0
            val needsAck = (flags and 0x04) != 0
            val encrypted = (flags and 0x08) != 0
            val handle = data[3]
            val count = data[4]

            var offset = 5
            if (isFirst) {
                reset()
                if (data.size < 11) return null

                currentLength = ByteBuffer.wrap(data, 5, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (currentLength < 0 || currentLength > 1_048_576) {
                    Timber.e("Huami2021: implausible payload length %d — discarding", currentLength)
                    return null
                }

                // Pre-allocate buffer for the entire (possibly padded) ciphertext
                val allocLen = if (encrypted) {
                    var enc = currentLength + 8
                    val r = enc % 16
                    if (r > 0) enc += 16 - r
                    enc
                } else {
                    currentLength
                }

                currentEndpoint = ByteBuffer.wrap(data, 9, 2).order(ByteOrder.LITTLE_ENDIAN).short
                currentHandle = handle
                lastCount = count.toInt() and 0xFF
                isEncryptedMessage = encrypted
                buffer = ByteBuffer.allocate(allocLen)
                offset = 11
            } else {
                if (handle != currentHandle || buffer == null) return null
                val c = count.toInt() and 0xFF
                if (c <= lastCount) {
                    // Duplicate or out-of-order chunk — acknowledge but don't advance state.
                    return DecodeResult(null, needsAck, handle, count)
                }
                lastCount = c
            }

            val chunkSize = data.size - offset
            if (chunkSize > 0) {
                if (buffer!!.remaining() < chunkSize) {
                    // Grow buffer if a size estimate was wrong (should be rare)
                    val grown = ByteBuffer.allocate(buffer!!.capacity() + maxOf(chunkSize, 128))
                    buffer!!.flip()
                    grown.put(buffer!!)
                    buffer = grown
                }
                buffer!!.put(data, offset, chunkSize)
            }

            var resultMsg: Message? = null
            if (isLast) {
                try {
                    var payload = buffer!!.array().copyOf(buffer!!.position())

                    if (isEncryptedMessage) {
                        val key = sessionKey
                        if (key == null) {
                            Timber.e(
                                "Huami2021: cannot decrypt endpoint 0x%04x — session key not set yet",
                                currentEndpoint.toInt() and 0xFFFF,
                            )
                            reset()
                            return null
                        }
                        val messageKey = ByteArray(16) { i ->
                            (key[i].toInt() xor (handle.toInt() and 0xFF)).toByte()
                        }
                        val decrypted = aesEcbDecrypt(messageKey, payload)
                        payload = decrypted.copyOf(currentLength) // Strip seq + CRC padding
                    }

                    resultMsg = Message(currentEndpoint, payload, handle, count)
                } catch (e: Exception) {
                    Timber.e(
                        e, "Huami2021: decode error for endpoint 0x%04x",
                        currentEndpoint.toInt() and 0xFFFF
                    )
                }
                reset()
            }

            return DecodeResult(resultMsg, needsAck, handle, count)
        }

        private fun reset() {
            currentHandle = null
            lastCount = -1
            currentEndpoint = 0
            currentLength = 0
            buffer = null
            isEncryptedMessage = false
        }
    }

    // ── AES helpers ───────────────────────────────────────────────────────────

    @SuppressLint("GetInstance")
    private fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    @SuppressLint("GetInstance")
    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }
}
