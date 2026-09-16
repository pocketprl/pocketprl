package dev.pocketprl.ui.screens

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
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
import dev.pocketprl.ui.components.readClipboard
import dev.pocketprl.ui.vm.FeeTier
import dev.pocketprl.ui.vm.SendViewModel
import dev.pocketprl.ui.vm.WalletViewModel
import dev.pocketprl.ui.vm.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val leavingApp = rememberLeaveAppMarker()

    LaunchedEffect(pending) {
        pending?.let { uri ->
            vm.applyPaymentRequest(uri)
            container.pendingPaymentUri.value = null
        }
    }

    var cameraDenied by remember { mutableStateOf<PermissionOutcome?>(null) }
    val scan = rememberQrScanner(
        prompt = "Scan a Pearl address or payment link",
        onScanned = { text -> if (!vm.applyPaymentRequest(text)) vm.setAddress(text) },
        onUnavailable = { cameraDenied = it },
    )
    LifecycleResumeEffect(cameraDenied) {
        if (cameraDenied != null && Permissions.granted(context, android.Manifest.permission.CAMERA)) cameraDenied = null
        onPauseOrDispose {}
    }

    AnimatedContent(
        targetState = s.sentTxid,
        transitionSpec = {
            (fadeIn(tween(450, delayMillis = 80)) + slideInVertically(tween(450, delayMillis = 80)) { it / 14 }) togetherWith fadeOut(tween(250))
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
    fun usdTextFor(grain: Long?): String = grain?.let { Amount.fiat(it, usd) }?.removePrefix("$")?.replace(Amount.GROUP_SEPARATOR.toString(), "") ?: ""
    // MAX or a payment link can rewrite the PRL amount underneath the USD field.
    LaunchedEffect(s.amountText) { if (fiatMode && s.amountText != pushed) fiatText = usdTextFor(amountGrain) }
    if (fiatMode && !canFiat) fiatMode = false
    val fiatHint = if (canFiat && amountGrain != null) Amount.fiat(amountGrain, usd) else null
    // "Hide balances" applies to the spendable line only; the summary of what is about to be signed stays visible.
    val walletSettings by walletVm.settings.collectAsStateWithLifecycle()
    val spendable = if (walletSettings.hideBalance) HIDDEN else Amount.pretty(snap.balances.spendable)

    ScreenScaffold(title = "Send $ticker", subtitle = "Spendable $spendable $ticker", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val addrInvalid = s.addressError ?: rememberAddrReason(s.address, snap.network)
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
                    fiatMode -> amountGrain?.let { "≈ ${Amount.pretty(it, 8)} $ticker" }
                    fiatHint != null -> "≈ $fiatHint"
                    s.label != null -> "For: ${s.label}"
                    else -> null
                },
                error = if (amountInvalid) "Enter a valid amount (up to 8 decimals)" else null,
                onSwap = if (canFiat) ({ fiatMode = !fiatMode; if (fiatMode) fiatText = usdTextFor(amountGrain) }) else null,
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
                        if (outcome == PermissionOutcome.DENIED_PERMANENTLY) "Camera access is off for PocketPRL." else "Scanning needs the camera.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                    )
                    if (outcome == PermissionOutcome.DENIED_PERMANENTLY) {
                        TextButton(onClick = { leavingApp(); Permissions.openAppSettings(context) }) { Text("Open settings") }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Network fee")
                    TextButton(onClick = { vm.setTier(if (s.tier == FeeTier.CUSTOM) FeeTier.MEDIUM else FeeTier.CUSTOM) }) {
                        Text(if (s.tier == FeeTier.CUSTOM) "Use estimates" else "Custom rate")
                    }
                }
                val options = listOf(
                    FeeOption(FeeTier.MEDIUM, "Normal", Network.etaForBlocks(WalletRepository.MEDIUM_BLOCKS, secondsPerBlock), s.fees?.mediumPerKb?.let { "${Amount.formatGrainPerVbyte(it)} grain/vB" }),
                    FeeOption(FeeTier.FAST, "Fast", Network.etaForBlocks(WalletRepository.FAST_BLOCKS, secondsPerBlock), s.fees?.fastPerKb?.let { "${Amount.formatGrainPerVbyte(it)} grain/vB" }),
                )
                FeeTierPicker(options = options, selected = s.tier, onSelect = vm::setTier)
                if (s.tier == FeeTier.CUSTOM) {
                    OutlinedTextField(
                        value = s.customRateText, onValueChange = vm::setCustomRate, label = { Text("Fee rate (grain per vbyte, min 1)") }, singleLine = true,
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
                    Text("Summary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Full precision: this is what gets signed.
                    KeyValueRow("Amount", "${Amount.pretty(p.build.amount, 8)} $ticker")
                    KeyValueRow("Fee", "${Amount.pretty(p.build.fee, 8)} $ticker (${p.build.vsize} vB)")
                    KeyValueRow("Total", "${Amount.pretty(p.build.amount + p.build.fee, 8)} $ticker")
                    if (vm.showFiat) Amount.fiat(p.build.amount + p.build.fee, usd)?.let { KeyValueRow("≈ USD", it) }
                    KeyValueRow("Inputs", "${p.build.selected.size}" + if (p.build.change > 0) " • change ${Amount.pretty(p.build.change, 8)}" else "")
                    KeyValueRow("To", s.contactName ?: Address.short(p.toAddress, 14, 10))
                }
            }

            val needBio = vm.requireAuth && vm.biometricEnabled && activity != null && Biometrics.available(activity)
            val needPw = vm.requireAuth && !needBio
            SlideToSend(
                enabled = s.prepared != null && !s.sending,
                held = authBusy || s.sending,
                busy = s.sending,
                onComplete = {
                    val p = s.prepared
                    if (p == null || !vm.validateAddressNow()) return@SlideToSend
                    authError = null
                    when {
                        needBio -> {
                            val cipher = vm.biometricCipher()
                            if (cipher == null) { authError = "Biometric key unavailable; disable and re-enable biometrics in Settings."; return@SlideToSend }
                            authBusy = true
                            scope.launch {
                                val title = "Send ${Amount.pretty(p.build.amount, 8)} $ticker"
                                val to = "to ${s.contactName ?: Address.short(p.toAddress, 14, 10)}"
                                when (val r = Biometrics.authenticate(activity!!, title, to, cipher, negative = "Cancel")) {
                                    is Biometrics.Outcome.Success -> if (vm.confirmBiometric(r.cipher)) { authBusy = false; vm.send(p) } else { authError = "Authentication failed"; authBusy = false }
                                    is Biometrics.Outcome.Error -> { authError = r.message; authBusy = false }
                                    is Biometrics.Outcome.Cancelled -> authBusy = false
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
                    s.amountText.isBlank() -> "Enter an amount to continue"
                    amountGrain == null -> "Enter a valid amount to continue"
                    s.address.isBlank() -> "Enter a recipient to continue"
                    addrInvalid != null -> "Fix the recipient address to continue"
                    s.feeRatePerKb == null -> if (s.feeError != null) "Fee estimate unavailable" else "Waiting for fee estimates…"
                    else -> "Preparing the payment…"
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
        val cancel = { askPassword = false; authBusy = false }
        val submit = {
            if (authPassword.isNotEmpty() && !checking) {
                checking = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { vm.verifyPassword(authPassword) }
                    checking = false
                    if (ok) { askPassword = false; authBusy = false; vm.send(p) } else authError = "Incorrect password"
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!checking) cancel() },
            title = { Text("Wallet password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sends ${Amount.pretty(p.build.amount, 8)} ${snap.network.ticker} to ${s.contactName ?: Address.short(p.toAddress, 14, 10)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(authPassword, { authPassword = it; authError = null }, "Wallet password", imeAction = ImeAction.Done, onDone = { submit() })
                    authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = authPassword.isNotEmpty() && !checking, onClick = { submit() }) { Text("Send") } },
            dismissButton = { TextButton(enabled = !checking, onClick = cancel) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerSheet(contacts: List<Contact>, onDismiss: () -> Unit, onPick: (Contact) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Contacts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (contacts.isEmpty()) {
            EmptyState(Icons.Filled.Person, "No contacts yet", text = "Save an address from a transaction or in Settings › Contacts.")
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
private fun rememberAddrReason(address: String, network: Network): String? {
    val parsed = remember(address, network) { if (address.isBlank()) null else Address.parse(address, network) }
    return (parsed as? Address.Result.Invalid)?.reason
}
