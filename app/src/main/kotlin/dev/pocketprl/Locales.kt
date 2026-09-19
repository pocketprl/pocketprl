package dev.pocketprl

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.pocketprl.data.Settings
import java.util.Locale

/**
 * Per-app language override. The chosen BCP-47 tag is saved in the shared
 * preferences and applied by wrapping the base context, so it works on every
 * supported API without AppCompat. An empty tag means "follow the system".
 */
object Locales {
    /** In-app language choices, labelled with their own endonym (never translated). */
    val supported = listOf(
        Lang("en", "English"),
        Lang("de", "Deutsch"),
        Lang("nl", "Nederlands"),
        Lang("fr", "Français"),
        Lang("it", "Italiano"),
        Lang("es", "Español"),
        Lang("pt", "Português"),
        Lang("pl", "Polski"),
        Lang("cs", "Čeština"),
        Lang("ru", "Русский"),
        Lang("uk", "Українська"),
        Lang("sv", "Svenska"),
        Lang("da", "Dansk"),
        Lang("nb", "Norsk bokmål"),
        Lang("fi", "Suomi"),
        Lang("el", "Ελληνικά"),
        Lang("tr", "Türkçe"),
        Lang("ro", "Română"),
        Lang("hu", "Magyar"),
    )

    data class Lang(val tag: String, val label: String)

    /** Wraps [base] with the saved locale. Safe to call from `attachBaseContext`. */
    fun wrap(base: Context): Context {
        val tag = savedTag(base)
        val system = base.resources.configuration.locales[0]
        val locale = if (tag.isBlank()) system else Locale.forLanguageTag(tag)
        runCatching { Locale.setDefault(locale) }
        if (tag.isBlank()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Applies a change immediately: framework API on 13+, activity recreate below. */
    fun applyNow(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = activity.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    fun savedTag(context: Context): String =
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Settings.KEY_APP_LANGUAGE, "")?.orEmpty() ?: ""
}
