package dev.pocketprl.core.crypto

import java.util.stream.IntStream

/**
 * XMSS-SHAKE256_5_256 key generation, a faithful port of the XMSS reference
 * implementation as vendored by Pearl (xmss/external) with the wrapper
 * parameters from xmss/src/xmss.cpp:
 *
 *   n = 32, padding = 32, w = 16, len = 67, height = 5 (32 one-time keys)
 *
 * Pearl commits `<xmss_pk> OP_CHECKXMSSSIG` as a tapleaf inside every
 * receive/change address, so producing the exact public key is required just
 * to *derive addresses*. Signing with XMSS is not needed (spends use the
 * Schnorr key path), so only keygen is implemented.
 */
object Xmss {
    const val N = 32
    const val PADDING = 32
    const val WOTS_W = 16
    const val WOTS_LOG_W = 4
    const val WOTS_LEN1 = 64
    const val WOTS_LEN2 = 3
    const val WOTS_LEN = WOTS_LEN1 + WOTS_LEN2
    const val TREE_HEIGHT = 5
    const val LEAVES = 1 shl TREE_HEIGHT
    const val PRIVATE_SEED_LEN = 64
    const val PUBLIC_SEED_LEN = 32
    const val PK_LEN = 64

    private const val PAD_F = 0
    private const val PAD_H = 1
    private const val PAD_PRF = 3
    private const val PAD_PRF_KEYGEN = 4

    private const val ADDR_TYPE_OTS = 0
    private const val ADDR_TYPE_LTREE = 1
    private const val ADDR_TYPE_HASHTREE = 2

    /** Returns pk = root(32) || pubSeed(32). */
    fun keygen(privateSeed: ByteArray, publicSeed: ByteArray, parallel: Boolean = true): ByteArray {
        require(privateSeed.size == PRIVATE_SEED_LEN) { "private seed must be 64 bytes" }
        require(publicSeed.size == PUBLIC_SEED_LEN) { "public seed must be 32 bytes" }
        val skSeed = privateSeed.copyOfRange(0, N) // SK_PRF (bytes 32..64) is only used for signing.
        try {
            val leaves = arrayOfNulls<ByteArray>(LEAVES)
            val work = { i: Int -> leaves[i] = genLeaf(Ctx(), skSeed, publicSeed, i) }
            if (parallel) IntStream.range(0, LEAVES).parallel().forEach { work(it) }
            else for (i in 0 until LEAVES) work(i)

            // Merkle tree; node addresses use (tree_height = lower layer, tree_index = parent index).
            val ctx = Ctx()
            var layer = Array(LEAVES) { leaves[it]!! }
            val nodeAddr = IntArray(8)
            nodeAddr[3] = ADDR_TYPE_HASHTREE
            for (h in 0 until TREE_HEIGHT) {
                val next = Array(layer.size / 2) { ByteArray(N) }
                nodeAddr[5] = h
                for (i in next.indices) {
                    nodeAddr[6] = i
                    thashH(ctx, next[i], layer[2 * i], layer[2 * i + 1], publicSeed, nodeAddr)
                }
                layer = next
            }
            return concat(layer[0], publicSeed)
        } finally {
            skSeed.wipe()
        }
    }

    /** Per-thread scratch space so keygen does no allocation in the hot loop. */
    private class Ctx {
        val shake = Shake256()
        val addrBytes = ByteArray(32)
        val prfBuf = ByteArray(PADDING + N + 32)
        val keyBuf = ByteArray(N)
        val maskBuf = ByteArray(2 * N)
        val fBuf = ByteArray(PADDING + 2 * N)
        val hBuf = ByteArray(PADDING + 3 * N)
        val kgBuf = ByteArray(PADDING + 2 * N + 32)
        val expandIn = ByteArray(N + 32)
    }

    private fun addrToBytes(out: ByteArray, addr: IntArray) {
        for (i in 0 until 8) {
            val v = addr[i]
            out[4 * i] = (v ushr 24).toByte()
            out[4 * i + 1] = (v ushr 16).toByte()
            out[4 * i + 2] = (v ushr 8).toByte()
            out[4 * i + 3] = v.toByte()
        }
    }

    private fun setPadding(buf: ByteArray, value: Int) {
        buf.fill(0, 0, PADDING)
        buf[PADDING - 1] = value.toByte()
    }

    /** PRF(key, in32) = SHAKE256(toByte(3, 32) || key || in32). */
    private fun prf(ctx: Ctx, out: ByteArray, outOff: Int, in32: ByteArray, key: ByteArray) {
        val b = ctx.prfBuf
        setPadding(b, PAD_PRF)
        System.arraycopy(key, 0, b, PADDING, N)
        System.arraycopy(in32, 0, b, PADDING + N, 32)
        ctx.shake.reset().update(b).digest(out, outOff, N)
    }

    /** PRF_keygen(key, in) = SHAKE256(toByte(4, 32) || key || in(64)). */
    private fun prfKeygen(ctx: Ctx, out: ByteArray, outOff: Int, input: ByteArray, key: ByteArray) {
        val b = ctx.kgBuf
        setPadding(b, PAD_PRF_KEYGEN)
        System.arraycopy(key, 0, b, PADDING, N)
        System.arraycopy(input, 0, b, PADDING + N, N + 32)
        ctx.shake.reset().update(b).digest(out, outOff, N)
    }

    /** F: keyed, masked hash of one n-byte value. In-place on [buf] at [off]. */
    private fun thashF(ctx: Ctx, buf: ByteArray, off: Int, pubSeed: ByteArray, addr: IntArray) {
        val b = ctx.fBuf
        setPadding(b, PAD_F)
        addr[7] = 0
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, b, PADDING, ctx.addrBytes, pubSeed)
        addr[7] = 1
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, ctx.maskBuf, 0, ctx.addrBytes, pubSeed)
        for (i in 0 until N) b[PADDING + N + i] = (buf[off + i].toInt() xor ctx.maskBuf[i].toInt()).toByte()
        ctx.shake.reset().update(b).digest(buf, off, N)
    }

    /** H: keyed, masked hash of two n-byte values (left || right). */
    private fun thashH(ctx: Ctx, out: ByteArray, left: ByteArray, right: ByteArray, pubSeed: ByteArray, addr: IntArray) {
        val b = ctx.hBuf
        setPadding(b, PAD_H)
        addr[7] = 0
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, b, PADDING, ctx.addrBytes, pubSeed)
        addr[7] = 1
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, ctx.maskBuf, 0, ctx.addrBytes, pubSeed)
        addr[7] = 2
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, ctx.maskBuf, N, ctx.addrBytes, pubSeed)
        for (i in 0 until N) {
            b[PADDING + N + i] = (left[i].toInt() xor ctx.maskBuf[i].toInt()).toByte()
            b[PADDING + 2 * N + i] = (right[i].toInt() xor ctx.maskBuf[N + i].toInt()).toByte()
        }
        ctx.shake.reset().update(b).digest(out, 0, N)
    }

    /** Same as thashH but with both halves in one buffer (used by l-tree). */
    private fun thashHInPlace(ctx: Ctx, pk: ByteArray, dstOff: Int, srcOff: Int, pubSeed: ByteArray, addr: IntArray) {
        val b = ctx.hBuf
        setPadding(b, PAD_H)
        addr[7] = 0
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, b, PADDING, ctx.addrBytes, pubSeed)
        addr[7] = 1
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, ctx.maskBuf, 0, ctx.addrBytes, pubSeed)
        addr[7] = 2
        addrToBytes(ctx.addrBytes, addr)
        prf(ctx, ctx.maskBuf, N, ctx.addrBytes, pubSeed)
        for (i in 0 until 2 * N) b[PADDING + N + i] = (pk[srcOff + i].toInt() xor ctx.maskBuf[i].toInt()).toByte()
        ctx.shake.reset().update(b).digest(pk, dstOff, N)
    }

    /** WOTS+ public key generation (wots_pkgen). Writes len*n bytes into [pk]. */
    private fun wotsPkgen(ctx: Ctx, pk: ByteArray, skSeed: ByteArray, pubSeed: ByteArray, addr: IntArray) {
        // expand_seed: sk_i = PRF_keygen(pub_seed || addr(chain = i), sk_seed)
        addr[6] = 0 // hash addr
        addr[7] = 0 // key & mask
        System.arraycopy(pubSeed, 0, ctx.expandIn, 0, N)
        for (i in 0 until WOTS_LEN) {
            addr[5] = i
            addrToBytesAt(ctx.expandIn, N, addr)
            prfKeygen(ctx, pk, i * N, ctx.expandIn, skSeed)
        }
        // gen_chain for each chain: w-1 steps from position 0.
        for (i in 0 until WOTS_LEN) {
            addr[5] = i
            for (j in 0 until WOTS_W - 1) {
                addr[6] = j
                thashF(ctx, pk, i * N, pubSeed, addr)
            }
        }
    }

    private fun addrToBytesAt(out: ByteArray, off: Int, addr: IntArray) {
        for (i in 0 until 8) {
            val v = addr[i]
            out[off + 4 * i] = (v ushr 24).toByte()
            out[off + 4 * i + 1] = (v ushr 16).toByte()
            out[off + 4 * i + 2] = (v ushr 8).toByte()
            out[off + 4 * i + 3] = v.toByte()
        }
    }

    /** L-tree compression of a WOTS+ public key into one n-byte leaf (destroys [pk]). */
    private fun lTree(ctx: Ctx, leaf: ByteArray, pk: ByteArray, pubSeed: ByteArray, addr: IntArray) {
        var l = WOTS_LEN
        var height = 0
        addr[5] = height // tree height
        while (l > 1) {
            val parents = l ushr 1
            for (i in 0 until parents) {
                addr[6] = i // tree index
                thashHInPlace(ctx, pk, i * N, (2 * i) * N, pubSeed, addr)
            }
            if (l and 1 == 1) {
                System.arraycopy(pk, (l - 1) * N, pk, (l ushr 1) * N, N)
                l = (l ushr 1) + 1
            } else {
                l = l ushr 1
            }
            height++
            addr[5] = height
        }
        System.arraycopy(pk, 0, leaf, 0, N)
    }

    private fun genLeaf(ctx: Ctx, skSeed: ByteArray, pubSeed: ByteArray, idx: Int): ByteArray {
        val otsAddr = IntArray(8)
        otsAddr[3] = ADDR_TYPE_OTS
        otsAddr[4] = idx
        val ltreeAddr = IntArray(8)
        ltreeAddr[3] = ADDR_TYPE_LTREE
        ltreeAddr[4] = idx
        val pk = ByteArray(WOTS_LEN * N)
        wotsPkgen(ctx, pk, skSeed, pubSeed, otsAddr)
        val leaf = ByteArray(N)
        lTree(ctx, leaf, pk, pubSeed, ltreeAddr)
        return leaf
    }
}
