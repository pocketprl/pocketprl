package dev.pocketprl.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SecureWindow
import dev.pocketprl.ui.components.SlideToConfirm
import dev.pocketprl.ui.components.rememberHaptics
import kotlinx.coroutines.launch

/** The word the user must type before the erase slider arms. */
private const val ERASE_WORD = "ERASE"

/**
 * Fullscreen erase confirmation. A red trash mark, the warning, and a typed
 * word gate a red slide-to-erase control: the same pattern as sending, for the
 * one action that cannot be undone. Used both from the unlock screen ("forgot
 * password") and from Settings' danger zone.
 */
@Composable
fun EraseWalletScreen(walletName: String, onBack: () -> Unit, onErased: suspend () -> Unit) {
    SecureWindow()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val armed = typed.trim() == ERASE_WORD
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // Erasing has started; do not let a back gesture abandon it half-done.
    BackHandler(enabled = busy) {}

    ScreenScaffold(title = stringResource(R.string.erase_title), onBack = if (busy) null else onBack) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(44.dp))
            }
            Text(stringResource(R.string.erase_heading, walletName), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.erase_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.erase_type_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = FieldShape,
                isError = typed.isNotEmpty() && !armed,
            )

            SlideToConfirm(
                onComplete = {
                    if (!armed || busy) return@SlideToConfirm
                    busy = true
                    haptics.confirm()
                    // If the delete itself fails, release the control rather than leaving it parked.
                    scope.launch { runCatching { onErased() }.onFailure { busy = false } }
                },
                label = stringResource(R.string.erase_slider),
                enabled = armed && !busy,
                held = busy,
                busy = busy,
                icon = Icons.Filled.Delete,
                danger = true,
                notReady = stringResource(R.string.erase_slider_locked),
                sending = stringResource(R.string.erase_working),
                confirming = stringResource(R.string.erase_working),
                slideHint = stringResource(R.string.erase_slider_hint),
            )

            SecondaryButton(stringResource(R.string.action_cancel), onClick = onBack, enabled = !busy)
        }
    }
}
