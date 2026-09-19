package dev.pocketprl.core.format

/**
 * User-selectable presentation formatting. This affects *display only*: the
 * canonical machine format (`Amount.format`, URIs, CSV, signing) always uses
 * '.' as the decimal separator and no grouping. `Amount.parse` is tolerant of
 * whatever is configured so typing stays natural.
 *
 * Lives in core so `Amount` can consult it without depending on Android.
 */

/** A fiat currency the price feed can be shown in. [suffix] currencies render after the number. */
enum class FiatCurrency(
    val code: String,
    val symbol: String,
    val suffix: Boolean = false,
    /** A space between the number and the symbol (used for alphabetic symbols). */
    val space: Boolean = false,
) {
    USD("usd", "$"),
    EUR("eur", "\u20AC"),
    GBP("gbp", "\u00A3"),
    CHF("chf", "CHF", space = true),
    JPY("jpy", "\u00A5"),
    CNY("cny", "\u00A5"),
    CAD("cad", "CA$"),
    AUD("aud", "A$"),
    INR("inr", "\u20B9"),
    BRL("brl", "R$"),
    PLN("pln", "z\u0142", suffix = true, space = true),
    SEK("sek", "kr", suffix = true, space = true),
    NOK("nok", "kr", suffix = true, space = true),
    DKK("dkk", "kr", suffix = true, space = true),
    CZK("czk", "K\u010D", suffix = true, space = true),
    HUF("huf", "Ft", suffix = true, space = true),
    RON("ron", "lei", suffix = true, space = true),
    TRY("try", "\u20BA"),
    RUB("rub", "\u20BD"),
    UAH("uah", "\u20B4"),
    ;

    companion object {
        fun fromCode(code: String?): FiatCurrency = entries.firstOrNull { it.code.equals(code, true) } ?: USD

        /** Best guess from the system locale's currency, falling back to USD. */
        fun forLocale(locale: java.util.Locale): FiatCurrency =
            runCatching { java.util.Currency.getInstance(locale).currencyCode }
                .getOrNull()
                ?.let { entries.firstOrNull { c -> c.code.equals(it, true) } }
                ?: USD
    }
}

/** Thousands grouping. [NONE] disables grouping entirely. */
enum class GroupingSeparator(val char: Char?) {
    NARROW_SPACE('\u202F'),
    SPACE(' '),
    COMMA(','),
    PERIOD('.'),
    APOSTROPHE('\''),
    NONE(null),
    ;

    companion object {
        fun fromName(name: String?): GroupingSeparator = entries.firstOrNull { it.name == name } ?: NARROW_SPACE
    }
}

enum class DecimalSeparator(val char: Char) {
    PERIOD('.'),
    COMMA(','),
    ;

    companion object {
        fun fromName(name: String?): DecimalSeparator = entries.firstOrNull { it.name == name } ?: PERIOD
    }
}

/** Snapshot of the formatting preferences in effect. */
data class FormatConfig(
    /** Decimals shown for balances and labels. Exact amounts (fees, signing) always use 8. */
    val decimals: Int = 4,
    val decimalSeparator: DecimalSeparator = DecimalSeparator.PERIOD,
    val groupingSeparator: GroupingSeparator = GroupingSeparator.NARROW_SPACE,
    val fiat: FiatCurrency = FiatCurrency.USD,
)

/**
 * Global, consulted by [dev.pocketprl.core.chain.Amount]. [dev.pocketprl.data.Settings]
 * keeps it in sync; tests set it directly.
 */
object Format {
    @Volatile
    var config: FormatConfig = FormatConfig()

    fun update(block: (FormatConfig) -> FormatConfig) {
        config = block(config)
    }
}
