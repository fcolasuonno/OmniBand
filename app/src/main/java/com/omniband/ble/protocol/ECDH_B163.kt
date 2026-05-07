@file:Suppress("FunctionName", "ClassName", "LocalVariableName")
package com.omniband.ble.protocol

/**
 * Pure Kotlin port of tiny-ECDH (B-163 curve).
 * Used by ZeppOS devices for authentication.
 */
object ECDH_B163 {
    private const val CURVE_DEGREE = 163
    const val ECC_PRV_KEY_SIZE = 24
    const val ECC_PUB_KEY_SIZE = 2 * ECC_PRV_KEY_SIZE
    private const val BITVEC_MARGIN = 3
    private const val BITVEC_NBITS = CURVE_DEGREE + BITVEC_MARGIN
    private const val BITVEC_NWORDS = (BITVEC_NBITS + 31) / 32
    private const val BITVEC_NBYTES = 4 * BITVEC_NWORDS

    private val polynomial = intArrayOf(0x000000c9, 0, 0, 0, 0, 0x00000008)
    private val coeff_b = intArrayOf(0x4a3205fd, 0x512f7874, 0x1481eb10, 0xb8c953ca.toInt(), 0x0a601907, 0x00000002)
    private val base_x = intArrayOf(0xe8343e36.toInt(), 0xd4994637.toInt(), 0xa0991168.toInt(), 0x86a2d57e.toInt(), 0xf0eba162.toInt(), 0x00000003)
    private val base_y = intArrayOf(0x797324f1, 0xb11c5c0c.toInt(), 0xa2cdd545.toInt(), 0x71a0094f, 0xd51fbc6c.toInt(), 0x00000000)
    private val base_order = intArrayOf(0xa4234c33.toInt(), 0x77e70c12, 0x000292fe, 0, 0, 0x00000004)

    private fun bitvec_get_bit(x: IntArray, idx: Int): Int =
        ((x[idx / 32].toLong() and 0xFFFFFFFFL) shr (idx and 31)).toInt() and 1

    private fun bitvec_clr_bit(x: IntArray, idx: Int) {
        x[idx / 32] = x[idx / 32] and (1 shl (idx and 31)).inv()
    }

    private fun bitvec_copy(x: IntArray, y: IntArray) {
        y.copyInto(x, 0, 0, BITVEC_NWORDS)
    }

    private fun bitvec_swap(x: IntArray, y: IntArray) {
        val tmp = IntArray(BITVEC_NWORDS)
        bitvec_copy(tmp, x)
        bitvec_copy(x, y)
        bitvec_copy(y, tmp)
    }

    private fun bitvec_equal(x: IntArray, y: IntArray): Boolean {
        for (i in 0 until BITVEC_NWORDS) if (x[i] != y[i]) return false
        return true
    }

    private fun bitvec_set_zero(x: IntArray) = x.fill(0)

    private fun bitvec_is_zero(x: IntArray): Boolean {
        for (i in 0 until BITVEC_NWORDS) if (x[i] != 0) return false
        return true
    }

    private fun bitvec_degree(x: IntArray): Int {
        var i = BITVEC_NWORDS * 32
        var y = BITVEC_NWORDS
        while (i > 0 && (x[--y] == 0)) i -= 32
        if (i != 0) {
            var u32mask = 1 shl 31
            while ((x[y] and u32mask) == 0) {
                u32mask = (u32mask.toLong() and 0xFFFFFFFFL shr 1).toInt()
                i -= 1
            }
        }
        return i
    }

    private fun bitvec_lshift(x: IntArray, y: IntArray, nbits: Int) {
        var shift = nbits
        val nwords = shift / 32
        for (i in 0 until nwords) x[i] = 0
        var i = nwords
        var j = 0
        while (i < BITVEC_NWORDS) {
            x[i] = y[j]
            i++
            j++
        }
        shift = shift and 31
        if (shift != 0) {
            for (k in BITVEC_NWORDS - 1 downTo 1) {
                x[k] = (x[k] shl shift) or ((x[k - 1].toLong() and 0xFFFFFFFFL) shr (32 - shift)).toInt()
            }
            x[0] = x[0] shl shift
        }
    }

    private fun gf2field_set_one(x: IntArray) {
        x[0] = 1
        for (i in 1 until BITVEC_NWORDS) x[i] = 0
    }

    private fun gf2field_is_one(x: IntArray): Boolean {
        if (x[0] != 1) return false
        for (i in 1 until BITVEC_NWORDS) if (x[i] != 0) return false
        return true
    }

    private fun gf2field_add(z: IntArray, x: IntArray, y: IntArray) {
        for (i in 0 until BITVEC_NWORDS) z[i] = x[i] xor y[i]
    }

    private fun gf2field_mul(z: IntArray, x: IntArray, y: IntArray) {
        val tmp = IntArray(BITVEC_NWORDS)
        bitvec_copy(tmp, x)
        if (bitvec_get_bit(y, 0) != 0) bitvec_copy(z, x) else bitvec_set_zero(z)
        for (i in 1 until CURVE_DEGREE) {
            bitvec_lshift(tmp, tmp, 1)
            if (bitvec_get_bit(tmp, CURVE_DEGREE) != 0) gf2field_add(tmp, tmp, polynomial)
            if (bitvec_get_bit(y, i) != 0) gf2field_add(z, z, tmp)
        }
    }

    private fun gf2field_inv(z: IntArray, x: IntArray) {
        val u = IntArray(BITVEC_NWORDS); bitvec_copy(u, x)
        val v = IntArray(BITVEC_NWORDS); bitvec_copy(v, polynomial)
        val g = IntArray(BITVEC_NWORDS); bitvec_set_zero(g)
        val h = IntArray(BITVEC_NWORDS)
        gf2field_set_one(z)
        while (!gf2field_is_one(u)) {
            var i = bitvec_degree(u) - bitvec_degree(v)
            if (i < 0) {
                bitvec_swap(u, v); bitvec_swap(g, z); i = -i
            }
            bitvec_lshift(h, v, i); gf2field_add(u, u, h)
            bitvec_lshift(h, g, i); gf2field_add(z, z, h)
        }
    }

    private fun gf2point_double(x: IntArray, y: IntArray) {
        if (bitvec_is_zero(x)) {
            bitvec_set_zero(y)
        } else {
            val l = IntArray(BITVEC_NWORDS)
            gf2field_inv(l, x)
            gf2field_mul(l, l, y)
            gf2field_add(l, l, x)
            gf2field_mul(y, x, x)
            gf2field_mul(x, l, l)
            x[0] = x[0] xor 1 // inc
            gf2field_add(x, x, l)
            gf2field_mul(l, l, x)
            gf2field_add(y, y, l)
        }
    }

    private fun gf2point_add(x1: IntArray, y1: IntArray, x2: IntArray, y2: IntArray) {
        if (!bitvec_is_zero(x2) || !bitvec_is_zero(y2)) {
            if (bitvec_is_zero(x1) && bitvec_is_zero(y1)) {
                bitvec_copy(x1, x2); bitvec_copy(y1, y2)
            } else if (bitvec_equal(x1, x2)) {
                if (bitvec_equal(y1, y2)) gf2point_double(x1, y1) else {
                    bitvec_set_zero(x1); bitvec_set_zero(y1)
                }
            } else {
                val a = IntArray(BITVEC_NWORDS); val b = IntArray(BITVEC_NWORDS)
                val c = IntArray(BITVEC_NWORDS); val d = IntArray(BITVEC_NWORDS)
                gf2field_add(a, y1, y2); gf2field_add(b, x1, x2)
                gf2field_inv(c, b); gf2field_mul(c, c, a)
                gf2field_mul(d, c, c); gf2field_add(d, d, c)
                gf2field_add(d, d, b); d[0] = d[0] xor 1 // inc
                gf2field_add(x1, x1, d); gf2field_mul(a, x1, c)
                gf2field_add(a, a, d); gf2field_add(y1, y1, a)
                bitvec_copy(x1, d)
            }
        }
    }

    private fun gf2point_mul(x: IntArray, y: IntArray, exp: IntArray) {
        val tx = IntArray(BITVEC_NWORDS); val ty = IntArray(BITVEC_NWORDS)
        val nbits = bitvec_degree(exp)
        for (i in nbits - 1 downTo 0) {
            gf2point_double(tx, ty)
            if (bitvec_get_bit(exp, i) != 0) gf2point_add(tx, ty, x, y)
        }
        bitvec_copy(x, tx); bitvec_copy(y, ty)
    }

    private fun gf2point_on_curve(x: IntArray, y: IntArray): Boolean {
        if (bitvec_is_zero(x) && bitvec_is_zero(y)) return false
        val a = IntArray(BITVEC_NWORDS); val b = IntArray(BITVEC_NWORDS)
        gf2field_mul(a, x, x); gf2field_mul(b, a, x); gf2field_add(a, a, b)
        gf2field_add(a, a, coeff_b); gf2field_mul(b, y, y); gf2field_add(a, a, b)
        gf2field_mul(b, x, y)
        return bitvec_equal(a, b)
    }

    private fun bytesToInts(bytes: ByteArray, offset: Int): IntArray {
        val res = IntArray(BITVEC_NWORDS)
        for (i in 0 until BITVEC_NWORDS) {
            val b = offset + i * 4
            res[i] = (bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                     ((bytes[b + 2].toInt() and 0xFF) shl 16) or ((bytes[b + 3].toInt() and 0xFF) shl 24)
        }
        return res
    }

    private fun intsToBytes(ints: IntArray, bytes: ByteArray, offset: Int) {
        for (i in 0 until BITVEC_NWORDS) {
            val b = offset + i * 4
            bytes[b] = (ints[i] and 0xFF).toByte()
            bytes[b + 1] = ((ints[i] shr 8) and 0xFF).toByte()
            bytes[b + 2] = ((ints[i] shr 16) and 0xFF).toByte()
            bytes[b + 3] = ((ints[i] shr 24) and 0xFF).toByte()
        }
    }

    fun generatePublic(privateKey: ByteArray): ByteArray? {
        val prv = bytesToInts(privateKey, 0)
        if (bitvec_degree(prv) < (CURVE_DEGREE / 2)) return null
        val nbits = bitvec_degree(base_order)
        for (i in nbits - 1 until BITVEC_NWORDS * 32) bitvec_clr_bit(prv, i)

        val px = base_x.copyOf(); val py = base_y.copyOf()
        gf2point_mul(px, py, prv)
        val res = ByteArray(ECC_PUB_KEY_SIZE)
        intsToBytes(px, res, 0); intsToBytes(py, res, BITVEC_NBYTES)
        return res
    }

    fun generateShared(privateKey: ByteArray, otherPub: ByteArray): ByteArray? {
        val prv = bytesToInts(privateKey, 0)
        val ox = bytesToInts(otherPub, 0); val oy = bytesToInts(otherPub, BITVEC_NBYTES)
        if (bitvec_is_zero(ox) && bitvec_is_zero(oy)) return null
        if (!gf2point_on_curve(ox, oy)) return null

        val nbits = bitvec_degree(base_order)
        for (i in nbits - 1 until BITVEC_NWORDS * 32) bitvec_clr_bit(prv, i)

        gf2point_mul(ox, oy, prv)
        val res = ByteArray(ECC_PUB_KEY_SIZE)
        intsToBytes(ox, res, 0); intsToBytes(oy, res, BITVEC_NBYTES)
        return res
    }
}
