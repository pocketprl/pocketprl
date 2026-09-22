package dev.pocketprl.core.chain

import dev.pocketprl.core.format.FiatCurrency
import dev.pocketprl.core.format.Format
import dev.pocketprl.core.format.GroupingSeparator
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

    /**
     * Machine-friendly decimal string ("1234.5"): no grouping, '.' as the
     * separator. Used for URIs, CSV and inputs, so it never follows the display
     * preferences.
     */
    fun format(grain: Long, maxDecimals: Int = 8, trimZeros: Boolean = true): String {
        val bd = BigDecimal(grain).divide(PRL_SCALE).setScale(8, RoundingMode.DOWN)
        var s = bd.setScale(maxDecimals, RoundingMode.DOWN).toPlainString()
        if (trimZeros && s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /**
     * Placeholder kept for callers that still want the old default; the symbol
     * is a no-op now that grouping/decimal separators are configurable.
     */
    @Deprecated("Use Amount.pretty", ReplaceWith("Amount.pretty(grain)"))
    const val GROUP_SEPARATOR = '\u202F'

    /** Decimals a label shows by default when the user has not chosen. */
    const val LABEL_DECIMALS = 4

    private fun symbols(): DecimalFormatSymbols {
        val c = Format.config
        return DecimalFormatSymbols(Locale.US).apply {
            decimalSeparator = c.decimalSeparator.char
            groupingSeparator = c.groupingSeparator.char ?: '\u202F'
        }
    }

    private fun applyGrouping(f: DecimalFormat): DecimalFormat {
        if (Format.config.groupingSeparator == GroupingSeparator.NONE) f.isGroupingUsed = false
        return f
    }

    /** Thousands-grouped integer ("1 234 567") for counts such as block heights and confirmations. */
    fun group(n: Long): String = applyGrouping(DecimalFormat("#,##0", symbols())).format(n)

    /**
     * Human-friendly rendering with the configured grouping and separators, for
     * labels only. Truncates (never rounds up) to [maxDecimals]; an amount that
     * would read as zero at that precision falls back to full precision.
     */
    fun pretty(grain: Long, maxDecimals: Int = Format.config.decimals): String {
        val dec = maxDecimals.coerceIn(0, 8)
        val abs = if (grain < 0) -grain else grain
        val unit = BigDecimal.TEN.pow(8 - dec).longValueExact()
        val scale = if (grain != 0L && abs < unit) 8 else dec
        val f = applyGrouping(DecimalFormat("#,##0.${"#".repeat(scale)}", symbols()))
        return f.format(BigDecimal(grain).divide(PRL_SCALE).setScale(scale, RoundingMode.DOWN))
    }

    fun formatWithTicker(grain: Long, network: Network, maxDecimals: Int = 8): String =
        "${format(grain, maxDecimals)} ${network.ticker}"

    /** Wraps a bare grouped number in the configured fiat symbol. */
    fun applyFiatSymbol(number: String, currency: FiatCurrency = Format.config.fiat): String = when {
        currency.suffix -> "$number${if (currency.space) " " else ""}${currency.symbol}"
        currency.space -> "${currency.symbol} $number"
        else -> "${currency.symbol}$number"
    }

    /** Removes the configured fiat symbol from typed input, prefix or suffix, with its optional space. */
    fun stripFiatSymbol(value: String): String {
        val c = Format.config.fiat
        val t = value.trim()
        return if (c.suffix) t.removeSuffix(c.symbol).trimEnd() else t.removePrefix(c.symbol).trimStart()
    }

    /** "$1 234.56" for a grain amount at [pricePerPrl]; null when no price is known. */
    fun fiat(grain: Long, pricePerPrl: Double?): String? {
        if (pricePerPrl == null || !pricePerPrl.isFinite() || pricePerPrl <= 0) return null
        val value = BigDecimal(grain).divide(PRL_SCALE).multiply(BigDecimal(pricePerPrl))
        val sign = if (value.signum() < 0) "-" else ""
        // Cents, except for dust that would read as "$0.00".
        return sign + applyFiatSymbol(money(value.abs(), finePrecisionBelow = BigDecimal("0.01")))
    }

    /** "$1 234.56" for a value that is already in the configured fiat. */
    fun fiatValue(value: Double): String {
        if (!value.isFinite()) return applyFiatSymbol("0.00")
        val sign = if (value < 0) "-" else ""
        return sign + applyFiatSymbol(money(BigDecimal(value).abs(), finePrecisionBelow = BigDecimal("0.01")))
    }

    /**
     * A unit price in the configured fiat, with four places under one unit.
     * Kept named `usdPrice` for source compatibility; it now renders the
     * user-selected currency.
     */
    fun usdPrice(pricePerPrl: Double): String =
        applyFiatSymbol(money(BigDecimal(pricePerPrl).abs(), finePrecisionBelow = BigDecimal.ONE))

    private fun money(abs: BigDecimal, finePrecisionBelow: BigDecimal): String {
        val f = if (abs < finePrecisionBelow && abs.signum() != 0) DecimalFormat("0.0000", symbols()) else DecimalFormat("#,##0.00", symbols())
        return applyGrouping(f).format(abs)
    }

    /**
     * Canonical, machine-readable decimal string ("1234.56") from free-form user
     * input, or null when it is not a number. The decimal separator is decided
     * *before* any grouping separator is stripped. Otherwise a comma typed as a
     * decimal on a comma-grouping locale (en-US "1,5") is deleted as grouping and
     * the amount inflates 10x, 100x or 1000x with no warning.
     *
     * Every entry point that accepts a typed number goes through here, so the
     * custom fee rate and the fiat amount obeys exactly the same rules as the PRL
     * amount field.
     */
    fun normalizeDecimal(text: String): String? {
        val c = Format.config
        var t = text.trim()
        t = t.replace('\u202F'.toString(), "").replace("\u00A0", "").replace(" ", "")
        if (t.isEmpty() || t == "." || t == ",") return null

        val grouping = c.groupingSeparator.char
        val configuredDec = c.decimalSeparator.char
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val decChar: Char? = when {
            // Both present: the last one is the decimal separator, the other groups.
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) '.' else ','
            lastDot >= 0 || lastComma >= 0 -> {
                val ch = if (lastDot >= 0) '.' else ','
                when {
                    ch == configuredDec -> ch
                    ch == grouping -> {
                        val count = t.count { it == ch }
                        val digitsAfter = t.length - t.lastIndexOf(ch) - 1
                        // One separator followed by anything but a full three-digit
                        // group is a decimal typed with the "wrong" character.
                        if (count == 1 && digitsAfter != 3) ch else null
                    }
                    else -> ch
                }
            }
            else -> null
        }

        if (decChar == null) {
            if (grouping != null) t = t.replace(grouping.toString(), "")
        } else {
            val other = if (decChar == '.') ',' else '.'
            t = t.replace(other.toString(), "")
            if (grouping != null && grouping != decChar) t = t.replace(grouping.toString(), "")
            if (decChar == ',') t = t.replace(',', '.')
        }
        if (t.isEmpty() || t == ".") return null
        if (!AMOUNT_RE.matches(t)) return null
        return t
    }

    /** Parses a decimal amount, tolerating the configured separators and both '.'/','. */
    fun parse(text: String): Long? {
        val t = normalizeDecimal(text) ?: return null
        val bd = try { BigDecimal(t).stripTrailingZeros() } catch (_: NumberFormatException) { return null }
        if (bd.scale() > 8) return null
        val grain = bd.multiply(PRL_SCALE)
        if (grain.signum() < 0 || grain > BigDecimal(MAX_SUPPLY_GRAIN)) return null
        return grain.longValueExact()
    }

    /** A separator-tolerant decimal with no total-supply ceiling, for rates and prices. */
    fun parseDecimal(text: String): BigDecimal? =
        normalizeDecimal(text)?.let { runCatching { BigDecimal(it).stripTrailingZeros() }.getOrNull() }

    /** User-typed PRL-per-kB fee rate to grain per kB, deciding separators like every other number field. */
    fun ratePerKbToGrainPerKb(text: String): Long? = runCatching {
        parseDecimal(text)?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.DOWN)?.longValueExact()
    }.getOrNull()?.takeIf { it >= 0 }

    /** Inverse of [fiat] for amount entry: how many grain does [fiat] buy at [pricePerPrl]? */
    fun grainForFiat(fiat: String, pricePerPrl: Double?): Long? {
        if (pricePerPrl == null || !pricePerPrl.isFinite() || pricePerPrl <= 0) return null
        val cleaned = stripFiatSymbol(fiat).removePrefix("$").trim()
        val t = normalizeDecimal(cleaned) ?: return null
        val bd = try { BigDecimal(t) } catch (_: NumberFormatException) { return null }
        val grain = bd.divide(BigDecimal(pricePerPrl), 8, RoundingMode.DOWN).multiply(PRL_SCALE).setScale(0, RoundingMode.DOWN)
        if (grain.signum() < 0 || grain > BigDecimal(MAX_SUPPLY_GRAIN)) return null
        return grain.longValueExact()
    }

    /** Kept for source compatibility; forwards to [grainForFiat]. */
    fun grainForUsd(usd: String, usdPerPrl: Double?): Long? = grainForFiat(usd, usdPerPrl)

    /** Fee rate helpers: Blockbook reports PRL per kB; the wallet works in grain per kB. */
    fun prlPerKbToGrainPerKb(prlPerKb: String): Long? =
        try { BigDecimal(prlPerKb).multiply(PRL_SCALE).setScale(0, RoundingMode.CEILING).longValueExact() } catch (_: Exception) { null }

    fun formatGrainPerVbyte(grainPerKb: Long): String =
        BigDecimal(grainPerKb).divide(BigDecimal(1000), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
