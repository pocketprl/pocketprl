package dev.pocketprl.core.format

import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.AddressError
import dev.pocketprl.core.chain.Amount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The configurable display formatting must not leak into the canonical machine format. */
class FormattingTest {
    private val original = Format.config

    @Before fun setUp() { Format.config = FormatConfig() }
    @After fun tearDown() { Format.config = original }

    @Test fun defaultUsFormatting() {
        assertEquals("1.5", Amount.pretty(150_000_000L))
        assertEquals("1.2345", Amount.pretty(123_456_789L))
        assertEquals("1\u202F234\u202F567", Amount.group(1_234_567L))
    }

    @Test fun decimalPlacesSetting() {
        Format.config = Format.config.copy(decimals = 2)
        assertEquals("1.23", Amount.pretty(123_456_789L))
        Format.config = Format.config.copy(decimals = 8)
        assertEquals("1.23456789", Amount.pretty(123_456_789L))
    }

    @Test fun commaDecimalsAndPeriodGrouping() {
        Format.config = Format.config.copy(decimalSeparator = DecimalSeparator.COMMA, groupingSeparator = GroupingSeparator.PERIOD)
        assertEquals("1.234,5", Amount.pretty(123_450_000_000L))
        assertEquals("1234.56", Amount.parse("1234,56")?.let { Amount.format(it) })
    }

    @Test fun groupingCanBeDisabled() {
        Format.config = Format.config.copy(groupingSeparator = GroupingSeparator.NONE)
        assertEquals("1234567", Amount.group(1_234_567L))
    }

    @Test fun parseAcceptsBothSeparatorConventions() {
        Format.config = Format.config.copy(decimalSeparator = DecimalSeparator.COMMA, groupingSeparator = GroupingSeparator.PERIOD)
        assertEquals(123_456_000_000L, Amount.parse("1.234,56"))
        assertEquals(123_456_000_000L, Amount.parse("1234,56"))
    }

    @Test fun machineFormatIgnoresDisplayConfig() {
        Format.config = Format.config.copy(decimalSeparator = DecimalSeparator.COMMA, groupingSeparator = GroupingSeparator.NONE, decimals = 2)
        assertEquals("1.5", Amount.format(150_000_000L))
    }

    @Test fun fiatUsesConfiguredCurrency() {
        assertEquals("$3.00", Amount.fiat(150_000_000L, 2.0))
        Format.config = Format.config.copy(fiat = FiatCurrency.EUR)
        assertEquals("€3.00", Amount.fiat(150_000_000L, 2.0))
    }

    @Test fun fiatSuffixCurrency() {
        Format.config = Format.config.copy(fiat = FiatCurrency.PLN, decimalSeparator = DecimalSeparator.COMMA)
        assertEquals("3,00 zł", Amount.fiat(150_000_000L, 2.0))
    }

    @Test fun fiatForLocalePicksKnownCurrency() {
        assertEquals(FiatCurrency.EUR, FiatCurrency.forLocale(Locale.GERMANY))
        assertEquals(FiatCurrency.USD, FiatCurrency.forLocale(Locale.US))
    }

    @Test fun addressErrorsAreStructured() {
        val empty = Address.parse("", null)
        assertTrue(empty is Address.Result.Invalid)
        assertEquals(AddressError.ENTER, (empty as Address.Result.Invalid).error)

        val garbage = Address.parse("not-an-address", null)
        assertTrue(garbage is Address.Result.Invalid)
        assertEquals(AddressError.INVALID, (garbage as Address.Result.Invalid).error)
    }
}
