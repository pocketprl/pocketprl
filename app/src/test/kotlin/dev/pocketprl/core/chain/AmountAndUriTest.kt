package dev.pocketprl.core.chain

import dev.pocketprl.core.crypto.Bech32
import dev.pocketprl.ui.Qr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountAndUriTest {
    private val main = "prl1puv0gqv4x0wd0ylwz086y3sqrg4a6umza9r08aecehjrmdqq7mctsmqaqsh"

    @Test
    fun prettyGroupsThousandsAndTrimsZeros() {
        val s = Amount.GROUP_SEPARATOR
        assertEquals("1${s}234${s}567.5", Amount.pretty(1_234_567_50000000L))
        assertEquals(1_234_567_50000000L, Amount.parse(Amount.pretty(1_234_567_50000000L)))
        assertEquals("0.00000001", Amount.pretty(1))
        assertEquals("-2.25", Amount.pretty(-2_25000000L))
        assertEquals("0", Amount.pretty(0))
    }

    @Test
    fun prettyShowsFourDecimalsUnlessTheAmountWouldVanish() {
        // Labels truncate to four places and never round up.
        assertEquals("1.2345", Amount.pretty(1_23456789L))
        assertEquals("1.2345", Amount.pretty(1_23459999L))
        assertEquals("0.0001", Amount.pretty(15_000L))
        // Dust below the fourth place keeps full precision.
        assertEquals("0.00009999", Amount.pretty(9_999L))
        assertEquals("-0.00000226", Amount.pretty(-226L))
        assertEquals("0.0000226", Amount.pretty(2_260L))
        // Exact contexts ask for eight.
        assertEquals("1.23456789", Amount.pretty(1_23456789L, 8))
        assertEquals("1.5", Amount.pretty(1_50000000L, 8))
    }

    @Test
    fun parseAcceptsTrailingZerosBeyondEightDecimals() {
        assertEquals(1_50000000L, Amount.parse("1.5000000000"))
        assertEquals(1_50000000L, Amount.parse("1,5"))
        assertEquals(1_000_00000000L, Amount.parse("1 000"))
        assertEquals(1_000_00000000L, Amount.parse("1 000"))
        assertNull(Amount.parse("1.000000001"))
        assertNull(Amount.parse("1e5"))
        assertNull(Amount.parse("2100000001"))
    }

    @Test
    fun fiatFormatting() {
        assertEquals("$0.59", Amount.fiat(1_00000000L, 0.59))
        // Same grouping as PRL amounts: narrow space for thousands, '.' for cents.
        assertEquals("$1${Amount.GROUP_SEPARATOR}234.57", Amount.fiat(2_000_00000000L, 0.617283))
        assertEquals("$0.0006", Amount.fiat(1000_00, 0.59))
        assertEquals("-$0.59", Amount.fiat(-1_00000000L, 0.59))
        assertEquals("$0.5601", Amount.usdPrice(0.5601))
        assertEquals("$1${Amount.GROUP_SEPARATOR}234.50", Amount.usdPrice(1234.5))
        assertEquals("1${Amount.GROUP_SEPARATOR}234${Amount.GROUP_SEPARATOR}567", Amount.group(1_234_567))
        assertEquals("0", Amount.group(0))
        assertNull(Amount.fiat(1, null))
        assertNull(Amount.fiat(1, 0.0))
        assertEquals(1_00000000L, Amount.grainForUsd("0.59", 0.59))
        assertEquals(10_00000000L, Amount.grainForUsd("$5.90", 0.59))
    }

    @Test
    fun blockEta() {
        assertEquals("~3 min", Network.etaForBlocks(1))
        assertEquals("~32 min", Network.etaForBlocks(10))
        assertEquals("~1.5 h", Network.etaForBlocks(25))
        assertEquals("~5.5 h", Network.etaForBlocks(100))
    }

    @Test
    fun paymentUriRoundTrip() {
        val uri = Qr.paymentUri(main, 1_50000000L, "Invoice 42 & co")
        assertEquals("pearl:$main?amount=1.5&label=Invoice%2042%20%26%20co", uri)
        val pr = Qr.parsePayment(uri, Network.MAINNET)!!
        assertEquals(main, pr.address)
        assertEquals(1_50000000L, pr.amountGrain)
        assertEquals("Invoice 42 & co", pr.label)
        assertTrue(Qr.isPaymentUri(uri))
        assertFalse(Qr.isPaymentUri(main))
        assertEquals(main, Qr.paymentUri(main, null))
    }

    @Test
    fun paymentUriVariantsAndJunk() {
        assertNotNull(Qr.parsePayment("PRL:$main", Network.MAINNET))
        assertNotNull(Qr.parsePayment("pearl://$main?amount=1", Network.MAINNET))
        assertNotNull(Qr.parsePayment("  $main  ", Network.MAINNET))
        assertNull(Qr.parsePayment("pearl:$main", Network.TESTNET2))
        assertNull(Qr.parsePayment("bitcoin:$main", Network.MAINNET))
        // Malformed percent-encoding must not throw; the bad parameter is just dropped.
        val pr = Qr.parsePayment("pearl:$main?label=%zz&amount=2", Network.MAINNET)!!
        assertNull(pr.label)
        assertEquals(2_00000000L, pr.amountGrain)
        assertNull(Qr.parsePayment("pearl:$main?amount=abc", Network.MAINNET)!!.amountGrain)
    }

    @Test
    fun bech32RejectsTruncatedInputWithoutThrowing() {
        // Shorter than version + program + checksum: must be null, never an exception.
        for (n in 0..12) assertNull(Bech32.decodeSegwit("prl1" + "q".repeat(n)))
        assertNull(Bech32.decodeSegwit(""))
        assertNull(Bech32.decodeSegwit("1"))
        assertNull(Bech32.decodeSegwit("prl1"))
        assertNull(Address.parse("prl1qqqqqq", Network.MAINNET).let { (it as? Address.Result.Valid)?.parsed })
    }
}
