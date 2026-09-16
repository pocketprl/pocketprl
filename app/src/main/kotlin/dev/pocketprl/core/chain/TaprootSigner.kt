package dev.pocketprl.core.chain

import dev.pocketprl.core.crypto.ByteWriter
import dev.pocketprl.core.crypto.Hashes
import dev.pocketprl.core.crypto.Secp
import dev.pocketprl.core.crypto.Taproot
import dev.pocketprl.core.crypto.wipe
import java.security.SecureRandom

/** BIP-341 signature hashing (key path, SIGHASH_DEFAULT) and key-path signing. */
object TaprootSigner {
    const val SIGHASH_DEFAULT = 0x00

    fun sighashKeyPath(tx: Transaction, inputIndex: Int, prevOuts: List<TxOut>): ByteArray {
        require(prevOuts.size == tx.inputs.size) { "need one prevout per input" }
        require(inputIndex in tx.inputs.indices)

        val prevouts = ByteWriter()
        val amounts = ByteWriter()
        val scripts = ByteWriter()
        val sequences = ByteWriter()
        for ((i, input) in tx.inputs.withIndex()) {
            prevouts.bytes(input.outPoint.txidInternal).u32le(input.outPoint.index)
            amounts.u64le(prevOuts[i].value)
            scripts.varBytes(prevOuts[i].script)
            sequences.u32le(input.sequence)
        }
        val outputs = ByteWriter()
        for (o in tx.outputs) outputs.u64le(o.value).varBytes(o.script)

        val msg = ByteWriter(200)
        msg.u8(0x00) // sighash epoch
        msg.u8(SIGHASH_DEFAULT)
        msg.u32le(tx.version.toLong())
        msg.u32le(tx.lockTime)
        msg.bytes(Hashes.sha256(prevouts.toByteArray()))
        msg.bytes(Hashes.sha256(amounts.toByteArray()))
        msg.bytes(Hashes.sha256(scripts.toByteArray()))
        msg.bytes(Hashes.sha256(sequences.toByteArray()))
        msg.bytes(Hashes.sha256(outputs.toByteArray()))
        msg.u8(0x00) // spend_type: ext_flag 0, no annex
        msg.u32le(inputIndex.toLong())
        return Hashes.taggedHash("TapSighash", msg.toByteArray())
    }

    /**
     * Produces the 64-byte witness signature for [inputIndex] spending an
     * XMSS-committed P2TR output via the key path.
     */
    fun signKeyPath(
        tx: Transaction,
        inputIndex: Int,
        prevOuts: List<TxOut>,
        internalPrivKey: ByteArray,
        tapscriptRoot: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val sighash = sighashKeyPath(tx, inputIndex, prevOuts)
        val tweaked = Taproot.tweakPrivateKey(internalPrivKey, tapscriptRoot)
        val aux = ByteArray(32).also { random.nextBytes(it) }
        try {
            val sig = Secp.signSchnorr(sighash, tweaked, aux)
            // Self-verify against the on-chain output key before returning.
            val outKey = prevOuts[inputIndex].script.copyOfRange(2, 34)
            check(Secp.verifySchnorr(sig, sighash, outKey)) { "produced signature failed self-verification" }
            return sig
        } finally {
            tweaked.wipe()
            aux.wipe()
        }
    }
}
