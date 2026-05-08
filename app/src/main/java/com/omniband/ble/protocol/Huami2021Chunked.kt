package com.omniband.ble.protocol

import android.annotation.SuppressLint
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Huami 2021 Chunked Transfer Protocol (ZeppOS)
 * ============================================
 * Used by Xiaomi Smart Band 7.
 */
object Huami2021Chunked {

    // ── Endpoints ────────────────────────────────────────────────────
    const val ENDPOINT_AUTH         : Short = 0x0082.toShort()
    const val ENDPOINT_FIND_DEVICE  : Short = 0x001a.toShort()
    const val ENDPOINT_HEARTRATE    : Short = 0x001d.toShort()
    const val ENDPOINT_BATTERY      : Short = 0x0029.toShort()
    const val ENDPOINT_TIME: Short = 0x0047.toShort()
    const val ENDPOINT_STEPS: Short = 0x0016.toShort()
    const val ENDPOINT_ACTIVITY_FETCH: Short = 0x004b.toShort()
    const val ENDPOINT_SPO2         : Short = 0x0045.toShort()
    const val ENDPOINT_DEVICE_INFO: Short = 0x0043.toShort()

    // ── Auth constants ──────────────────────────────────────────────
    const val AUTH_CMD_PUB_KEY      : Byte = 0x04
    const val AUTH_CMD_SESSION_KEY  : Byte = 0x05
    const val AUTH_RESP_PREFIX: Byte = 0x10
    const val AUTH_SUCCESS          : Byte = 0x01

    data class Message(
        val endpoint: Short,
        val payload: ByteArray,
        val handle: Byte,
        val count: Byte
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Message
            if (endpoint != other.endpoint) return false
            if (!payload.contentEquals(other.payload)) return false
            if (handle != other.handle) return false
            if (count != other.count) return false
            return true
        }

        override fun hashCode(): Int {
            var result = endpoint.toInt()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + handle.toInt()
            result = 31 * result + count.toInt()
            return result
        }
    }

    /**
     * Encode a message using the 2021 Extended Header format.
     */
    fun encode(
        handle: Byte,
        endpoint: Short,
        payload: ByteArray,
        mtu: Int = 23,
        sessionKey: ByteArray? = null,
        encryptedSeq: Int = 0
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        val encrypt = sessionKey != null
        val dataToSend: ByteArray
        val originalLength = payload.size

        if (encrypt) {
            val messageKey =
                ByteArray(16) { i -> (sessionKey!![i].toInt() xor handle.toInt()).toByte() }

            // Prepare payload for encryption: data + seq(4) + crc(4)
            var encryptedLen = originalLength + 8
            val overflow = encryptedLen % 16
            if (overflow > 0) encryptedLen += (16 - overflow)

            val encryptable = ByteArray(encryptedLen)
            System.arraycopy(payload, 0, encryptable, 0, originalLength)

            ByteBuffer.wrap(encryptable, originalLength, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(encryptedSeq)

            val crc = CRC32()
            crc.update(encryptable, 0, originalLength + 4)
            ByteBuffer.wrap(encryptable, originalLength + 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(crc.value.toInt())

            dataToSend = aesEcbEncrypt(messageKey, encryptable)
        } else {
            dataToSend = payload
        }

        var offset = 0
        var count: Byte = 0
        var headerSize = 11

        while (offset < dataToSend.size) {
            val isFirst = (offset == 0)
            val chunkSize = minOf(dataToSend.size - offset, mtu - headerSize)
            val isLast = (offset + chunkSize >= dataToSend.size)

            // Flags: 0x01=First, 0x02=Last, 0x04=NeedsAck, 0x08=Encrypted
            var flags = 0
            if (isFirst) flags = flags or 0x01
            if (isLast) flags = flags or 0x02 or 0x04
            if (encrypt) flags = flags or 0x08

            val packet = ByteBuffer.allocate(headerSize + chunkSize)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(0x03.toByte())
                    put(flags.toByte())
                    put(0x00.toByte()) // Padding
                    put(handle)
                    put(count)
                    if (isFirst) {
                        putInt(originalLength) // Header contains UNENCRYPTED length
                        putShort(endpoint)
                    }
                    put(dataToSend, offset, chunkSize)
                }.array()
            
            packets.add(packet)
            offset += chunkSize
            count++
            headerSize = 5 // Continuation header size
        }
        return packets
    }

    class Decoder {
        var sessionKey: ByteArray? = null
        
        private var currentHandle: Byte? = null
        private var currentEndpoint: Short = 0
        private var currentLength: Int = 0 // Original unencrypted length
        private var buffer: ByteBuffer? = null
        private var isEncryptedMessage = false

        data class DecodeResult(
            val message: Message?,
            val needsAck: Boolean,
            val handle: Byte,
            val count: Byte
        )

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
                if (data.size < 11) return null
                currentLength = ByteBuffer.wrap(data, 5, 4).order(ByteOrder.LITTLE_ENDIAN).int

                if (currentLength < 0 || currentLength > 1024 * 1024) {
                    Timber.e("Invalid Huami 2021 length: $currentLength")
                    return null
                }

                var allocLength = currentLength
                if (encrypted) {
                    var encLen = currentLength + 8
                    val overflow = encLen % 16
                    if (overflow > 0) encLen += (16 - overflow)
                    allocLength = encLen
                }

                currentEndpoint = ByteBuffer.wrap(data, 9, 2).order(ByteOrder.LITTLE_ENDIAN).short
                currentHandle = handle
                isEncryptedMessage = encrypted
                buffer = ByteBuffer.allocate(allocLength)
                offset = 11
            } else if (handle != currentHandle || buffer == null) {
                return null
            }

            val payloadSize = data.size - offset
            if (payloadSize > 0) {
                if (buffer!!.remaining() < payloadSize) {
                    val newSize = buffer!!.capacity() + maxOf(payloadSize, 128)
                    val newBuffer = ByteBuffer.allocate(newSize)
                    buffer!!.flip()
                    newBuffer.put(buffer!!)
                    buffer = newBuffer
                }
                buffer!!.put(data, offset, payloadSize)
            }

            var resultMsg: Message? = null
            if (isLast) {
                try {
                    var payload = buffer!!.array().copyOf(buffer!!.position())
                    if (isEncryptedMessage) {
                        val key = sessionKey ?: throw Exception("Session key missing")
                        val messageKey =
                            ByteArray(16) { i -> (key[i].toInt() xor handle.toInt()).toByte() }
                        val decrypted = aesEcbDecrypt(messageKey, payload)
                        payload = decrypted.copyOf(currentLength)
                    }
                    resultMsg = Message(currentEndpoint, payload, handle, count)
                } catch (e: Exception) {
                    Timber.e(e, "Huami 2021 decode error")
                }
                reset()
            }

            return DecodeResult(resultMsg, needsAck, handle, count)
        }

        private fun reset() {
            currentHandle = null
            buffer = null
            isEncryptedMessage = false
        }
    }

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
