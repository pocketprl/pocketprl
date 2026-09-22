package dev.pocketprl

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.ui.AppNav
import dev.pocketprl.ui.Qr
import dev.pocketprl.ui.components.SecureFlags
import dev.pocketprl.ui.theme.PocketPrlTheme
import dev.pocketprl.ui.theme.isDarkTheme

/** FragmentActivity (not ComponentActivity) because androidx.biometric needs it. */
class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 12+ drops touches while another app draws over this window; older
        // versions need this flag or an overlay could drive the send/erase sliders.
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val container = (application as PocketPrlApp).container
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.themeMode, isSystemInDarkTheme())
            // System bar icon colour follows the app theme, not the OS theme.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // App-wide screenshot blocking, toggled in Settings › Security.
            DisposableEffect(settings.secureAllScreens) {
                SecureFlags.setAppWide(window, settings.secureAllScreens)
                onDispose {}
            }
            PocketPrlTheme(
                themeMode = settings.themeMode,
                accentTheme = settings.accentTheme,
                dynamicColor = settings.dynamicColor,
                reducedMotion = settings.reducedMotion,
            ) {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Routes the three ways the app can be opened with a URI: a `pearl:` payment
     * link (Send prefilled), a `pocketprl://tx/<txid>` deep link from a payment
     * notification, and the `pocketprl://send|receive|scan` app shortcuts.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val container = (application as PocketPrlApp).container
        if (uri.scheme.equals("pocketprl", ignoreCase = true)) {
            when (uri.host?.lowercase()) {
                "send" -> container.pendingShortcut.value = Shortcut.SEND
                "receive" -> container.pendingShortcut.value = Shortcut.RECEIVE
                "scan" -> container.pendingShortcut.value = Shortcut.SCAN
                "tx" -> uri.lastPathSegment
                    ?.takeIf { it.isNotBlank() && it.matches(Regex("^[0-9a-fA-F]{64}$")) }
                    ?.let { container.pendingTxid.value = it }
            }
            return
        }
        val data = intent.dataString ?: return
        if (Qr.isPaymentUri(data)) container.pendingPaymentUri.value = data
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as PocketPrlApp).container.active?.session?.touch()
    }
}
