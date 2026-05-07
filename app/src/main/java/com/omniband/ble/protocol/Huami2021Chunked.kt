package com.omniband.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Huami 2021 Chunked Transfer Protocol (ZeppOS)
 * ============================================
 * Used by Xiaomi Smart Band 7.
 *
 * This version implements the 11-byte "Extended 2021" header format
 * and handles the NEEDS_ACK (0x04) flag required by newer firmware.
 */
object Huami2021Chunked {

    // ── Endpoints ────────────────────────────────────────────────────
    const val ENDPOINT_AUTH         : Short = 0x0082.toShort()
    const val ENDPOINT_FIND_DEVICE  : Short = 0x001a.toShort()
    const val ENDPOINT_HEARTRATE    : Short = 0x001d.toShort()
    const val ENDPOINT_BATTERY      : Short = 0x0029.toShort()
    const val ENDPOINT_SET_TIME     : Short = 0x0003.toShort()
    const val ENDPOINT_ACTIVITY     : Short = 0x0006.toShort()
    const val ENDPOINT_SLEEP        : Short = 0x0008.toShort()
    const val ENDPOINT_SPO2         : Short = 0x0045.toShort()

    // ── Auth constants ──────────────────────────────────────────────
    const val AUTH_CMD_PUB_KEY      : Byte = 0x04
    const val AUTH_CMD_SESSION_KEY  : Byte = 0x05
    const val AUTH_RESP_PREFIX      : Byte = 0x10 // Response indicator
    const val AUTH_SUCCESS          : Byte = 0x01

    /**
     * Encode a message using the 2021 Extended Header format.
     * First: [0x03][flags][0x00][handle][count][len:4][ep:2] = 11 bytes
     * Cont:  [0x03][flags][0x00][handle][count]             = 5 bytes
     */
    fun encode(
        handle: Byte,
        endpoint: Short,
        payload: ByteArray,
        mtu: Int = 23
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var count: Byte = 0

        while (offset < payload.size) {
            val isFirst = (offset == 0)
            val headerSize = if (isFirst) 11 else 5
            val chunkSize = minOf(payload.size - offset, mtu - headerSize)
            val isLast = (offset + chunkSize >= payload.size)

            // Flags: 0x01=First, 0x02=Last, 0x04=NeedsAck
            var flags = 0
            if (isFirst) flags = flags or 0x01
            if (isLast)  flags = flags or 0x02 or 0x04 

            val packet = ByteBuffer.allocate(headerSize + chunkSize)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    put(0x03.toByte())
                    put(flags.toByte())
                    put(0x00.toByte()) // Extended padding
                    put(handle)
                    put(count)
                    if (isFirst) {
                        putInt(payload.size)
                        putShort(endpoint)
                    }
                    put(payload, offset, chunkSize)
                }.array()
            
            packets.add(packet)
            offset += chunkSize
            count++
        }
        return packets
    }

    class Decoder {
        private var currentHandle: Byte? = null
        private var currentEndpoint: Short = 0
        private var currentLength: Int = 0
        private var buffer: ByteBuffer? = null

        data class Message(
            val endpoint: Short, 
            val payload: ByteArray, 
            val handle: Byte, 
            val count: Byte, 
            val needsAck: Boolean
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Message
                if (endpoint != other.endpoint) return false
                if (!payload.contentEquals(other.payload)) return false
                if (handle != other.handle) return false
                if (count != other.count) return false
                if (needsAck != other.needsAck) return false
                return true
            }

            override fun hashCode(): Int {
                var result = endpoint.toInt()
                result = 31 * result + payload.contentHashCode()
                result = 31 * result + handle.toInt()
                result = 31 * result + count.toInt()
                result = 31 * result + (if (needsAck) 1 else 0)
                return result
            }
        }

        fun decode(data: ByteArray): Message? {
            if (data.size < 5 || data[0] != 0x03.toByte()) return null

            val flags = data[1].toInt()
            val isFirst = (flags and 0x01) != 0
            val isLast = (flags and 0x02) != 0
            val needsAck = (flags and 0x04) != 0
            val handle = data[3]
            val count = data[4]

            var offset = 5
            if (isFirst) {
                if (data.size < 11) return null
                currentLength = (data[5].toInt() and 0xFF) or
                                ((data[6].toInt() and 0xFF) shl 8) or
                                ((data[7].toInt() and 0xFF) shl 16) or
                                ((data[8].toInt() and 0xFF) shl 24)
                currentEndpoint = ((data[9].toInt() and 0xFF) or ((data[10].toInt() and 0xFF) shl 8)).toShort()
                currentHandle = handle
                buffer = ByteBuffer.allocate(currentLength)
                offset = 11
            } else if (handle != currentHandle || buffer == null) {
                return null
            }

            val payloadSize = data.size - offset
            if (payloadSize > 0) {
                buffer!!.put(data, offset, payloadSize)
            }

            return if (isLast) {
                val msg = Message(currentEndpoint, buffer!!.array(), handle, count, needsAck)
                reset()
                msg
            } else null
        }

        private fun reset() {
            currentHandle = null
            buffer = null
        }
    }
}
