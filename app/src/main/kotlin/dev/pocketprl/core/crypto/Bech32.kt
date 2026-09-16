package dev.pocketprl.core.crypto

/** Bech32 / Bech32m (BIP-173 / BIP-350) segwit address codec. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    class Decoded(val hrp: String, val witnessVersion: Int, val program: ByteArray)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return out
    }

    private fun createChecksum(hrp: String, data: IntArray, const: Int): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor const
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and 0xff)
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    private fun convertBitsToBytes(data: IntArray, from: Int, to: Int): ByteArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            if (v < 0 || v ushr from != 0) return null
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return out.toByteArray()
    }

    fun encodeSegwit(hrp: String, witnessVersion: Int, program: ByteArray): String {
        require(witnessVersion in 0..16)
        val data = intArrayOf(witnessVersion) + (convertBits(program, 8, 5, true) ?: error("convertBits"))
        val const = if (witnessVersion == 0) BECH32_CONST else BECH32M_CONST
        val checksum = createChecksum(hrp, data, const)
        val sb = StringBuilder(hrp).append('1')
        for (d in data + checksum) sb.append(CHARSET[d])
        return sb.toString()
    }

    /** Decodes a segwit address; returns null on any error. */
    fun decodeSegwit(address: String): Decoded? {
        if (address.length < 8 || address.length > 90) return null
        val hasLower = address.any { it.isLowerCase() }
        val hasUpper = address.any { it.isUpperCase() }
        if (hasLower && hasUpper) return null
        val s = address.lowercase()
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) return null
        val hrp = s.substring(0, pos)
        if (hrp.any { it.code < 33 || it.code > 126 }) return null
        // version + at least one program character + 6 checksum characters
        if (s.length - pos - 1 < 8) return null
        val data = IntArray(s.length - pos - 1)
        for (i in data.indices) {
            val c = CHARSET.indexOf(s[pos + 1 + i])
            if (c < 0) return null
            data[i] = c
        }
        val check = polymod(hrpExpand(hrp) + data)
        val version = data[0]
        val expected = if (version == 0) BECH32_CONST else BECH32M_CONST
        if (check != expected) return null
        if (version > 16) return null
        val program = convertBitsToBytes(data.copyOfRange(1, data.size - 6), 5, 8) ?: return null
        if (program.size < 2 || program.size > 40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null
        return Decoded(hrp, version, program)
    }
}
