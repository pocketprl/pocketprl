package dev.pocketprl.data

import android.content.Context
import android.content.SharedPreferences
import dev.pocketprl.core.chain.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-secret preferences. */
enum class ThemeMode(val id: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");
    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pocketprl.settings", Context.MODE_PRIVATE)

    data class Snapshot(
        val autoLockSeconds: Int,
        val requireAuthToSend: Boolean,
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
        val preferredNetwork: Network,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    private fun read() = Snapshot(
        autoLockSeconds = prefs.getInt(KEY_AUTOLOCK, 300),
        requireAuthToSend = prefs.getBoolean(KEY_REQUIRE_AUTH_SEND, true),
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
        preferredNetwork = Network.fromId(prefs.getString(KEY_PREF_NETWORK, null)),
    )

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

    var feeTier: String
        get() = _state.value.feeTier
        set(v) = edit { putString(KEY_FEE_TIER, v) }

    var hideBalance: Boolean
        get() = _state.value.hideBalance
        set(v) = edit { putBoolean(KEY_HIDE_BALANCE, v) }

    /** Fetch and show an approximate USD value (talks to public price APIs; addresses are never sent). */
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

    fun blockbookUrl(network: Network): String = _state.value.customBlockbookUrl[network.id] ?: network.defaultBlockbookUrl

    fun setBlockbookUrl(network: Network, url: String?) = edit {
        if (url.isNullOrBlank() || url.trim().trimEnd('/') == network.defaultBlockbookUrl) remove(KEY_BLOCKBOOK_PREFIX + network.id)
        else putString(KEY_BLOCKBOOK_PREFIX + network.id, url.trim().trimEnd('/'))
    }

    /** Network the user picked on the welcome screen before a wallet exists. */
    var preferredNetwork: Network
        get() = _state.value.preferredNetwork
        set(v) = edit { putString(KEY_PREF_NETWORK, v.id) }

    companion object {
        private const val KEY_AUTOLOCK = "auto_lock_seconds"
        private const val KEY_REQUIRE_AUTH_SEND = "require_auth_send"
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
    }
}
