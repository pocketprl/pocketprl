package dev.pocketprl.core.chain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** PRL amounts are handled as integer "grain" (1 PRL = 100,000,000 grain), never as doubles. */
object Amount {
    const val GRAIN_PER_PRL = 100_000_000L
    const val MAX_SUPPLY_GRAIN = 2_100_000_000L * GRAIN_PER_PRL
    private val PRL_SCALE = BigDecimal(GRAIN_PER_PRL)
    private val AMOUNT_RE = Regex("^\\d*(\\.\\d*)?$")

    /** Machine-friendly decimal string ("1234.5"): no grouping, '.' as the separator. Used for URIs, CSV and inputs. */
    fun format(grain: Long, maxDecimals: Int = 8, trimZeros: Boolean = true): String {
        val bd = BigDecimal(grain).divide(PRL_SCALE).setScale(8, RoundingMode.DOWN)
        var s = bd.setScale(maxDecimals, RoundingMode.DOWN).toPlainString()
        if (trimZeros && s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /** Narrow no-break space: groups digits without letting an amount wrap mid-number. */
    const val GROUP_SEPARATOR = '\u202F'

    /** One convention for every number on screen: thousands grouped with the narrow space, decimals after a '.'. */
    private val SYMBOLS = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = GROUP_SEPARATOR; decimalSeparator = '.' }

    /** Thousands-grouped integer ("1 234 567") for counts such as block heights and confirmations. */
    fun group(n: Long): String = DecimalFormat("#,##0", SYMBOLS).format(n)

    /** Decimals a label shows by default. Anything exact (a payment about to be signed, a fee, a transaction detail) passes 8 explicitly. */
    const val LABEL_DECIMALS = 4

    /**
     * Human-friendly rendering with thousands grouping ("1 234.5"), for labels only.
     * Truncates (never rounds up) to [maxDecimals]; an amount that would read as
     * zero at that precision falls back to full precision.
     */
    fun pretty(grain: Long, maxDecimals: Int = LABEL_DECIMALS): String {
        val abs = if (grain < 0) -grain else grain
        val unit = BigDecimal.TEN.pow(8 - maxDecimals.coerceIn(0, 8)).longValueExact()
        val scale = if (grain != 0L && abs < unit) 8 else maxDecimals
        val f = DecimalFormat("#,##0.${"#".repeat(scale)}", SYMBOLS)
        return f.format(BigDecimal(grain).divide(PRL_SCALE).setScale(scale, RoundingMode.DOWN))
    }

    fun formatWithTicker(grain: Long, network: Network, maxDecimals: Int = 8): String =
        "${format(grain, maxDecimals)} ${network.ticker}"

    /** "$1 234.56" for a grain amount at [usdPerPrl]; null when no price is known. */
    fun fiat(grain: Long, usdPerPrl: Double?): String? {
        if (usdPerPrl == null || !usdPerPrl.isFinite() || usdPerPrl <= 0) return null
        val usd = BigDecimal(grain).divide(PRL_SCALE).multiply(BigDecimal(usdPerPrl))
        // Cents, except for dust that would read as "$0.00".
        return (if (usd.signum() < 0) "-" else "") + usd(usd.abs(), finePrecisionBelow = BigDecimal("0.01"))
    }

    /** "$0.5601" / "$1 234.56": a unit price in USD, with four places under a dollar. */
    fun usdPrice(usdPerPrl: Double): String = usd(BigDecimal(usdPerPrl).abs(), finePrecisionBelow = BigDecimal.ONE)

    private fun usd(abs: BigDecimal, finePrecisionBelow: BigDecimal): String {
        val f = if (abs < finePrecisionBelow && abs.signum() != 0) DecimalFormat("$0.0000", SYMBOLS) else DecimalFormat("$#,##0.00", SYMBOLS)
        return f.format(abs)
    }

    /** Parses a decimal PRL string; returns null if not a valid non-negative amount with <= 8 significant decimals. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(",", ".").replace("\u202F", "").replace("\u00A0", "").replace(" ", "")
        if (t.isEmpty() || t == ".") return null
        if (!AMOUNT_RE.matches(t)) return null
        val bd = try { BigDecimal(t).stripTrailingZeros() } catch (_: NumberFormatException) { return null }
        if (bd.scale() > 8) return null
        val grain = bd.multiply(PRL_SCALE)
        if (grain.signum() < 0 || grain > BigDecimal(MAX_SUPPLY_GRAIN)) return null
        return grain.longValueExact()
    }

    /** Inverse of [fiat] for amount entry: how many grain does [usd] buy at [usdPerPrl]? */
    fun grainForUsd(usd: String, usdPerPrl: Double?): Long? {
        if (usdPerPrl == null || !usdPerPrl.isFinite() || usdPerPrl <= 0) return null
        val t = usd.trim().replace(",", ".").removePrefix("$")
        if (t.isEmpty() || !AMOUNT_RE.matches(t)) return null
        val bd = try { BigDecimal(t) } catch (_: NumberFormatException) { return null }
        val grain = bd.divide(BigDecimal(usdPerPrl), 8, RoundingMode.DOWN).multiply(PRL_SCALE).setScale(0, RoundingMode.DOWN)
        if (grain.signum() < 0 || grain > BigDecimal(MAX_SUPPLY_GRAIN)) return null
        return grain.longValueExact()
    }

    /** Fee rate helpers: Blockbook reports PRL per kB; the wallet works in grain per kB. */
    fun prlPerKbToGrainPerKb(prlPerKb: String): Long? =
        try { BigDecimal(prlPerKb).multiply(PRL_SCALE).setScale(0, RoundingMode.CEILING).longValueExact() } catch (_: Exception) { null }

    fun formatGrainPerVbyte(grainPerKb: Long): String =
        BigDecimal(grainPerKb).divide(BigDecimal(1000), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
