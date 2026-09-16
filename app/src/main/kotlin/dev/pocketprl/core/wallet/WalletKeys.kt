package dev.pocketprl.core.wallet

import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.crypto.ExtKey
import dev.pocketprl.core.crypto.Hashes
import dev.pocketprl.core.crypto.Taproot
import dev.pocketprl.core.crypto.Xmss
import dev.pocketprl.core.crypto.wipe

const val BRANCH_EXTERNAL = 0
const val BRANCH_INTERNAL = 1

/**
 * Every derivation index yields two Taproot addresses from the same internal key:
 *
 *  * [PLAIN] – BIP-86 key path only, no script tree. What oyster's
 *    `getnewaddress` / `getrawchangeaddress` produce by default (`usePQ=false`)
 *    and the only variant its recovery scan derives; the default here too.
 *  * [PQ] – the same internal key with an `<xmss_pk> OP_CHECKXMSSSIG` tapleaf
 *    committed (`usePQ=true`). Watched and spendable, handed out only on request.
 */
object AddressVariant {
    const val PLAIN = 0
    const val PQ = 1
}

/** Public data for one derived wallet address. Safe to persist. */
class DerivedAddress(
    val branch: Int,
    val index: Int,
    val variant: Int,
    val address: String,
    val scriptPubKey: ByteArray,
    val internalPubKey: ByteArray,
    /** 32-byte tapscript root for [AddressVariant.PQ]; empty for [AddressVariant.PLAIN]. */
    val tapscriptRoot: ByteArray,
    val outputKey: ByteArray,
) {
    val path: String get() = "m/86'/…/0'/$branch/$index"
    val isPq: Boolean get() = variant == AddressVariant.PQ
}

/**
 * Holds the two account-level extended keys (BIP-86 Schnorr scope and the
 * purpose-222 post-quantum scope) for one wallet and derives addresses exactly
 * like oyster does:
 *
 *   internal key  = m/86'/coin'/0'/branch/index
 *   plain address = bech32m(hrp, v1, x(P + H_TapTweak(x(P))·G))
 *   pq key        = m/222'/coin'/0'/branch/index
 *   xmss seeds    = HKDF-SHA256(ikm = pq private key, info = "XMSS-SEED-EXPANSION")[0..96]
 *   leaf          = <xmss_pk> OP_CHECKXMSSSIG,  root = TapLeafHash(leaf)
 *   pq address    = bech32m(hrp, v1, x(P + H_TapTweak(x(P) || root)·G))
 */
class WalletKeys(seed: ByteArray, val network: Network) : AutoCloseable {
    private val schnorrAccount: ExtKey
    private val pqAccount: ExtKey

    init {
        val master = ExtKey.master(seed)
        try {
            schnorrAccount = master.derivePath(ExtKey.hardened(86), ExtKey.hardened(network.coinType), ExtKey.hardened(0))
            pqAccount = master.derivePath(ExtKey.hardened(222), ExtKey.hardened(network.coinType), ExtKey.hardened(0))
        } finally {
            master.close()
        }
    }

    /** Both variants for one index. Expensive (XMSS keygen, ~100k SHAKE256 calls); run off the main thread. */
    fun deriveAddresses(branch: Int, index: Int): Pair<DerivedAddress, DerivedAddress> {
        require(branch == BRANCH_EXTERNAL || branch == BRANCH_INTERNAL)
        val schnorrKey = schnorrAccount.derivePath(branch.toLong(), index.toLong())
        try {
            val internalPub = schnorrKey.publicKey()
            val plain = build(branch, index, AddressVariant.PLAIN, internalPub, ByteArray(0))
            val pq = build(branch, index, AddressVariant.PQ, internalPub, xmssRoot(branch, index))
            return plain to pq
        } finally {
            schnorrKey.close()
        }
    }

    /** Only the cheap desktop-compatible variant. */
    fun derivePlainAddress(branch: Int, index: Int): DerivedAddress {
        require(branch == BRANCH_EXTERNAL || branch == BRANCH_INTERNAL)
        val schnorrKey = schnorrAccount.derivePath(branch.toLong(), index.toLong())
        try {
            return build(branch, index, AddressVariant.PLAIN, schnorrKey.publicKey(), ByteArray(0))
        } finally {
            schnorrKey.close()
        }
    }

    /** Only the XMSS-committed variant. */
    fun derivePqAddress(branch: Int, index: Int): DerivedAddress = deriveAddresses(branch, index).second

    private fun build(branch: Int, index: Int, variant: Int, internalPub: ByteArray, root: ByteArray): DerivedAddress {
        val out = Taproot.outputKey(internalPub, root)
        return DerivedAddress(
            branch = branch,
            index = index,
            variant = variant,
            address = Address.encode(network, out.xOnly),
            scriptPubKey = Taproot.p2trScript(out.xOnly),
            internalPubKey = internalPub,
            tapscriptRoot = root,
            outputKey = out.xOnly,
        )
    }

    private fun xmssRoot(branch: Int, index: Int): ByteArray {
        val pqKey = pqAccount.derivePath(branch.toLong(), index.toLong())
        try {
            val ikm = pqKey.privateKey()
            val seeds = try { Hashes.hkdfSha256(ikm, null, HKDF_INFO, Xmss.PRIVATE_SEED_LEN + Xmss.PUBLIC_SEED_LEN) } finally { ikm.wipe() }
            val privSeed = seeds.copyOfRange(0, Xmss.PRIVATE_SEED_LEN)
            val pubSeed = seeds.copyOfRange(Xmss.PRIVATE_SEED_LEN, seeds.size)
            seeds.wipe()
            val xmssPk = try { Xmss.keygen(privSeed, pubSeed) } finally { privSeed.wipe(); pubSeed.wipe() }
            return Taproot.tapLeafHash(Taproot.xmssLeafScript(xmssPk))
        } finally {
            pqKey.close()
        }
    }

    /** 32-byte BIP-86 internal private key for signing (shared by both variants). Caller must wipe. */
    fun privateKey(branch: Int, index: Int): ByteArray {
        val k = schnorrAccount.derivePath(branch.toLong(), index.toLong())
        try {
            return k.privateKey()
        } finally {
            k.close()
        }
    }

    override fun close() {
        schnorrAccount.close()
        pqAccount.close()
    }

    companion object {
        private val HKDF_INFO = "XMSS-SEED-EXPANSION".toByteArray(Charsets.US_ASCII)
    }
}
