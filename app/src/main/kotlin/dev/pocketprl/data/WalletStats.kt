package dev.pocketprl.data

import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Net inflow/outflow for one calendar week, in grain. [start] is that Monday 00:00 local time. */
data class FlowBucket(val start: Long, val inflow: Long, val outflow: Long)

/** A transaction amount tagged with the tx it came from and when it happened. */
data class DatedAmount(val txid: String, val time: Long, val amount: Long)

/** The day with the most transactions, as a local-day epoch and the count. */
data class BusyDay(val dayEpochSeconds: Long, val count: Int)

/**
 * Everything the Stats screen shows, derived purely from the local transaction
 * history. Amounts are grain; the UI turns them into PRL/fiat.
 */
data class WalletStats(
    val txCount: Int = 0,
    val receivedCount: Int = 0,
    val sentCount: Int = 0,
    val selfCount: Int = 0,
    val minedCount: Int = 0,
    val firstTxTime: Long? = null,
    val lastTxTime: Long? = null,
    /** Gross inflow: received payments plus block rewards. */
    val totalReceived: Long = 0,
    /** Coins sent to others, excluding the network fee. */
    val totalSent: Long = 0,
    val totalFees: Long = 0,
    /** Net effect on the balance: received − sent − fees. */
    val netFlow: Long = 0,
    val totalMined: Long = 0,
    val largestReceived: DatedAmount? = null,
    val largestSent: DatedAmount? = null,
    val biggestFee: DatedAmount? = null,
    val averageFee: Long = 0,
    val daysActive: Int = 0,
    val busiestDay: BusyDay? = null,
    /** The last [WalletStatsCalculator.WEEKS] weeks, oldest first. */
    val weeks: List<FlowBucket> = emptyList(),
    /** Cumulative net balance over time, oldest first, starting at zero. */
    val balanceSeries: List<DatedAmount> = emptyList(),
    val isEmpty: Boolean = true,
)

/**
 * Pure, testable roll-up of the local history. Nothing here touches Android, the
 * network or the clock's timezone unless one is passed in, so it can be unit
 * tested from a handful of synthetic [TxRow]s.
 */
object WalletStatsCalculator {
    /** Pearl's chain is young; weekly buckets read better than a handful of monthly ones. */
    const val WEEKS = 12

    /** Cap on the balance-over-time series so a wallet with thousands of txs still draws fast. */
    private const val MAX_SERIES_POINTS = 120

    fun compute(
        txs: List<TxRow>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WalletStats {
        if (txs.isEmpty()) return WalletStats()

        var receivedCount = 0
        var sentCount = 0
        var selfCount = 0
        var minedCount = 0
        var totalReceived = 0L
        var totalSent = 0L
        var totalFees = 0L
        var totalMined = 0L
        var net = 0L
        var feeSum = 0L
        var feeCount = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var largestReceived: DatedAmount? = null
        var largestSent: DatedAmount? = null
        var biggestFee: DatedAmount? = null

        val days = HashSet<Long>()
        val dayCounts = HashMap<Long, Int>()
        val weekBuckets = HashMap<Long, LongArray>()

        // Chronological so the running balance below is meaningful.
        val ordered = txs.sortedBy { it.time }

        for (t in ordered) {
            val time = t.time
            if (time > 0) {
                if (time < first) first = time
                if (time > last) last = time
                val day = Instant.ofEpochSecond(time).atZone(zone).toLocalDate()
                val dayStart = day.atStartOfDay(zone).toEpochSecond()
                days += dayStart
                dayCounts[dayStart] = (dayCounts[dayStart] ?: 0) + 1
                val netAmount = t.netAmount
                if (netAmount != 0L) {
                    val bucket = weekBuckets.getOrPut(weekStart(day, zone)) { LongArray(2) }
                    if (netAmount > 0) bucket[0] += netAmount else bucket[1] += -netAmount
                }
            }
            when (t.kind) {
                TxKind.RECEIVED -> {
                    receivedCount++
                    totalReceived += t.amount
                    if (largestReceived == null || t.amount > largestReceived.amount) largestReceived = DatedAmount(t.txid, time, t.amount)
                }
                TxKind.MINED -> {
                    minedCount++
                    totalReceived += t.amount
                    totalMined += t.amount
                }
                TxKind.SENT -> {
                    sentCount++
                    totalSent += t.amount
                    if (largestSent == null || t.amount > largestSent.amount) largestSent = DatedAmount(t.txid, time, t.amount)
                }
                TxKind.SELF -> selfCount++
            }
            if (t.fee > 0) {
                totalFees += t.fee
                feeSum += t.fee
                feeCount++
                if (biggestFee == null || t.fee > biggestFee.amount) biggestFee = DatedAmount(t.txid, time, t.fee)
            }
            net += t.netAmount
        }

        val currentWeek = weekStart(Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate(), zone)
        val weeks = (WEEKS - 1 downTo 0).map { back ->
            val start = Instant.ofEpochSecond(currentWeek).atZone(zone).toLocalDate()
                .minusWeeks(back.toLong()).atStartOfDay(zone).toEpochSecond()
            val b = weekBuckets[start]
            FlowBucket(start, b?.get(0) ?: 0L, b?.get(1) ?: 0L)
        }

        // Seed the curve at zero before the first transaction so the chart starts at the floor.
        var running = 0L
        val series = ArrayList<DatedAmount>(ordered.size + 1)
        val start = ordered.firstOrNull()?.let { if (it.time > 0) it.time else null }
        if (start != null) series += DatedAmount("", start, 0L)
        for (t in ordered) {
            running += t.netAmount
            series += DatedAmount(t.txid, t.time, running)
        }

        val busiest = dayCounts.entries.maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })

        return WalletStats(
            txCount = txs.size,
            receivedCount = receivedCount,
            sentCount = sentCount,
            selfCount = selfCount,
            minedCount = minedCount,
            firstTxTime = if (first == Long.MAX_VALUE) null else first,
            lastTxTime = if (last == Long.MIN_VALUE) null else last,
            totalReceived = totalReceived,
            totalSent = totalSent,
            totalFees = totalFees,
            netFlow = net,
            totalMined = totalMined,
            largestReceived = largestReceived,
            largestSent = largestSent,
            biggestFee = biggestFee,
            averageFee = if (feeCount > 0) feeSum / feeCount else 0L,
            daysActive = days.size,
            busiestDay = busiest?.let { BusyDay(it.key, it.value) },
            weeks = weeks,
            balanceSeries = downsample(series, MAX_SERIES_POINTS),
            isEmpty = false,
        )
    }

    /** Monday 00:00 of the week containing [day], in [zone]. */
    private fun weekStart(day: LocalDate, zone: ZoneId): Long =
        day.minusDays((day.dayOfWeek.value - 1).toLong()).atStartOfDay(zone).toEpochSecond()

    /** Evenly thins a series to at most [max] points, always keeping the first and last. */
    private fun downsample(series: List<DatedAmount>, max: Int): List<DatedAmount> {
        if (series.size <= max) return series
        val step = (series.size - 1).toDouble() / (max - 1)
        val out = ArrayList<DatedAmount>(max)
        for (i in 0 until max) {
            val idx = (i * step).roundToInt().coerceIn(0, series.lastIndex)
            out += series[idx]
        }
        return out
    }
}
