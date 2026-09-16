package dev.pocketprl.core.crypto

/**
 * SHAKE256 (FIPS 202) built on a self-contained Keccak-f[1600].
 *
 * Pearl's XMSS variant (XMSS-SHAKE256_5_256) needs ~100k short SHAKE256 calls
 * per address, so this implementation is allocation-free after construction:
 * one instance can be reused via [reset] and [update]/[digest]. Instances are
 * not thread-safe; use one per thread.
 */
class Shake256 {
    private val state = LongArray(25)
    private val block = ByteArray(RATE)
    private var pos = 0
    private var squeezing = false

    fun reset(): Shake256 {
        state.fill(0L)
        block.fill(0)
        pos = 0
        squeezing = false
        return this
    }

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size): Shake256 {
        check(!squeezing) { "cannot absorb after squeezing" }
        var i = off
        val end = off + len
        while (i < end) {
            val n = minOf(RATE - pos, end - i)
            System.arraycopy(data, i, block, pos, n)
            pos += n
            i += n
            if (pos == RATE) {
                absorbBlock()
                pos = 0
            }
        }
        return this
    }

    fun update(b: Int): Shake256 {
        check(!squeezing)
        block[pos++] = b.toByte()
        if (pos == RATE) {
            absorbBlock()
            pos = 0
        }
        return this
    }

    /** Squeezes [len] bytes into [out] at [off]. May be called repeatedly. */
    fun digest(out: ByteArray, off: Int = 0, len: Int = out.size - off) {
        if (!squeezing) {
            block.fill(0, pos, RATE)
            block[pos] = (block[pos].toInt() xor 0x1F).toByte()
            block[RATE - 1] = (block[RATE - 1].toInt() xor 0x80).toByte()
            absorbBlock()
            squeezing = true
            pos = 0
        }
        var o = off
        val end = off + len
        while (o < end) {
            if (pos == RATE) {
                keccakF1600(state)
                pos = 0
            }
            val n = minOf(RATE - pos, end - o)
            for (j in 0 until n) {
                val p = pos + j
                out[o + j] = (state[p ushr 3] ushr ((p and 7) shl 3)).toByte()
            }
            pos += n
            o += n
        }
    }

    fun digest(len: Int): ByteArray {
        val out = ByteArray(len)
        digest(out, 0, len)
        return out
    }

    private fun absorbBlock() {
        for (i in 0 until RATE / 8) {
            val o = i shl 3
            var lane = 0L
            for (j in 7 downTo 0) lane = (lane shl 8) or (block[o + j].toLong() and 0xff)
            state[i] = state[i] xor lane
        }
        keccakF1600(state)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    companion object {
        const val RATE = 136

        fun hash(input: ByteArray, outLen: Int): ByteArray = Shake256().update(input).digest(outLen)

        private val RC = ulongArrayOf(
            0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
            0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
            0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
            0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
            0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
            0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
        ).let { u -> LongArray(24) { u[it].toLong() } }

        internal fun keccakF1600(a: LongArray) {
            var a0 = a[0]
            var a1 = a[1]
            var a2 = a[2]
            var a3 = a[3]
            var a4 = a[4]
            var a5 = a[5]
            var a6 = a[6]
            var a7 = a[7]
            var a8 = a[8]
            var a9 = a[9]
            var a10 = a[10]
            var a11 = a[11]
            var a12 = a[12]
            var a13 = a[13]
            var a14 = a[14]
            var a15 = a[15]
            var a16 = a[16]
            var a17 = a[17]
            var a18 = a[18]
            var a19 = a[19]
            var a20 = a[20]
            var a21 = a[21]
            var a22 = a[22]
            var a23 = a[23]
            var a24 = a[24]
            for (round in 0 until 24) {
                val c0 = a0 xor a5 xor a10 xor a15 xor a20
                val c1 = a1 xor a6 xor a11 xor a16 xor a21
                val c2 = a2 xor a7 xor a12 xor a17 xor a22
                val c3 = a3 xor a8 xor a13 xor a18 xor a23
                val c4 = a4 xor a9 xor a14 xor a19 xor a24
                val d0 = c4 xor java.lang.Long.rotateLeft(c1, 1)
                val d1 = c0 xor java.lang.Long.rotateLeft(c2, 1)
                val d2 = c1 xor java.lang.Long.rotateLeft(c3, 1)
                val d3 = c2 xor java.lang.Long.rotateLeft(c4, 1)
                val d4 = c3 xor java.lang.Long.rotateLeft(c0, 1)
                a0 = a0 xor d0
                a1 = a1 xor d1
                a2 = a2 xor d2
                a3 = a3 xor d3
                a4 = a4 xor d4
                a5 = a5 xor d0
                a6 = a6 xor d1
                a7 = a7 xor d2
                a8 = a8 xor d3
                a9 = a9 xor d4
                a10 = a10 xor d0
                a11 = a11 xor d1
                a12 = a12 xor d2
                a13 = a13 xor d3
                a14 = a14 xor d4
                a15 = a15 xor d0
                a16 = a16 xor d1
                a17 = a17 xor d2
                a18 = a18 xor d3
                a19 = a19 xor d4
                a20 = a20 xor d0
                a21 = a21 xor d1
                a22 = a22 xor d2
                a23 = a23 xor d3
                a24 = a24 xor d4
                val b0 = a0
                val b10 = java.lang.Long.rotateLeft(a1, 1)
                val b20 = java.lang.Long.rotateLeft(a2, 62)
                val b5 = java.lang.Long.rotateLeft(a3, 28)
                val b15 = java.lang.Long.rotateLeft(a4, 27)
                val b16 = java.lang.Long.rotateLeft(a5, 36)
                val b1 = java.lang.Long.rotateLeft(a6, 44)
                val b11 = java.lang.Long.rotateLeft(a7, 6)
                val b21 = java.lang.Long.rotateLeft(a8, 55)
                val b6 = java.lang.Long.rotateLeft(a9, 20)
                val b7 = java.lang.Long.rotateLeft(a10, 3)
                val b17 = java.lang.Long.rotateLeft(a11, 10)
                val b2 = java.lang.Long.rotateLeft(a12, 43)
                val b12 = java.lang.Long.rotateLeft(a13, 25)
                val b22 = java.lang.Long.rotateLeft(a14, 39)
                val b23 = java.lang.Long.rotateLeft(a15, 41)
                val b8 = java.lang.Long.rotateLeft(a16, 45)
                val b18 = java.lang.Long.rotateLeft(a17, 15)
                val b3 = java.lang.Long.rotateLeft(a18, 21)
                val b13 = java.lang.Long.rotateLeft(a19, 8)
                val b14 = java.lang.Long.rotateLeft(a20, 18)
                val b24 = java.lang.Long.rotateLeft(a21, 2)
                val b9 = java.lang.Long.rotateLeft(a22, 61)
                val b19 = java.lang.Long.rotateLeft(a23, 56)
                val b4 = java.lang.Long.rotateLeft(a24, 14)
                a0 = b0 xor (b1.inv() and b2)
                a1 = b1 xor (b2.inv() and b3)
                a2 = b2 xor (b3.inv() and b4)
                a3 = b3 xor (b4.inv() and b0)
                a4 = b4 xor (b0.inv() and b1)
                a5 = b5 xor (b6.inv() and b7)
                a6 = b6 xor (b7.inv() and b8)
                a7 = b7 xor (b8.inv() and b9)
                a8 = b8 xor (b9.inv() and b5)
                a9 = b9 xor (b5.inv() and b6)
                a10 = b10 xor (b11.inv() and b12)
                a11 = b11 xor (b12.inv() and b13)
                a12 = b12 xor (b13.inv() and b14)
                a13 = b13 xor (b14.inv() and b10)
                a14 = b14 xor (b10.inv() and b11)
                a15 = b15 xor (b16.inv() and b17)
                a16 = b16 xor (b17.inv() and b18)
                a17 = b17 xor (b18.inv() and b19)
                a18 = b18 xor (b19.inv() and b15)
                a19 = b19 xor (b15.inv() and b16)
                a20 = b20 xor (b21.inv() and b22)
                a21 = b21 xor (b22.inv() and b23)
                a22 = b22 xor (b23.inv() and b24)
                a23 = b23 xor (b24.inv() and b20)
                a24 = b24 xor (b20.inv() and b21)
                a0 = a0 xor RC[round]
            }
            a[0] = a0
            a[1] = a1
            a[2] = a2
            a[3] = a3
            a[4] = a4
            a[5] = a5
            a[6] = a6
            a[7] = a7
            a[8] = a8
            a[9] = a9
            a[10] = a10
            a[11] = a11
            a[12] = a12
            a[13] = a13
            a[14] = a14
            a[15] = a15
            a[16] = a16
            a[17] = a17
            a[18] = a18
            a[19] = a19
            a[20] = a20
            a[21] = a21
            a[22] = a22
            a[23] = a23
            a[24] = a24
        }
    }
}
