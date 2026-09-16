package dev.pocketprl.core.wallet

import dev.pocketprl.Vectors
import dev.pocketprl.arr
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.chain.OutPoint
import dev.pocketprl.core.chain.TaprootSigner
import dev.pocketprl.core.chain.Transaction
import dev.pocketprl.core.chain.TxIn
import dev.pocketprl.core.chain.TxOut
import dev.pocketprl.core.crypto.Bip39
import dev.pocketprl.core.crypto.ExtKey
import dev.pocketprl.core.crypto.Hashes
import dev.pocketprl.core.crypto.Secp
import dev.pocketprl.core.crypto.Taproot
import dev.pocketprl.core.crypto.Xmss
import dev.pocketprl.core.crypto.hexToBytes
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.int
import dev.pocketprl.long
import dev.pocketprl.objOrNull
import dev.pocketprl.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte conformance against values produced by the real Pearl wallet
 * (oyster) code path. See tools/vectors/.
 *
 * Every index has two addresses in oyster: the plain BIP-86 key-path address
 * (`getnewaddress` default, what the desktop wallet uses) and the same key with
 * the XMSS tapleaf committed (`getnewaddress … true`). Both are checked.
 */
class ConformanceTest {

    @Test
    fun bip32NonStandardDerivationMatchesHdkeychain() {
        for (v in Vectors.bip32) {
            val o = v.jsonObject
            val note = o.str("note")
            val levels = o.arr("levels")
            var k = ExtKey.master(o.str("seedHex").hexToBytes())
            for ((i, lvl) in levels.withIndex()) {
                if (i > 0) k = k.deriveNonStandard(lvl.long("index"))
                assertEquals("$note level $i privKey", lvl.str("privKey"), k.privateKey().toHex())
                assertEquals("$note level $i chainCode", lvl.str("chainCode"), k.chainCode.toHex())
                assertEquals("$note level $i rawKeyLen", lvl.int("rawKeyLen"), k.rawKeyLength)
            }
        }
    }

    @Test
    fun mnemonicsProduceOysterSeeds() {
        for (w in Vectors.wallets) {
            val m = w["mnemonic"]?.let { w.str("mnemonic") } ?: continue
            assertEquals(w.str("name"), w.str("seedHex"), Bip39.toSeed(m).toHex())
        }
    }

    @Test
    fun addressesMatchOysterForEveryVectorWallet() {
        var plainChecked = 0
        for (w in Vectors.wallets) {
            val name = w.str("name")
            val network = Network.fromId(w.str("network"))
            assertEquals(name, w.str("hrp"), network.hrp)
            assertEquals(name, w.int("coinType"), network.coinType)
            val seed = w.str("seedHex").hexToBytes()
            WalletKeys(seed, network).use { keys ->
                for (a in w.arr("addresses")) {
                    val branch = a.int("branch")
                    val index = a.int("index")
                    val tag = "$name $branch/$index"

                    // Low-level pieces first so a failure points at the right layer.
                    val priv = keys.privateKey(branch, index)
                    assertEquals("$tag internal priv", a.str("privKey"), priv.toHex())
                    assertEquals("$tag internal pub", a.str("internalPubKey"), Secp.pubkey(priv).toHex())

                    val master = ExtKey.master(seed)
                    val pq = master.derivePath(
                        ExtKey.hardened(222), ExtKey.hardened(network.coinType), ExtKey.hardened(0),
                        branch.toLong(), index.toLong(),
                    )
                    assertEquals("$tag pq priv", a.str("pqPrivKey"), pq.privateKey().toHex())
                    val seeds = Hashes.hkdfSha256(pq.privateKey(), null, "XMSS-SEED-EXPANSION".toByteArray(), 96)
                    assertEquals("$tag xmss priv seed", a.str("xmssPrivSeed"), seeds.copyOfRange(0, 64).toHex())
                    assertEquals("$tag xmss pub seed", a.str("xmssPubSeed"), seeds.copyOfRange(64, 96).toHex())
                    val xpk = Xmss.keygen(seeds.copyOfRange(0, 64), seeds.copyOfRange(64, 96))
                    assertEquals("$tag xmss pk", a.str("xmssPubKey"), xpk.toHex())
                    assertEquals("$tag root", a.str("tapscriptRoot"), Taproot.tapLeafHash(Taproot.xmssLeafScript(xpk)).toHex())

                    // Then the whole pipeline, both variants at once.
                    val (plain, pqAddr) = keys.deriveAddresses(branch, index)
                    assertEquals(AddressVariant.PQ, pqAddr.variant)
                    assertEquals("$tag root", a.str("tapscriptRoot"), pqAddr.tapscriptRoot.toHex())
                    assertEquals("$tag output key", a.str("outputKey"), pqAddr.outputKey.toHex())
                    assertEquals("$tag pkScript", a.str("pkScript"), pqAddr.scriptPubKey.toHex())
                    assertEquals("$tag address", a.str("address"), pqAddr.address)
                    val parsed = Address.parse(pqAddr.address, network) as Address.Result.Valid
                    assertEquals(a.str("pkScript"), parsed.parsed.scriptPubKey.toHex())

                    assertEquals(AddressVariant.PLAIN, plain.variant)
                    assertTrue("$tag plain has no root", plain.tapscriptRoot.isEmpty())
                    assertEquals("$tag plain output key", a.str("plainOutputKey"), plain.outputKey.toHex())
                    assertEquals("$tag plain pkScript", a.str("plainPkScript"), plain.scriptPubKey.toHex())
                    assertEquals("$tag plain address", a.str("plainAddress"), plain.address)
                    assertNotEquals("$tag variants must differ", plain.address, pqAddr.address)
                    assertEquals("$tag derivePlainAddress agrees", plain.address, keys.derivePlainAddress(branch, index).address)
                    assertEquals("$tag shared internal key", plain.internalPubKey.toHex(), pqAddr.internalPubKey.toHex())
                    plainChecked++
                }
            }
        }
        assertTrue(plainChecked >= 10)
    }

    @Test
    fun keyPathSpendsMatchOysterAndVerifyAgainstItsSignatures() {
        var checked = 0
        for (w in Vectors.wallets) {
            w.objOrNull("spend")?.let { checkSpend(w, it, plain = false); checked++ }
            w.objOrNull("spendPlain")?.let { checkSpend(w, it, plain = true); checked++ }
        }
        assertTrue("expected both PQ and plain spend vectors", checked >= 4)
    }

    private fun checkSpend(w: JsonObject, sp: JsonObject, plain: Boolean) {
        val name = w.str("name") + if (plain) " (plain)" else " (pq)"
        val network = Network.fromId(w.str("network"))
        val seed = w.str("seedHex").hexToBytes()
        val inputs = sp.arr("inputs")
        val outputs = sp.arr("outputs")

        val tx = Transaction(
            version = sp.int("version"),
            inputs = inputs.map { TxIn(OutPoint.fromHex(it.str("prevTxid"), it.long("prevVout"))) },
            outputs = outputs.map { TxOut(it.long("value"), it.str("pkScript").hexToBytes()) },
            lockTime = sp.long("lockTime"),
        )
        assertEquals("$name unsigned tx", sp.str("unsignedTxHex"), tx.serialize(withWitness = false).toHex())
        val prevOuts = inputs.map { TxOut(it.long("value"), it.str("pkScript").hexToBytes()) }
        assertEquals("$name vsize estimate", sp.long("vsize"), Transaction.estimateVsize(inputs.size, outputs.size).toLong())

        WalletKeys(seed, network).use { keys ->
            for ((i, inp) in inputs.withIndex()) {
                val tag = "$name input $i"
                val sighash = TaprootSigner.sighashKeyPath(tx, i, prevOuts)
                assertEquals("$tag sighash", inp.str("sighash"), sighash.toHex())

                val branch = inp.int("branch")
                val index = inp.int("index")
                val d = if (plain) keys.derivePlainAddress(branch, index) else keys.derivePqAddress(branch, index)
                assertEquals("$tag output key", inp.str("outputKey"), d.outputKey.toHex())
                assertEquals("$tag prevout script", inp.str("pkScript"), d.scriptPubKey.toHex())
                val priv = keys.privateKey(branch, index)
                val tweaked = Taproot.tweakPrivateKey(priv, d.tapscriptRoot)
                assertEquals("$tag tweaked priv", inp.str("tweakedPrivKey"), tweaked.toHex())

                // oyster's signature must verify under our sighash + output key ...
                assertTrue("$tag oyster sig verifies", Secp.verifySchnorr(inp.str("signature").hexToBytes(), sighash, d.outputKey))
                // ... and ours must verify too.
                val sig = TaprootSigner.signKeyPath(tx, i, prevOuts, priv, d.tapscriptRoot)
                assertTrue("$tag our sig verifies", Secp.verifySchnorr(sig, sighash, inp.str("outputKey").hexToBytes()))
                tx.inputs[i].witness = listOf(sig)
            }
        }
        assertEquals("$name txid", sp.str("txid"), tx.txid())
        assertEquals("$name vsize", sp.long("vsize"), tx.vsize().toLong())
        // Witness data differs (random aux), but structure/size must be identical.
        assertEquals("$name signed size", sp.str("signedTxHex").length, tx.serialize().toHex().length)
    }
}
