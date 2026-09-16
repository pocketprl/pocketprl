package dev.pocketprl.core.crypto

import java.math.BigInteger

/**
 * BIP-32 private derivation reproducing btcd/hdkeychain's `DeriveNonStandard`,
 * which is what the Pearl wallet (oyster/waddrmgr) uses for every level.
 *
 * The quirk: derived private keys are kept as minimal big-endian integers
 * (leading zero bytes stripped). For *hardened* children the HMAC input is
 * `0x00 || key || zero padding to 33 bytes || index` instead of the BIP-32
 * `0x00 || ser256(key) || index`. Whenever a parent key happens to start with a
 * zero byte (~1 in 256 per level) the two schemes diverge. The master key is
 * the raw 32-byte HMAC output and is never stripped.
 */
class ExtKey private constructor(
    /** Raw key bytes exactly as hdkeychain would store them (1..32 bytes). */
    private val rawKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
) : AutoCloseable {

    /** 32-byte private key. Caller owns the copy and should wipe it. */
    fun privateKey(): ByteArray = rawKey.padStart(32)

    /** 33-byte compressed public key. */
    fun publicKey(): ByteArray {
        val p = privateKey()
        try {
            return Secp.pubkey(p)
        } finally {
            p.wipe()
        }
    }

    val rawKeyLength: Int get() = rawKey.size

    fun deriveNonStandard(index: Long): ExtKey {
        require(index in 0..0xffffffffL)
        require(depth < 255) { "derive beyond max depth" }
        val hardened = index >= HARDENED
        val data = ByteArray(37)
        if (hardened) {
            // btcd copies the un-padded key right after the 0x00 byte.
            System.arraycopy(rawKey, 0, data, 1, rawKey.size)
        } else {
            System.arraycopy(publicKey(), 0, data, 0, 33)
        }
        data[33] = (index ushr 24).toByte()
        data[34] = (index ushr 16).toByte()
        data[35] = (index ushr 8).toByte()
        data[36] = index.toByte()

        val i = Hashes.hmacSha512(chainCode, data)
        data.wipe()
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)
        i.wipe()

        val ilNum = BigInteger(1, il)
        if (ilNum >= CURVE_N || ilNum.signum() == 0) {
            il.wipe(); ir.wipe()
            throw IllegalStateException("invalid child key at index $index")
        }
        val parent = privateKey()
        val child = try {
            Secp.privAdd(parent, il)
        } finally {
            parent.wipe(); il.wipe()
        }
        val stripped = child.stripLeadingZeros()
        child.wipe()
        return ExtKey(stripped, ir, depth + 1)
    }

    fun derivePath(vararg indices: Long): ExtKey {
        var k = this
        for (idx in indices) {
            val next = k.deriveNonStandard(idx)
            if (k !== this) k.close()
            k = next
        }
        return k
    }

    override fun close() {
        rawKey.wipe()
        chainCode.wipe()
    }

    companion object {
        const val HARDENED = 0x80000000L
        val CURVE_N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        private val MASTER_KEY = "Bitcoin seed".toByteArray(Charsets.US_ASCII)

        fun hardened(i: Int): Long = HARDENED + i

        fun master(seed: ByteArray): ExtKey {
            require(seed.size in 16..64) { "seed must be 16..64 bytes" }
            val i = Hashes.hmacSha512(MASTER_KEY, seed)
            val il = i.copyOfRange(0, 32)
            val ir = i.copyOfRange(32, 64)
            i.wipe()
            val n = BigInteger(1, il)
            if (n >= CURVE_N || n.signum() == 0) {
                il.wipe(); ir.wipe()
                throw IllegalStateException("unusable seed")
            }
            return ExtKey(il, ir, 0)
        }
    }
}
