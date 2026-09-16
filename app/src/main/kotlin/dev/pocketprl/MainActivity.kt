package dev.pocketprl

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
import dev.pocketprl.ui.theme.PocketPrlTheme
import dev.pocketprl.ui.theme.isDarkTheme

/** FragmentActivity (not ComponentActivity) because androidx.biometric needs it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePaymentIntent(intent)
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
            PocketPrlTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePaymentIntent(intent)
    }

    private fun handlePaymentIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW && Qr.isPaymentUri(data)) {
            (application as PocketPrlApp).container.pendingPaymentUri.value = data
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as PocketPrlApp).container.active?.session?.touch()
    }
}
