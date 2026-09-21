package dev.pocketprl.data

import android.content.Context
import android.content.SharedPreferences
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.format.DecimalSeparator
import dev.pocketprl.core.format.FiatCurrency
import dev.pocketprl.core.format.Format
import dev.pocketprl.core.format.FormatConfig
import dev.pocketprl.core.format.GroupingSeparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Non-secret preferences. */
enum class ThemeMode(val id: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark"),
    /** Dark with true-black surfaces, for OLED panels. */
    OLED("oled");
    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Accent colour sets layered over the Pearl neutrals. Dynamic colour overrides this on Android 12+. */
enum class AccentTheme(val id: String) {
    PEARL("pearl"), OCEAN("ocean"), SUNSET("sunset"), VIOLET("violet"), FOREST("forest"), ROSE("rose"), MONO("mono");
    companion object {
        fun fromId(id: String?): AccentTheme = entries.firstOrNull { it.id == id } ?: PEARL
    }
}

/** How aggressively the home screen re-polls the indexer. */
enum class PollMode(val id: String) {
    LIVE("live"), BALANCED("balanced"), BATTERY("battery");
    companion object {
        fun fromId(id: String?): PollMode = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val autoLockSeconds: Int,
        val requireAuthToSend: Boolean,
        /** Off by default: erase this wallet's keys after 10 wrong password attempts. */
        val wipeAfterFailedAttempts: Boolean,
        val customBlockbookUrl: Map<String, String>,
        val feeTier: String,
        val hideBalance: Boolean,
        val showFiat: Boolean,
        val odometer: Boolean,
        val odometerHaptics: Boolean,
        val notifyIncoming: Boolean,
        val priceAlert: Boolean,
        val priceAlertPercent: Double,
        val themeMode: ThemeMode,
        val appLanguage: String,
        val accentTheme: AccentTheme,
        val dynamicColor: Boolean,
        val reducedMotion: Boolean,
        val decimals: Int,
        val decimalSeparator: DecimalSeparator,
        val groupingSeparator: GroupingSeparator,
        val fiatCurrency: FiatCurrency,
        val heroSpendable: Boolean,
        val showChange24h: Boolean,
        val showMiningCard: Boolean,
        val startHidden: Boolean,
        val secureAllScreens: Boolean,
        val pollMode: PollMode,
        val preferredNetwork: Network,
        /** Version staged for the post-install "what's new" popup; empty when there is none. */
        val whatsNewVersion: String,
        val whatsNewNotes: String,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    private fun localeSymbols() = runCatching { DecimalFormatSymbols.getInstance(Locale.getDefault()) }.getOrElse { DecimalFormatSymbols(Locale.US) }

    private fun defaultDecimalSeparator(): DecimalSeparator =
        if (localeSymbols().decimalSeparator == ',') DecimalSeparator.COMMA else DecimalSeparator.PERIOD

    private fun defaultGroupingSeparator(): GroupingSeparator = when (localeSymbols().groupingSeparator) {
        ',' -> GroupingSeparator.COMMA
        '.' -> GroupingSeparator.PERIOD
        '\u00A0', '\u202F', ' ' -> GroupingSeparator.NARROW_SPACE
        '\'' -> GroupingSeparator.APOSTROPHE
        else -> GroupingSeparator.NARROW_SPACE
    }

    private fun defaultFiat(): FiatCurrency = runCatching { FiatCurrency.forLocale(Locale.getDefault()) }.getOrElse { FiatCurrency.USD }

    private fun read(): Snapshot {
        val decName = prefs.getString(KEY_DECIMAL_SEP, null)
        val grpName = prefs.getString(KEY_GROUPING_SEP, null)
        val fiatCode = prefs.getString(KEY_FIAT_CURRENCY, null)
        val snap = Snapshot(
            autoLockSeconds = prefs.getInt(KEY_AUTOLOCK, 300),
            requireAuthToSend = prefs.getBoolean(KEY_REQUIRE_AUTH_SEND, true),
            wipeAfterFailedAttempts = prefs.getBoolean(KEY_WIPE_AFTER_FAILED, false),
            customBlockbookUrl = Network.entries.mapNotNull { n -> prefs.getString(KEY_BLOCKBOOK_PREFIX + n.id, null)?.let { n.id to it } }.toMap(),
            feeTier = prefs.getString(KEY_FEE_TIER, "medium") ?: "medium",
            hideBalance = prefs.getBoolean(KEY_HIDE_BALANCE, false),
            showFiat = prefs.getBoolean(KEY_SHOW_FIAT, true),
            odometer = prefs.getBoolean(KEY_ODOMETER, true),
            odometerHaptics = prefs.getBoolean(KEY_ODOMETER_HAPTICS, true),
            notifyIncoming = prefs.getBoolean(KEY_NOTIFY_INCOMING, false),
            priceAlert = prefs.getBoolean(KEY_PRICE_ALERT, false),
            priceAlertPercent = prefs.getFloat(KEY_PRICE_ALERT_PERCENT, 5f).toDouble(),
            themeMode = ThemeMode.fromId(prefs.getString(KEY_THEME_MODE, ThemeMode.AUTO.id)),
            appLanguage = prefs.getString(KEY_APP_LANGUAGE, "") ?: "",
            accentTheme = AccentTheme.fromId(prefs.getString(KEY_ACCENT_THEME, AccentTheme.PEARL.id)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            decimals = prefs.getInt(KEY_DECIMALS, 4),
            decimalSeparator = if (decName == null) defaultDecimalSeparator() else DecimalSeparator.fromName(decName),
            groupingSeparator = if (grpName == null) defaultGroupingSeparator() else GroupingSeparator.fromName(grpName),
            fiatCurrency = if (fiatCode == null) defaultFiat() else FiatCurrency.fromCode(fiatCode),
            heroSpendable = prefs.getBoolean(KEY_HERO_SPENDABLE, false),
            showChange24h = prefs.getBoolean(KEY_SHOW_CHANGE_24H, true),
            showMiningCard = prefs.getBoolean(KEY_SHOW_MINING_CARD, true),
            startHidden = prefs.getBoolean(KEY_START_HIDDEN, false),
            secureAllScreens = prefs.getBoolean(KEY_SECURE_ALL, false),
            pollMode = PollMode.fromId(prefs.getString(KEY_POLL_MODE, PollMode.BALANCED.id)),
            preferredNetwork = Network.fromId(prefs.getString(KEY_PREF_NETWORK, null)),
            whatsNewVersion = prefs.getString(KEY_WHATS_NEW_VERSION, "") ?: "",
            whatsNewNotes = prefs.getString(KEY_WHATS_NEW_NOTES, "") ?: "",
        )
        applyFormat(snap)
        return snap
    }

    /** Pushes the display-only formatting preferences into the core [Format] singleton. */
    private fun applyFormat(s: Snapshot) {
        Format.config = FormatConfig(
            decimals = s.decimals,
            decimalSeparator = s.decimalSeparator,
            groupingSeparator = s.groupingSeparator,
            fiat = s.fiatCurrency,
        )
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    var autoLockSeconds: Int
        get() = _state.value.autoLockSeconds
        set(v) = edit { putInt(KEY_AUTOLOCK, v) }

    var requireAuthToSend: Boolean
        get() = _state.value.requireAuthToSend
        set(v) = edit { putBoolean(KEY_REQUIRE_AUTH_SEND, v) }

    /** Off by default. When on, 10 consecutive wrong passwords erase this wallet's keys and local history. */
    var wipeAfterFailedAttempts: Boolean
        get() = _state.value.wipeAfterFailedAttempts
        set(v) = edit { putBoolean(KEY_WIPE_AFTER_FAILED, v) }

    var feeTier: String
        get() = _state.value.feeTier
        set(v) = edit { putString(KEY_FEE_TIER, v) }

    var hideBalance: Boolean
        get() = _state.value.hideBalance
        set(v) = edit { putBoolean(KEY_HIDE_BALANCE, v) }

    /** Fetch and show an approximate fiat value (talks to public price APIs; addresses are never sent). */
    var showFiat: Boolean
        get() = _state.value.showFiat
        set(v) = edit { putBoolean(KEY_SHOW_FIAT, v) }

    /** Roll changed digits like an odometer instead of swapping the text. */
    var odometer: Boolean
        get() = _state.value.odometer
        set(v) = edit { putBoolean(KEY_ODOMETER, v) }

    /** Slot-machine tick on every odometer digit roll. No effect while [odometer] is off. */
    var odometerHaptics: Boolean
        get() = _state.value.odometerHaptics
        set(v) = edit { putBoolean(KEY_ODOMETER_HAPTICS, v) }

    /** Periodic background check for incoming payments with a system notification. */
    var notifyIncoming: Boolean
        get() = _state.value.notifyIncoming
        set(v) = edit { putBoolean(KEY_NOTIFY_INCOMING, v) }

    /** Background check for large 24h PRL price moves, with a system notification. */
    var priceAlert: Boolean
        get() = _state.value.priceAlert
        set(v) = edit { putBoolean(KEY_PRICE_ALERT, v) }

    /** Percentage move over 24 h that trips [priceAlert]. */
    var priceAlertPercent: Double
        get() = _state.value.priceAlertPercent
        set(v) = edit { putFloat(KEY_PRICE_ALERT_PERCENT, v.toFloat()) }

    var themeMode: ThemeMode
        get() = _state.value.themeMode
        set(v) = edit { putString(KEY_THEME_MODE, v.id) }

    /** BCP-47 language tag override, or "" to follow the system. */
    var appLanguage: String
        get() = _state.value.appLanguage
        set(v) = edit { putString(KEY_APP_LANGUAGE, v) }

    var accentTheme: AccentTheme
        get() = _state.value.accentTheme
        set(v) = edit { putString(KEY_ACCENT_THEME, v.id) }

    /** Use the Material You palette derived from the wallpaper (Android 12+ only). */
    var dynamicColor: Boolean
        get() = _state.value.dynamicColor
        set(v) = edit { putBoolean(KEY_DYNAMIC_COLOR, v) }

    /** Suppress rolling digits and screen transitions regardless of the animation toggles. */
    var reducedMotion: Boolean
        get() = _state.value.reducedMotion
        set(v) = edit { putBoolean(KEY_REDUCED_MOTION, v) }

    /** Decimals shown for balances and labels (2/4/6/8). */
    var decimals: Int
        get() = _state.value.decimals
        set(v) = edit { putInt(KEY_DECIMALS, v.coerceIn(0, 8)) }

    var decimalSeparator: DecimalSeparator
        get() = _state.value.decimalSeparator
        set(v) = edit { putString(KEY_DECIMAL_SEP, v.name) }

    var groupingSeparator: GroupingSeparator
        get() = _state.value.groupingSeparator
        set(v) = edit { putString(KEY_GROUPING_SEP, v.name) }

    var fiatCurrency: FiatCurrency
        get() = _state.value.fiatCurrency
        set(v) = edit { putString(KEY_FIAT_CURRENCY, v.code) }

    /** Show spendable rather than total as the big dashboard number. */
    var heroSpendable: Boolean
        get() = _state.value.heroSpendable
        set(v) = edit { putBoolean(KEY_HERO_SPENDABLE, v) }

    var showChange24h: Boolean
        get() = _state.value.showChange24h
        set(v) = edit { putBoolean(KEY_SHOW_CHANGE_24H, v) }

    var showMiningCard: Boolean
        get() = _state.value.showMiningCard
        set(v) = edit { putBoolean(KEY_SHOW_MINING_CARD, v) }

    /** Start each unlocked session with balances hidden, even if they were visible last time. */
    var startHidden: Boolean
        get() = _state.value.startHidden
        set(v) = edit { putBoolean(KEY_START_HIDDEN, v) }

    /** Keep FLAG_SECURE on every screen, not just the seed ones. */
    var secureAllScreens: Boolean
        get() = _state.value.secureAllScreens
        set(v) = edit { putBoolean(KEY_SECURE_ALL, v) }

    var pollMode: PollMode
        get() = _state.value.pollMode
        set(v) = edit { putString(KEY_POLL_MODE, v.id) }

    fun blockbookUrl(network: Network): String = _state.value.customBlockbookUrl[network.id] ?: network.defaultBlockbookUrl

    fun setBlockbookUrl(network: Network, url: String?) = edit {
        if (url.isNullOrBlank() || url.trim().trimEnd('/') == network.defaultBlockbookUrl) remove(KEY_BLOCKBOOK_PREFIX + network.id)
        else putString(KEY_BLOCKBOOK_PREFIX + network.id, url.trim().trimEnd('/'))
    }

    /** Network the user picked on the welcome screen before a wallet exists. */
    var preferredNetwork: Network
        get() = _state.value.preferredNetwork
        set(v) = edit { putString(KEY_PREF_NETWORK, v.id) }

    /**
     * Records the version and notes to show in the "what's new" popup once the
     * update is actually installed. Called just before handing the APK to the
     * installer, so a cancelled install leaves it pointing at a version that is
     * not on the device yet — harmless, it only fires when it matches the running
     * version.
     */
    fun stageWhatsNew(version: String, notes: String?) = edit {
        putString(KEY_WHATS_NEW_VERSION, version)
        putString(KEY_WHATS_NEW_NOTES, notes.orEmpty())
    }

    /** Clears the staged popup after the user dismissed it. */
    fun clearWhatsNew() = edit {
        remove(KEY_WHATS_NEW_VERSION)
        remove(KEY_WHATS_NEW_NOTES)
    }

    companion object {
        const val PREFS_NAME = "pocketprl.settings"
        const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_AUTOLOCK = "auto_lock_seconds"
        private const val KEY_REQUIRE_AUTH_SEND = "require_auth_send"
        private const val KEY_WIPE_AFTER_FAILED = "wipe_after_failed_attempts"
        private const val KEY_BLOCKBOOK_PREFIX = "blockbook_url_"
        private const val KEY_FEE_TIER = "fee_tier"
        private const val KEY_HIDE_BALANCE = "hide_balance"
        private const val KEY_SHOW_FIAT = "show_fiat"
        private const val KEY_ODOMETER = "odometer"
        private const val KEY_ODOMETER_HAPTICS = "odometer_haptics"
        private const val KEY_NOTIFY_INCOMING = "notify_incoming"
        private const val KEY_PRICE_ALERT = "price_alert"
        private const val KEY_PRICE_ALERT_PERCENT = "price_alert_percent"
        private const val KEY_PREF_NETWORK = "preferred_network"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_THEME = "accent_theme"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_DECIMALS = "decimals"
        private const val KEY_DECIMAL_SEP = "decimal_separator"
        private const val KEY_GROUPING_SEP = "grouping_separator"
        private const val KEY_FIAT_CURRENCY = "fiat_currency"
        private const val KEY_HERO_SPENDABLE = "hero_spendable"
        private const val KEY_SHOW_CHANGE_24H = "show_change_24h"
        private const val KEY_SHOW_MINING_CARD = "show_mining_card"
        private const val KEY_START_HIDDEN = "start_hidden"
        private const val KEY_SECURE_ALL = "secure_all_screens"
        private const val KEY_POLL_MODE = "poll_mode"
        private const val KEY_WHATS_NEW_VERSION = "whats_new_version"
        private const val KEY_WHATS_NEW_NOTES = "whats_new_notes"
    }
}
