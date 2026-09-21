package dev.pocketprl.ui.screens

import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.format.Format
import dev.pocketprl.data.WalletRepository
import dev.pocketprl.data.db.Contact
import dev.pocketprl.ui.Biometrics
import dev.pocketprl.ui.PermissionOutcome
import dev.pocketprl.ui.Permissions
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.rememberQrScanner
import dev.pocketprl.ui.components.AmountHero
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.ContactAvatar
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.FeeOption
import dev.pocketprl.ui.components.FeeTierPicker
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.HIDDEN
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.KeyValueRow
import dev.pocketprl.ui.components.PasswordField
import dev.pocketprl.ui.components.RecipientField
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.SlideToSend
import dev.pocketprl.ui.components.addressErrorText
import dev.pocketprl.ui.components.etaBlocks
import dev.pocketprl.ui.components.readClipboard
import dev.pocketprl.ui.theme.LocalReducedMotion
import dev.pocketprl.ui.vm.FeeTier
import dev.pocketprl.ui.vm.SendViewModel
import dev.pocketprl.ui.vm.WalletViewModel
import dev.pocketprl.ui.vm.appContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(vm: SendViewModel, walletVm: WalletViewModel, onBack: () -> Unit, explorerUrl: (String) -> String) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snap by walletVm.snapshot.collectAsStateWithLifecycle()
    val price by walletVm.price.collectAsStateWithLifecycle()
    val secondsPerBlock by walletVm.blockSeconds.collectAsStateWithLifecycle()
    val container = appContainer()
    val pending by container.pendingPaymentUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // authBusy keeps the slider thumb parked while a prompt is up or a password is being checked.
    var authBusy by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
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

    LaunchedEffect(pending) {
        pending?.let { uri ->
            vm.applyPaymentRequest(uri)
            container.pendingPaymentUri.value = null
        }
    }

    var cameraDenied by remember { mutableStateOf<PermissionOutcome?>(null) }
    // Hoisted above the sent/content split: both branches read wallet settings.
    // "Hide balances" applies to the spendable line only; the summary of what is about to be signed stays visible.
    val walletSettings by walletVm.settings.collectAsStateWithLifecycle()
    val scan = rememberQrScanner(
        prompt = stringResource(R.string.send_scan_prompt),
        onScanned = { text -> if (!vm.applyPaymentRequest(text)) vm.setAddress(text) },
        onUnavailable = { cameraDenied = it },
    )
    LifecycleResumeEffect(cameraDenied) {
        if (cameraDenied != null && Permissions.granted(context, android.Manifest.permission.CAMERA)) cameraDenied = null
        onPauseOrDispose {}
    }
    // The scan app shortcut lands here with a request to open the scanner.
    val scanRequested by container.requestScan.collectAsStateWithLifecycle()
    LaunchedEffect(scanRequested) {
        if (scanRequested) { container.requestScan.value = false; scan() }
    }

    AnimatedContent(
        targetState = s.sentTxid,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(450, delayMillis = 80)) + slideInVertically(tween(450, delayMillis = 80)) { it / 14 }) togetherWith fadeOut(tween(250))
        },
        label = "send",
    ) { txid ->
    if (txid != null) {
        LaunchedEffect(txid) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
        val p = s.prepared
        SentContent(
            txid = txid,
            amountGrain = p?.build?.amount ?: 0L,
            network = snap.network,
            toLabel = s.contactName ?: p?.let { Address.short(it.toAddress, 12, 8) } ?: "",
            onExplorer = { leavingApp(); runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, explorerUrl(txid).toUri())) } },
            onDone = { vm.reset(); onBack() },
            animate = !reduced,
            odometer = walletSettings.odometer && !reduced,
            odometerHaptics = walletSettings.odometerHaptics,
            secondsPerBlock = secondsPerBlock,
        )
    } else {

    val ticker = snap.network.ticker
    val usd = price.usdPerPrl
    val canFiat = vm.showFiat && usd != null
    val amountGrain = Amount.parse(s.amountText)
    // In fiat mode the field holds USD text; the view-model keeps the PRL it converts to.
    var fiatMode by rememberSaveable { mutableStateOf(false) }
    var fiatText by rememberSaveable { mutableStateOf("") }
    var pushed by remember { mutableStateOf<String?>(null) }
    fun fiatTextFor(grain: Long?): String {
        val group = Format.config.groupingSeparator.char?.toString() ?: ""
        val decimal = Format.config.decimalSeparator.char.toString()
        return grain?.let { Amount.fiat(it, usd) }?.removePrefix(Format.config.fiat.symbol)?.replace(group, "")?.replace(decimal, ".") ?: ""
    }
    // MAX or a payment link can rewrite the PRL amount underneath the USD field.
    LaunchedEffect(s.amountText) { if (fiatMode && s.amountText != pushed) fiatText = fiatTextFor(amountGrain) }
    if (fiatMode && !canFiat) fiatMode = false
    val fiatHint = if (canFiat && amountGrain != null) Amount.fiat(amountGrain, usd) else null
    val spendable = if (walletSettings.hideBalance) HIDDEN else Amount.pretty(snap.balances.spendable)

    ScreenScaffold(title = stringResource(R.string.send_title, ticker), subtitle = stringResource(R.string.send_spendable, spendable, ticker), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val addrInvalid = s.addressError ?: rememberAddrReason(context, s.address, snap.network)
            val amountInvalid = s.amountText.isNotBlank() && amountGrain == null

            AmountHero(
                text = if (fiatMode) fiatText else s.amountText,
                onTextChange = { v ->
                    if (fiatMode) {
                        fiatText = v
                        val text = if (v.isBlank()) "" else Amount.grainForUsd(v, usd)?.let { Amount.format(it) } ?: ""
                        pushed = text
                        vm.setAmount(text)
                    } else vm.setAmount(v)
                },
                ticker = ticker,
                fiatMode = fiatMode,
                secondary = when {
                    fiatMode -> amountGrain?.let { stringResource(R.string.send_fiat_approx, "${Amount.pretty(it, 8)} $ticker") }
                    fiatHint != null -> stringResource(R.string.send_fiat_approx, fiatHint)
                    else -> s.label?.let { stringResource(R.string.send_for_label, it) }
                },
                error = if (amountInvalid) stringResource(R.string.send_enter_valid_amount) else null,
                onSwap = if (canFiat) ({ fiatMode = !fiatMode; if (fiatMode) fiatText = fiatTextFor(amountGrain) }) else null,
                onMax = vm::useMax,
                maxSelected = s.sendMax,
                maxEnabled = s.feeRatePerKb != null,
                modifier = Modifier.padding(top = 8.dp),
            )

            RecipientField(
                address = s.address,
                onAddressChange = vm::setAddress,
                hrp = snap.network.hrp,
                contactName = s.contactName,
                error = addrInvalid,
                onContacts = { showContacts = true },
                onPaste = { readClipboard(context)?.let { c -> if (!vm.applyPaymentRequest(c)) vm.setAddress(c.trim()) } },
                onScan = scan,
            )
            cameraDenied?.let { outcome ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (outcome == PermissionOutcome.DENIED_PERMANENTLY) stringResource(R.string.send_camera_off) else stringResource(R.string.send_camera_needed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                    )
                    if (outcome == PermissionOutcome.DENIED_PERMANENTLY) {
                        TextButton(onClick = { leavingApp(); Permissions.openAppSettings(context) }) { Text(stringResource(R.string.send_open_settings)) }
                    }
                }
            }

            if (s.externalRequest) {
                InfoBanner(
                    stringResource(R.string.send_external_warning),
                    BannerKind.WARNING,
                    title = stringResource(R.string.send_external_warning_title),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.send_network_fee))
                    TextButton(onClick = { vm.setTier(if (s.tier == FeeTier.CUSTOM) FeeTier.MEDIUM else FeeTier.CUSTOM) }) {
                        Text(if (s.tier == FeeTier.CUSTOM) stringResource(R.string.send_use_estimates) else stringResource(R.string.send_custom_rate))
                    }
                }
                val options = listOf(
                    FeeOption(FeeTier.MEDIUM, stringResource(R.string.send_fee_normal), etaBlocks(WalletRepository.MEDIUM_BLOCKS, secondsPerBlock), s.fees?.mediumPerKb?.let { stringResource(R.string.send_grain_per_vbyte, Amount.formatGrainPerVbyte(it)) }),
                    FeeOption(FeeTier.FAST, stringResource(R.string.send_fee_fast), etaBlocks(WalletRepository.FAST_BLOCKS, secondsPerBlock), s.fees?.fastPerKb?.let { stringResource(R.string.send_grain_per_vbyte, Amount.formatGrainPerVbyte(it)) }),
                )
                FeeTierPicker(options = options, selected = s.tier, onSelect = vm::setTier)
                if (s.tier == FeeTier.CUSTOM) {
                    OutlinedTextField(
                        value = s.customRateText, onValueChange = vm::setCustomRate, label = { Text(stringResource(R.string.send_fee_rate_label)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = FieldShape, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = s.customRateText.isNotBlank() && s.feeRatePerKb == null,
                    )
                }
                s.feeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }

            s.prepareError?.let { InfoBanner(it, BannerKind.ERROR) }
            s.error?.let { InfoBanner(it, BannerKind.ERROR) }

            s.prepared?.let { p ->
                SectionCard {
                    Text(stringResource(R.string.send_summary), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Full precision: this is what gets signed.
                    KeyValueRow(stringResource(R.string.label_amount), "${Amount.pretty(p.build.amount, 8)} $ticker")
                    KeyValueRow(stringResource(R.string.label_fee), stringResource(R.string.send_summary_fee, Amount.pretty(p.build.fee, 8), ticker, p.build.vsize))
                    KeyValueRow(stringResource(R.string.label_total), "${Amount.pretty(p.build.amount + p.build.fee, 8)} $ticker")
                    if (vm.showFiat) Amount.fiat(p.build.amount + p.build.fee, usd)?.let { KeyValueRow(stringResource(R.string.send_fiat_approx, Format.config.fiat.code.uppercase()), it) }
                    val inputsValue = if (p.build.change > 0) stringResource(R.string.send_inputs_change, p.build.selected.size, Amount.pretty(p.build.change, 8)) else "${p.build.selected.size}"
                    KeyValueRow(stringResource(R.string.send_inputs), inputsValue)
                    KeyValueRow(
                        stringResource(R.string.send_to),
                        s.contactName ?: if (s.externalRequest) p.toAddress else Address.short(p.toAddress, 14, 10),
                    )
                }
            }

            val needBio = vm.requireAuth && vm.biometricEnabled && activity != null && Biometrics.available(activity)
            val needPw = vm.requireAuth && !needBio
            SlideToSend(
                enabled = s.prepared != null && !s.sending,
                held = authBusy || s.sending,
                busy = s.sending,
                resetKey = sliderReset,
                onComplete = {
                    val p = s.prepared
                    if (p == null || !vm.validateAddressNow()) { sliderReset++; return@SlideToSend }
                    authError = null
                    when {
                        needBio -> {
                            val cipher = vm.biometricCipher()
                            if (cipher == null) { failAuth(context.getString(R.string.send_bio_unavailable)); return@SlideToSend }
                            authBusy = true
                            scope.launch {
                                val title = context.getString(R.string.send_title, "${Amount.pretty(p.build.amount, 8)} $ticker")
                                val to = context.getString(R.string.sent_to, s.contactName ?: Address.short(p.toAddress, 14, 10))
                                when (val r = Biometrics.authenticate(activity!!, title, to, cipher, negative = context.getString(R.string.action_cancel))) {
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
            )
            if (!askPassword) authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (s.prepared == null && !s.sending && s.prepareError == null && s.error == null) {
                val why = when {
                    s.amountText.isBlank() -> stringResource(R.string.send_why_amount_blank)
                    amountGrain == null -> stringResource(R.string.send_why_amount_invalid)
                    s.address.isBlank() -> stringResource(R.string.send_why_recipient_blank)
                    addrInvalid != null -> stringResource(R.string.send_why_recipient_invalid)
                    s.feeRatePerKb == null -> if (s.feeError != null) stringResource(R.string.send_why_fee_unavailable) else stringResource(R.string.send_why_waiting_fees)
                    else -> stringResource(R.string.send_why_preparing)
                }
                Text(why, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    } // form branch
    } // AnimatedContent

    if (showContacts) {
        // Keyed on the wallet revision so a contact saved in Settings shows up without leaving the screen.
        ContactPickerSheet(contacts = remember(snap.revision) { vm.contacts() }, onDismiss = { showContacts = false }, onPick = { vm.setAddress(it.address); showContacts = false })
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
                    Text(stringResource(R.string.send_password_body, Amount.pretty(p.build.amount, 8), snap.network.ticker, s.contactName ?: Address.short(p.toAddress, 14, 10)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(authPassword, { authPassword = it; authError = null }, stringResource(R.string.send_password_title), imeAction = ImeAction.Done, onDone = { submit() })
                    authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = authPassword.isNotEmpty() && !checking, onClick = { submit() }) { Text(stringResource(R.string.action_send)) } },
            dismissButton = { TextButton(enabled = !checking, onClick = cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerSheet(contacts: List<Contact>, onDismiss: () -> Unit, onPick: (Contact) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.action_contacts), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (contacts.isEmpty()) {
            EmptyState(Icons.Filled.Person, stringResource(R.string.settings_contacts_empty), text = stringResource(R.string.send_contacts_empty_body))
        } else {
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                items(contacts, key = { it.address }) { c ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(c.name)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(Address.short(c.address, 14, 8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberAddrReason(context: Context, address: String, network: Network): String? {
    val parsed = remember(address, network) { if (address.isBlank()) null else Address.parse(address, network) }
    return (parsed as? Address.Result.Invalid)?.let { addressErrorText(context, it) }
}
