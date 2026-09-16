package dev.pocketprl.core.chain

import dev.pocketprl.core.crypto.ByteWriter
import dev.pocketprl.core.crypto.Hashes
import dev.pocketprl.core.crypto.compactSizeLen
import dev.pocketprl.core.crypto.hexToBytes
import dev.pocketprl.core.crypto.reversedBytes
import dev.pocketprl.core.crypto.toHex

class OutPoint(val txidInternal: ByteArray, val index: Long) {
    init { require(txidInternal.size == 32) }

    val txidHex: String get() = txidInternal.reversedBytes().toHex()

    companion object {
        /** From display-order txid hex (as shown by explorers / Blockbook). */
        fun fromHex(txidHex: String, index: Long) = OutPoint(txidHex.hexToBytes().reversedBytes(), index)
    }
}

class TxIn(
    val outPoint: OutPoint,
    val sequence: Long = 0xffffffffL,
    var witness: List<ByteArray> = emptyList(),
)

class TxOut(val value: Long, val script: ByteArray)

/** Minimal segwit transaction model: enough to build, sign and serialize key-path P2TR spends. */
class Transaction(
    val version: Int = 2,
    val inputs: List<TxIn>,
    val outputs: List<TxOut>,
    val lockTime: Long = 0,
) {
    val hasWitness: Boolean get() = inputs.any { it.witness.isNotEmpty() }

    fun serialize(withWitness: Boolean = true): ByteArray {
        val w = ByteWriter(baseSize() + 256)
        w.u32le(version.toLong())
        val witness = withWitness && hasWitness
        if (witness) { w.u8(0x00); w.u8(0x01) }
        w.compactSize(inputs.size.toLong())
        for (i in inputs) {
            w.bytes(i.outPoint.txidInternal)
            w.u32le(i.outPoint.index)
            w.compactSize(0) // Taproot spends carry no scriptSig.
            w.u32le(i.sequence)
        }
        w.compactSize(outputs.size.toLong())
        for (o in outputs) {
            w.u64le(o.value)
            w.varBytes(o.script)
        }
        if (witness) {
            for (i in inputs) {
                w.compactSize(i.witness.size.toLong())
                for (item in i.witness) w.varBytes(item)
            }
        }
        w.u32le(lockTime)
        return w.toByteArray()
    }

    fun baseSize(): Int {
        var n = 4 + compactSizeLen(inputs.size.toLong()) + compactSizeLen(outputs.size.toLong()) + 4
        n += inputs.size * (32 + 4 + 1 + 4)
        for (o in outputs) n += 8 + compactSizeLen(o.script.size.toLong()) + o.script.size
        return n
    }

    fun weight(): Int {
        if (!hasWitness) return baseSize() * 4
        var wit = 2
        for (i in inputs) {
            wit += compactSizeLen(i.witness.size.toLong())
            for (item in i.witness) wit += compactSizeLen(item.size.toLong()) + item.size
        }
        return baseSize() * 4 + wit
    }

    fun vsize(): Int = (weight() + 3) / 4

    /** Display-order txid (double SHA-256 of the non-witness serialization, reversed). */
    fun txid(): String = Hashes.dsha256(serialize(withWitness = false)).reversedBytes().toHex()

    fun totalOutput(): Long = outputs.sumOf { it.value }

    companion object {
        /** Worst-case vsize for a key-path P2TR spend with [nIn] inputs and [nOut] P2TR outputs. */
        fun estimateVsize(nIn: Int, nOut: Int): Int {
            val base = 4 + compactSizeLen(nIn.toLong()) + nIn * 41 + compactSizeLen(nOut.toLong()) + nOut * 43 + 4
            val witness = 2 + nIn * (1 + 1 + 64)
            return (base * 4 + witness + 3) / 4
        }
    }
}
