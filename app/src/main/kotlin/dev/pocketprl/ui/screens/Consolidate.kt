package dev.pocketprl.ui.screens

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.format.Format
import dev.pocketprl.data.WalletRepository
import dev.pocketprl.ui.Biometrics
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.FeeOption
import dev.pocketprl.ui.components.FeeTierPicker
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.KeyValueRow
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.PasswordField
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.SlideToConfirm
import dev.pocketprl.ui.components.etaBlocks
import dev.pocketprl.ui.components.shortAddress
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.LocalReducedMotion
import dev.pocketprl.ui.vm.ConsolidateViewModel
import dev.pocketprl.ui.vm.FeeTier
import dev.pocketprl.ui.vm.WalletViewModel
import kotlinx.coroutines.launch

/**
 * Full-screen UTXO consolidation: sweeps every spendable output into the
 * wallet's own receive address. Shows exactly what is about to be signed (the
 * swept total, the fee, the number of inputs and the destination) and uses the
 * same slide-to-confirm and re-authentication as Send.
 */
@Composable
fun ConsolidateScreen(vm: ConsolidateViewModel, walletVm: WalletViewModel, onBack: () -> Unit, explorerUrl: (String) -> String) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snap by walletVm.snapshot.collectAsStateWithLifecycle()
    val walletSettings by walletVm.settings.collectAsStateWithLifecycle()
    val secondsPerBlock by walletVm.blockSeconds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // authBusy keeps the slider thumb parked while a prompt is up or a password is being checked.
    var authBusy by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var authPassword by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    // Bumped on every failed or cancelled confirmation so the slider always springs back.
    var sliderReset by remember { mutableStateOf(0) }
    fun failAuth(message: String?) {
        authError = message
        authBusy = false
        sliderReset += 1
    }
    val leavingApp = rememberLeaveAppMarker()
    val reduced = LocalReducedMotion.current

    val ticker = snap.network.ticker
    val usd = vm.usdPerPrl

    AnimatedContent(
        targetState = s.sentTxid,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(450, delayMillis = 80)) + slideInVertically(tween(450, delayMillis = 80)) { it / 14 }) togetherWith fadeOut(tween(250))
        },
        label = "consolidate",
    ) { txid ->
        if (txid != null) {
            LaunchedEffect(txid) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
            val p = s.prepared
            SentContent(
                txid = txid,
                amountGrain = p?.build?.amount ?: 0L,
                network = snap.network,
                toLabel = stringResource(R.string.consolidate_to_self),
                onExplorer = { leavingApp(); runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, explorerUrl(txid).toUri())) } },
                onDone = { vm.reset(); onBack() },
                animate = !reduced,
                odometer = walletSettings.odometer && !reduced,
                odometerHaptics = walletSettings.odometerHaptics,
                secondsPerBlock = secondsPerBlock,
                titleRes = R.string.consolidate_sent_title,
            )
        } else {
            ScreenScaffold(title = stringResource(R.string.consolidate_title), onBack = onBack) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        HeroIcon(AppIcons.Swap)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.consolidate_hero_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.consolidate_hero_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (s.loading) {
                        LoadingBlock(stringResource(R.string.consolidate_not_ready), modifier = Modifier.fillMaxWidth())
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.send_network_fee))
                        val options = listOf(
                            FeeOption(FeeTier.MEDIUM, stringResource(R.string.send_fee_normal), etaBlocks(WalletRepository.MEDIUM_BLOCKS, secondsPerBlock), s.fees?.mediumPerKb?.let { stringResource(R.string.send_grain_per_vbyte, Amount.formatGrainPerVbyte(it)) }),
                            FeeOption(FeeTier.FAST, stringResource(R.string.send_fee_fast), etaBlocks(WalletRepository.FAST_BLOCKS, secondsPerBlock), s.fees?.fastPerKb?.let { stringResource(R.string.send_grain_per_vbyte, Amount.formatGrainPerVbyte(it)) }),
                        )
                        FeeTierPicker(options = options, selected = s.tier, onSelect = vm::setTier)
                    }

                    s.prepareError?.let { InfoBanner(it, BannerKind.ERROR) }
                    s.error?.let { InfoBanner(it, BannerKind.ERROR) }

                    s.prepared?.let { p ->
                        SectionCard {
                            Text(stringResource(R.string.consolidate_summary_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Full precision: this is what gets signed.
                            KeyValueRow(stringResource(R.string.label_amount), "${Amount.pretty(p.build.amount, 8)} $ticker")
                            KeyValueRow(stringResource(R.string.label_fee), stringResource(R.string.send_summary_fee, Amount.pretty(p.build.fee, 8), ticker, p.build.vsize))
                            KeyValueRow(stringResource(R.string.label_total), "${Amount.pretty(p.build.amount + p.build.fee, 8)} $ticker")
                            if (vm.showFiat) Amount.fiat(p.build.amount + p.build.fee, usd)?.let { KeyValueRow(stringResource(R.string.send_fiat_approx, Format.config.fiat.code.uppercase()), it) }
                            KeyValueRow(stringResource(R.string.send_inputs), "${p.build.selected.size}")
                            KeyValueRow(stringResource(R.string.consolidate_to_self), s.target?.let { shortAddress(it) } ?: "")
                        }
                    }

                    val needBio = vm.requireAuth && vm.biometricEnabled && activity != null && Biometrics.available(activity)
                    val needPw = vm.requireAuth && !needBio
                    SlideToConfirm(
                        onComplete = {
                            val p = s.prepared ?: run { sliderReset++; return@SlideToConfirm }
                            authError = null
                            when {
                                needBio -> {
                                    val cipher = vm.biometricCipher()
                                    if (cipher == null) { failAuth(context.getString(R.string.send_bio_unavailable)); return@SlideToConfirm }
                                    authBusy = true
                                    scope.launch {
                                        val title = context.getString(R.string.consolidate_title)
                                        val to = context.getString(R.string.consolidate_to_self)
                                        when (val r = Biometrics.authenticate(activity, title, to, cipher, negative = context.getString(R.string.action_cancel))) {
                                            is Biometrics.Outcome.Success -> if (vm.confirmBiometric(r.cipher)) { authBusy = false; vm.authorizeSend(p); vm.send(p) } else failAuth(context.getString(R.string.send_auth_failed))
                                            is Biometrics.Outcome.Error -> failAuth(r.message)
                                            is Biometrics.Outcome.Cancelled -> failAuth(null)
                                        }
                                    }
                                }
                                needPw -> { authPassword = ""; authBusy = true; askPassword = true }
                                else -> vm.send(p)
                            }
                        },
                        label = stringResource(R.string.consolidate_slide),
                        icon = Icons.AutoMirrored.Filled.Send,
                        enabled = s.prepared != null && !s.sending,
                        held = authBusy || s.sending,
                        busy = s.sending,
                        resetKey = sliderReset,
                        notReady = stringResource(R.string.consolidate_not_ready),
                        sending = stringResource(R.string.consolidate_sending),
                        confirming = stringResource(R.string.consolidate_sending),
                        slideHint = stringResource(R.string.consolidate_slide_hint),
                    )
                    if (!askPassword) authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    val p = s.prepared
    if (askPassword && p != null) {
        var checking by remember { mutableStateOf(false) }
        // Keep the dialog open on a wrong password (so the next try is one field away) but free the slider.
        val cancel: () -> Unit = { askPassword = false; authBusy = false; sliderReset += 1 }
        val submit = {
            if (authPassword.isNotEmpty() && !checking) {
                checking = true
                scope.launch {
                    val err = vm.verifyPassword(authPassword)
                    checking = false
                    if (err == null) { askPassword = false; authBusy = false; vm.authorizeSend(p); vm.send(p) } else failAuth(err)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!checking) cancel() },
            title = { Text(stringResource(R.string.send_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.send_password_body, Amount.pretty(p.build.amount, 8), ticker, stringResource(R.string.consolidate_to_self)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(authPassword, { authPassword = it; authError = null }, stringResource(R.string.send_password_title), imeAction = ImeAction.Done, onDone = { submit() })
                    authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = authPassword.isNotEmpty() && !checking, onClick = { submit() }) { Text(stringResource(R.string.action_send)) } },
            dismissButton = { TextButton(enabled = !checking, onClick = cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
