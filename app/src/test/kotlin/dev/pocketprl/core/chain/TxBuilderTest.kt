package dev.pocketprl.core.chain

import dev.pocketprl.core.crypto.Taproot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TxBuilderTest {
    private val to = Taproot.p2trScript(ByteArray(32) { 1 })
    private val change = Taproot.p2trScript(ByteArray(32) { 2 })

    private fun utxo(v: Long, conf: Int = 6, i: Int = 0) =
        SpendableUtxo("ab".repeat(32), i, v, Taproot.p2trScript(ByteArray(32) { 3 }), 0, i, conf)

    @Test
    fun feeMatchesOysterEstimate() {
        assertEquals(212, Transaction.estimateVsize(2, 2))
        assertEquals(154, Transaction.estimateVsize(1, 2))
        assertEquals(111, Transaction.estimateVsize(1, 1))
        assertEquals(212, TxBuilder.feeFor(212, 1000))
        assertEquals(41_617, TxBuilder.feeFor(212, 196_302))
    }

    @Test
    fun selectsLargestConfirmedFirstAndCreatesChange() {
        val r = TxBuilder.build(listOf(utxo(1_0000_0000, i = 0), utxo(5_0000_0000, i = 1), utxo(9_0000_0000, conf = 0, i = 2)),
            to, 2_0000_0000, change, 10_000)
        assertEquals(1, r.selected.size)
        assertEquals(1, r.selected[0].vout)
        assertEquals(2, r.tx.outputs.size)
        assertEquals(2_0000_0000, r.tx.outputs[0].value)
        assertEquals(5_0000_0000 - 2_0000_0000 - r.fee, r.change)
        assertEquals(r.change, r.tx.outputs[1].value)
        assertEquals(TxBuilder.feeFor(Transaction.estimateVsize(1, 2), 10_000), r.fee)
    }

    @Test
    fun dustChangeIsFoldedIntoFee() {
        val amount = 1_0000_0000L - 200
        val r = TxBuilder.build(listOf(utxo(1_0000_0000)), to, amount, change, 1000)
        assertEquals(1, r.tx.outputs.size)
        assertEquals(0, r.change)
        assertEquals(200, r.fee)
    }

    @Test
    fun insufficientFunds() {
        assertThrows(InsufficientFundsException::class.java) {
            TxBuilder.build(listOf(utxo(1000)), to, 5000, change, 1000)
        }
    }

    @Test
    fun sendMaxUsesEverything() {
        val utxos = listOf(utxo(1_0000_0000, i = 0), utxo(3_0000_0000, i = 1))
        val r = TxBuilder.build(utxos, to, 0, change, 2000, sendMax = true)
        assertEquals(2, r.selected.size)
        assertEquals(1, r.tx.outputs.size)
        assertEquals(4_0000_0000 - r.fee, r.amount)
        assertEquals(TxBuilder.maxSendable(utxos, 2000), r.amount)
        assertTrue(r.fee >= 170)
    }

    @Test
    fun amountParsing() {
        assertEquals(1_2345_6789L, Amount.parse("1.23456789"))
        assertEquals(50_0000_0000L, Amount.parse("50"))
        assertEquals(1L, Amount.parse("0.00000001"))
        assertNull(Amount.parse("0.000000001"))
        assertNull(Amount.parse("abc"))
        assertNull(Amount.parse("-1"))
        assertEquals("1.23456789", Amount.format(1_2345_6789L))
        assertEquals("50", Amount.format(50_0000_0000L))
        assertEquals("0.00000001", Amount.format(1))
        assertEquals(196_437L, Amount.prlPerKbToGrainPerKb("0.00196437"))
        assertEquals(1000L, Amount.prlPerKbToGrainPerKb("0.00001"))
    }

    @Test
    fun addressValidation() {
        val main = "prl1puv0gqv4x0wd0ylwz086y3sqrg4a6umza9r08aecehjrmdqq7mctsmqaqsh"
        assertTrue(Address.parse(main, Network.MAINNET) is Address.Result.Valid)
        assertTrue(Address.parse(main, Network.TESTNET2) is Address.Result.Invalid)
        assertTrue(Address.parse(main.dropLast(1), Network.MAINNET) is Address.Result.Invalid)
        assertTrue(Address.parse("bc1p" + main.drop(5), Network.MAINNET) is Address.Result.Invalid)
        assertEquals(main, Address.fromScript(Network.MAINNET, (Address.parse(main) as Address.Result.Valid).parsed.scriptPubKey))
    }
}
