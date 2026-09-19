package dev.pocketprl.data.price

import dev.pocketprl.core.chain.Amount
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow

/** One daily price observation, epoch seconds and the fiat price at that instant. */
data class PricePoint(val time: Long, val price: Double)

/**
 * A price series, oldest first, with the two lookups the time-machine stats need:
 * the price at a moment and the high from a moment onward. Small enough to keep in
 * memory and cheap to persist as one meta row.
 */
class PriceHistory(val points: List<PricePoint>) {

    /** The latest price, used when the live feed has not answered yet. */
    val latest: Double? get() = points.lastOrNull()?.price

    /** Price at [time]: the last observation at or before it, or null before the series starts. */
    fun priceAt(time: Long): Double? {
        if (points.isEmpty() || time < points.first().time) return null
        var lo = 0
        var hi = points.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (points[mid].time <= time) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return points[found].price
    }

    /** The highest observation at or after [time], with when it happened. */
    fun highSince(time: Long): PricePoint? {
        val from = points.indexOfFirst { it.time >= time }
        if (from < 0) return null
        var best = points[from]
        for (i in from + 1 until points.size) if (points[i].price > best.price) best = points[i]
        return best
    }

    fun encode(): String = points.joinToString(";") { "${it.time}:${java.lang.Double.toString(it.price)}" }

    companion object {
        const val MAX_POINTS = 4000

        fun decode(raw: String?): PriceHistory? {
            if (raw.isNullOrBlank()) return null
            val points = ArrayList<PricePoint>()
            for (part in raw.split(';')) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                val t = part.substring(0, i).toLongOrNull() ?: continue
                val p = part.substring(i + 1).toDoubleOrNull() ?: continue
                if (p.isFinite() && p > 0) points += PricePoint(t, p)
                if (points.size >= MAX_POINTS) break
            }
            return if (points.isEmpty()) null else PriceHistory(points.sortedBy { it.time })
        }
    }
}

/** One past sale, priced against the all-time high that came after it. */
data class SellRecord(
    val txid: String,
    val time: Long,
    val amount: Long,
    val sellPrice: Double,
    val missedGainFiat: Double,
)

/**
 * "What if" numbers for a wallet that has sent coins: the biggest fiat gain
 * forgone by selling early, and the average sell price against the high since.
 * All in the user's fiat; the UI formats them.
 */
data class PriceStats(
    val sellsCount: Int,
    val averageSellPrice: Double,
    val biggestMissedGain: SellRecord?,
    val allTimeHigh: Double,
    val allTimeHighTime: Long,
    val firstSellTime: Long,
    /** Sum of (current price − sell price) × amount over every sale that is now under water, or null without a live price. */
    val totalMissedGainFiat: Double?,
)

object PriceStatsCalculator {
    /**
     * The missed gain is measured against the high since the first sale, which
     * is what "missed" means; sales before the price series starts are skipped.
     * [currentPrice] only feeds the cumulative "if held until now" total.
     */
    fun compute(txs: List<TxRow>, history: PriceHistory, currentPrice: Double? = null): PriceStats? {
        val sells = txs.filter { it.kind == TxKind.SENT && it.amount > 0 && it.time > 0 }
        if (sells.isEmpty() || history.points.isEmpty()) return null
        val firstSell = sells.minOf { it.time }
        val ath = history.highSince(firstSell) ?: return null

        var weighted = 0.0
        var totalPrl = 0.0
        var worst: SellRecord? = null
        var totalMissedNow = 0.0
        var priced = 0
        for (s in sells) {
            val price = history.priceAt(s.time) ?: continue
            val prl = s.amount / Amount.GRAIN_PER_PRL.toDouble()
            weighted += price * prl
            totalPrl += prl
            priced++
            val missed = (ath.price - price).coerceAtLeast(0.0) * prl
            if (worst == null || missed > worst.missedGainFiat) worst = SellRecord(s.txid, s.time, s.amount, price, missed)
            currentPrice?.let { totalMissedNow += (it - price).coerceAtLeast(0.0) * prl }
        }
        if (priced == 0 || totalPrl <= 0.0) return null
        return PriceStats(
            sellsCount = priced,
            averageSellPrice = weighted / totalPrl,
            biggestMissedGain = worst,
            allTimeHigh = ath.price,
            allTimeHighTime = ath.time,
            firstSellTime = firstSell,
            totalMissedGainFiat = currentPrice?.let { totalMissedNow },
        )
    }
}
