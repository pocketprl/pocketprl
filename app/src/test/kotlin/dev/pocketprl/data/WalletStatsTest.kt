package dev.pocketprl.data

import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStatsTest {
    private val utc = ZoneId.of("UTC")
    private val now: Long = Instant.parse("2026-09-19T12:00:00Z").epochSecond

    private fun at(iso: String): Long = Instant.parse(iso).epochSecond

    private fun tx(
        txid: String,
        kind: TxKind,
        amount: Long,
        fee: Long = 0,
        time: Long = now,
        coinbase: Boolean = false,
        height: Long = 1,
        firstSeen: Long = time,
    ) = TxRow(
        txid = txid, height = height, blockTime = time, firstSeen = firstSeen, fee = fee, kind = kind, amount = amount,
        ownIn = 0, ownOut = 0, ownAddress = null, counterparty = null, nIn = 1, nOut = 1, vsize = 100, coinbase = coinbase,
    )

    @Test
    fun `empty history is empty`() {
        assertTrue(WalletStatsCalculator.compute(emptyList(), now, utc).isEmpty)
    }

    @Test
    fun `totals, counts and records`() {
        val txs = listOf(
            tx("a", TxKind.RECEIVED, 100, time = at("2026-08-01T00:00:00Z")),
            tx("b", TxKind.SENT, 30, fee = 2, time = at("2026-09-01T00:00:00Z")),
            tx("c", TxKind.MINED, 50, time = at("2026-09-02T00:00:00Z"), coinbase = true),
        )
        val s = WalletStatsCalculator.compute(txs, now, utc)
        assertEquals(3, s.txCount)
        assertEquals(1, s.receivedCount)
        assertEquals(1, s.sentCount)
        assertEquals(1, s.minedCount)
        assertEquals(150L, s.totalReceived)
        assertEquals(30L, s.totalSent)
        assertEquals(2L, s.totalFees)
        assertEquals(50L, s.totalMined)
        assertEquals(118L, s.netFlow)
        assertEquals(100L, s.largestReceived?.amount)
        assertEquals(30L, s.largestSent?.amount)
        assertEquals(2L, s.biggestFee?.amount)
        assertEquals(2L, s.averageFee)
        assertEquals(at("2026-08-01T00:00:00Z"), s.firstTxTime)
        assertEquals(at("2026-09-02T00:00:00Z"), s.lastTxTime)
    }

    @Test
    fun `self transfers only cost the fee`() {
        val s = WalletStatsCalculator.compute(listOf(tx("s", TxKind.SELF, 0, fee = 5)), now, utc)
        assertEquals(1, s.selfCount)
        assertEquals(0, s.sentCount)
        assertEquals(5L, s.totalFees)
        assertEquals(-5L, s.netFlow)
    }

    @Test
    fun `weeks bucket inflow and outflow, oldest first`() {
        val txs = listOf(
            // Wednesday 2 Sep 2026 -> week beginning Mon 31 Aug (11th-oldest of the 12 weeks ending Mon 14 Sep).
            tx("a", TxKind.RECEIVED, 100, time = at("2026-09-02T12:00:00Z")),
            // Tuesday 15 Sep 2026 -> the current week.
            tx("b", TxKind.SENT, 30, fee = 2, time = at("2026-09-15T12:00:00Z")),
        )
        val s = WalletStatsCalculator.compute(txs, now, utc)
        assertEquals(12, s.weeks.size)
        assertEquals(at("2026-08-31T00:00:00Z"), s.weeks[9].start)
        assertEquals(100L, s.weeks[9].inflow)
        assertEquals(0L, s.weeks[9].outflow)
        assertEquals(0L, s.weeks[11].inflow)
        assertEquals(32L, s.weeks[11].outflow)
    }

    @Test
    fun `balance series starts at zero and ends at net`() {
        val txs = listOf(
            tx("a", TxKind.RECEIVED, 100, time = at("2026-08-01T00:00:00Z")),
            tx("b", TxKind.SENT, 30, fee = 2, time = at("2026-09-01T00:00:00Z")),
        )
        val s = WalletStatsCalculator.compute(txs, now, utc)
        assertEquals(0L, s.balanceSeries.first().amount)
        assertEquals(s.netFlow, s.balanceSeries.last().amount)
    }

    @Test
    fun `busiest day is the one with the most transactions`() {
        val txs = listOf(
            tx("a", TxKind.RECEIVED, 1, time = at("2026-09-01T01:00:00Z")),
            tx("b", TxKind.RECEIVED, 1, time = at("2026-09-01T20:00:00Z")),
            tx("c", TxKind.RECEIVED, 1, time = at("2026-09-02T10:00:00Z")),
        )
        val s = WalletStatsCalculator.compute(txs, now, utc)
        assertEquals(2, s.busiestDay?.count)
        assertEquals(2, s.daysActive)
        assertEquals(at("2026-09-01T00:00:00Z"), s.busiestDay?.dayEpochSeconds)
    }

    @Test
    fun `pending transaction falls back to firstSeen`() {
        // time = firstSeen when there is no block yet.
        val pending = tx("p", TxKind.RECEIVED, 7, time = 0, height = 0, firstSeen = at("2026-09-18T08:00:00Z"))
        val s = WalletStatsCalculator.compute(listOf(pending), now, utc)
        assertEquals(at("2026-09-18T08:00:00Z"), s.lastTxTime)
    }

    @Test
    fun `empty value is null, not zero`() {
        val s = WalletStatsCalculator.compute(listOf(tx("a", TxKind.RECEIVED, 5)), now, utc)
        assertNull(s.largestSent)
        assertNull(s.biggestFee)
    }
}
