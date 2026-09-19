package dev.pocketprl.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.ui.Biometrics
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.PasswordField
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.theme.PearlMark
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.UnlockViewModel
import kotlinx.coroutines.launch

@Composable
fun UnlockScreen(vm: UnlockViewModel, onUnlocked: () -> Unit, onAddWallet: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var showWipe by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val bioAvailable = activity != null && vm.biometricEnabled && Biometrics.available(activity)

    val haptics = rememberHaptics()
    LaunchedEffect(state.unlocked) { if (state.unlocked) { haptics.confirm(); onUnlocked() } }
    LaunchedEffect(state.error) { if (state.error != null) haptics.reject() }

    var bioLaunched by rememberSaveable { mutableStateOf(false) }
    val unlockTitle = stringResource(R.string.unlock_title, vm.walletName)
    val biometricKeyUnavailable = stringResource(R.string.unlock_error_biometric)

    fun biometric() {
        val act = activity ?: return
        val cipher = vm.biometricCipher() ?: run { vm.setError(biometricKeyUnavailable); return }
        scope.launch {
            when (val r = Biometrics.authenticate(act, unlockTitle, "PocketPRL", cipher)) {
                is Biometrics.Outcome.Success -> vm.unlockWithBiometric(r.cipher)
                is Biometrics.Outcome.Error -> vm.setError(r.message)
                is Biometrics.Outcome.Cancelled -> Unit
            }
        }
    }

    LaunchedEffect(bioAvailable) { if (bioAvailable && !bioLaunched) { bioLaunched = true; biometric() } }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PearlMark(size = 80.dp)
        Spacer(Modifier.height(16.dp))
        Text(vm.walletName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        Text(vm.network.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        PasswordField(password, { password = it }, stringResource(R.string.unlock_password), imeAction = ImeAction.Done, onDone = { if (password.isNotEmpty()) vm.unlockWithPassword(password) }, enabled = !state.busy)
        Spacer(Modifier.height(12.dp))
        state.error?.let { InfoBanner(it, BannerKind.ERROR); Spacer(Modifier.height(12.dp)) }
        PrimaryButton(stringResource(R.string.unlock_button), onClick = { vm.unlockWithPassword(password) }, enabled = password.isNotEmpty(), loading = state.busy)
        if (bioAvailable) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.unlock_use_biometrics), onClick = { biometric() }, icon = AppIcons.Fingerprint)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showSwitcher = true }) { Text(if (wallets.wallets.size > 1) stringResource(R.string.unlock_switch_wallet, wallets.wallets.size) else stringResource(R.string.unlock_add_another)) }
        }
        TextButton(onClick = { showWipe = true }) { Text(stringResource(R.string.unlock_forgot), color = MaterialTheme.colorScheme.error) }
    }

    if (showSwitcher) {
        WalletSwitcherSheet(wallets = wallets.wallets, activeId = vm.walletId, onPick = { vm.switchWallet(it) }, onAdd = onAddWallet, onDismiss = { showSwitcher = false })
    }

    if (showWipe) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showWipe = false },
            icon = { Icon(AppIcons.Shield, contentDescription = null) },
            title = { Text(stringResource(R.string.unlock_erase_title, vm.walletName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.unlock_erase_body))
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                }
            },
            confirmButton = {
                TextButton(enabled = typed == "ERASE", onClick = { scope.launch { vm.deleteWallet(); showWipe = false } }) { Text(stringResource(R.string.unlock_erase_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showWipe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
