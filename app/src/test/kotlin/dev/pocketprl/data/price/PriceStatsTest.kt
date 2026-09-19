package dev.pocketprl.data.price

import dev.pocketprl.core.chain.Amount
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceStatsTest {
    private fun at(iso: String): Long = Instant.parse(iso).epochSecond

    private fun sent(amount: Long, time: Long) = TxRow(
        txid = "s$time", height = 1, blockTime = time, firstSeen = time, fee = 0, kind = TxKind.SENT, amount = amount,
        ownIn = 0, ownOut = 0, ownAddress = null, counterparty = null, nIn = 1, nOut = 1, vsize = 100, coinbase = false,
    )

    private val history = PriceHistory(
        listOf(
            PricePoint(at("2026-01-01T00:00:00Z"), 0.10),
            PricePoint(at("2026-02-01T00:00:00Z"), 0.20),
            PricePoint(at("2026-03-01T00:00:00Z"), 0.50),
            PricePoint(at("2026-04-01T00:00:00Z"), 0.30),
        ),
    )

    @Test
    fun `priceAt uses the last observation at or before the moment`() {
        assertEquals(0.20, history.priceAt(at("2026-02-15T00:00:00Z"))!!, 1e-9)
        assertEquals(0.10, history.priceAt(at("2026-01-15T00:00:00Z"))!!, 1e-9)
        assertEquals(0.20, history.priceAt(at("2026-02-01T00:00:00Z"))!!, 1e-9)
        assertNull(history.priceAt(at("2025-12-31T00:00:00Z")))
    }

    @Test
    fun `highSince finds the all-time high after a point`() {
        val h = history.highSince(at("2026-02-01T00:00:00Z"))!!
        assertEquals(0.50, h.price, 1e-9)
        assertEquals(at("2026-03-01T00:00:00Z"), h.time)
    }

    @Test
    fun `biggest missed gain and weighted average sell price`() {
        val txs = listOf(
            sent(100L * Amount.GRAIN_PER_PRL, at("2026-02-01T00:00:00Z")),
            sent(50L * Amount.GRAIN_PER_PRL, at("2026-03-01T00:00:00Z")),
        )
        val ps = PriceStatsCalculator.compute(txs, history)!!
        assertEquals(0.30, ps.averageSellPrice, 1e-9)
        assertEquals(0.50, ps.allTimeHigh, 1e-9)
        assertEquals(at("2026-03-01T00:00:00Z"), ps.allTimeHighTime)
        assertEquals(2, ps.sellsCount)
        assertEquals(100L * Amount.GRAIN_PER_PRL, ps.biggestMissedGain?.amount)
        assertEquals(30.0, ps.biggestMissedGain!!.missedGainFiat, 1e-9)
        assertNull(ps.totalMissedGainFiat)
    }

    @Test
    fun `total missed gains sum every sale under the current price`() {
        val txs = listOf(
            sent(100L * Amount.GRAIN_PER_PRL, at("2026-02-01T00:00:00Z")), // sold at 0.20
            sent(50L * Amount.GRAIN_PER_PRL, at("2026-03-01T00:00:00Z")),  // sold at 0.50
        )
        // At 0.40 only the first sale is under water: (0.40 - 0.20) * 100 = 20.
        val ps = PriceStatsCalculator.compute(txs, history, currentPrice = 0.40)!!
        assertEquals(20.0, ps.totalMissedGainFiat!!, 1e-9)
        // At 0.60 both are: (0.40 * 100) + (0.10 * 50) = 45.
        assertEquals(45.0, PriceStatsCalculator.compute(txs, history, currentPrice = 0.60)!!.totalMissedGainFiat!!, 1e-9)
        // Below every sale, nothing was missed.
        assertEquals(0.0, PriceStatsCalculator.compute(txs, history, currentPrice = 0.05)!!.totalMissedGainFiat!!, 1e-9)
    }

    @Test
    fun `no sales means no price stats`() {
        assertNull(PriceStatsCalculator.compute(emptyList(), history))
    }

    @Test
    fun `sales before the series starts are skipped`() {
        val old = sent(10L * Amount.GRAIN_PER_PRL, at("2010-01-01T00:00:00Z"))
        assertNull(PriceStatsCalculator.compute(listOf(old), history))
    }

    @Test
    fun `history survives a round trip through its compact encoding`() {
        val decoded = PriceHistory.decode(history.encode())!!
        assertEquals(history.points.size, decoded.points.size)
        assertEquals(history.points[2].price, decoded.points[2].price, 1e-12)
        assertEquals(history.points[2].time, decoded.points[2].time)
        assertNull(PriceHistory.decode("garbage;1:x;2:notanumber"))
        assertTrue(PriceHistory.decode("").let { it == null })
    }
}
