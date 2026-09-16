package dev.pocketprl.core.crypto

/** BIP-341 helpers for Pearl's XMSS-committed Taproot addresses. */
object Taproot {
    const val OP_CHECKXMSSSIG = 0xde
    const val BASE_LEAF_VERSION = 0xc0

    /** `<xmss_pk(64)> OP_CHECKXMSSSIG` exactly as oyster's ScriptBuilder emits it. */
    fun xmssLeafScript(xmssPubKey: ByteArray): ByteArray {
        require(xmssPubKey.size == Xmss.PK_LEN)
        return concat(byteArrayOf(0x40), xmssPubKey, byteArrayOf(OP_CHECKXMSSSIG.toByte()))
    }

    fun tapLeafHash(script: ByteArray, leafVersion: Int = BASE_LEAF_VERSION): ByteArray {
        val w = ByteWriter(script.size + 8)
        w.u8(leafVersion)
        w.varBytes(script)
        return Hashes.taggedHash("TapLeaf", w.toByteArray())
    }

    fun tapTweak(xOnlyInternal: ByteArray, merkleRoot: ByteArray?): ByteArray {
        require(xOnlyInternal.size == 32)
        return if (merkleRoot == null || merkleRoot.isEmpty()) Hashes.taggedHash("TapTweak", xOnlyInternal)
        else Hashes.taggedHash("TapTweak", xOnlyInternal, merkleRoot)
    }

    class OutputKey(val xOnly: ByteArray, val parityOdd: Boolean)

    /** Q = lift_x(P) + t*G for a compressed internal key. */
    fun outputKey(internalPub33: ByteArray, merkleRoot: ByteArray?): OutputKey {
        require(internalPub33.size == 33)
        val even = if (Secp.hasOddY(internalPub33)) Secp.pubNegate(internalPub33) else internalPub33
        val t = tapTweak(Secp.xOnly(even), merkleRoot)
        val q = Secp.pubAdd(even, t) // 65 bytes uncompressed
        require(q.size == 65)
        return OutputKey(q.copyOfRange(1, 33), (q[64].toInt() and 1) == 1)
    }

    /** BIP-341 key-path private key: negate if P has odd y, then add the tweak. */
    fun tweakPrivateKey(priv32: ByteArray, merkleRoot: ByteArray?): ByteArray {
        val pub = Secp.pubkey(priv32)
        val d = if (Secp.hasOddY(pub)) Secp.privNegate(priv32) else priv32.copyOf()
        val evenPub = if (Secp.hasOddY(pub)) Secp.pubNegate(pub) else pub
        val t = tapTweak(Secp.xOnly(evenPub), merkleRoot)
        try {
            return Secp.privAdd(d, t)
        } finally {
            d.wipe()
        }
    }

    /** OP_1 <32-byte key> */
    fun p2trScript(xOnly: ByteArray): ByteArray {
        require(xOnly.size == 32)
        return concat(byteArrayOf(0x51, 0x20), xOnly)
    }

    fun isP2tr(script: ByteArray): Boolean = script.size == 34 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte()
}
