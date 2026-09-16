package dev.pocketprl.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.db.AddressRow
import dev.pocketprl.ui.Qr
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.components.ActionButton
import dev.pocketprl.ui.components.AddressText
import dev.pocketprl.ui.components.AmountHero
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.QrCode
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.copyToClipboard
import dev.pocketprl.ui.components.encodeQr
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.sanitizeAmount
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.PearlColors
import dev.pocketprl.ui.theme.PearlMark
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.TabularNumbers
import dev.pocketprl.ui.vm.WalletViewModel
import kotlinx.coroutines.launch

@Composable
fun ReceiveScreen(vm: WalletViewModel, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val price by vm.price.collectAsStateWithLifecycle()
    var row by remember { mutableStateOf<AddressRow?>(null) }
    var pqRow by remember { mutableStateOf<AddressRow?>(null) }
    var showPq by remember { mutableStateOf(false) }
    // Not secrets, so saveable is fine here.
    var amountText by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var rotating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val leavingApp = rememberLeaveAppMarker()
    val haptics = rememberHaptics()
    LaunchedEffect(snap.receiveAddress?.address) {
        row = vm.receiveAddress()
        pqRow = row?.let { vm.pqVariant(it) }
    }
    // The plain BIP-86 address is the default; the XMSS-committed twin of the same index is shown on request.
    val shown = if (showPq) pqRow ?: row else row
    val amount = Amount.parse(amountText)
    val payload = shown?.let { Qr.paymentUri(it.address, amount, label.takeIf { l -> l.isNotBlank() }) }
    val isRequest = payload != null && payload != shown?.address
    val fiat = if (settings.showFiat && snap.network.isMainnet && amount != null) Amount.fiat(amount, price.usdPerPrl) else null

    ReceiveContent(
        network = snap.network,
        address = shown?.address,
        index = shown?.index ?: 0,
        showPq = showPq,
        pqAvailable = pqRow != null,
        payload = payload,
        isRequest = isRequest,
        amountText = amountText,
        onAmountChange = { amountText = it },
        amountError = if (amountText.isNotBlank() && amount == null) "Enter a valid amount" else null,
        fiatHint = fiat?.let { "≈ $it" },
        label = label,
        onLabelChange = { label = it.take(60) },
        rotating = rotating,
        onCopy = { shown?.let { haptics.confirm(); copyToClipboard(context, "Pearl address", it.address) } },
        onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, payload) }
            leavingApp()
            runCatching { context.startActivity(Intent.createChooser(send, if (isRequest) "Share payment request" else "Share address")) }
        },
        onRotate = {
            haptics.click()
            rotating = true
            scope.launch { row = vm.rotateReceiveAddress(); pqRow = row?.let { vm.pqVariant(it) }; rotating = false }
        },
        onTogglePq = { haptics.tick(); showPq = !showPq },
        onBack = onBack,
    )
}

/** Everything on the Receive screen below the view-model: plain state in, callbacks out. */
@Composable
fun ReceiveContent(
    network: Network,
    address: String?,
    index: Int,
    showPq: Boolean,
    pqAvailable: Boolean,
    payload: String?,
    isRequest: Boolean,
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountError: String?,
    fiatHint: String?,
    label: String,
    onLabelChange: (String) -> Unit,
    rotating: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onTogglePq: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = PearlTheme.palette.isDark
    val ticker = network.ticker
    ScreenScaffold(title = "Receive $ticker", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (address == null) { LoadingBlock("Preparing address…"); return@Column }
            // The address, Copy and Share do not depend on the QR encode succeeding.
            val matrix = remember(payload) { payload?.let { encodeQr(it) } }
            val plate = if (dark) cs.surfaceContainerHigh else Color.White
            val ink = if (dark) PearlColors.Pearl else PearlColors.Ink
            val glow = cs.primary
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 4.dp)) {
                Box(
                    modifier = Modifier.size(320.dp).drawBehind {
                        val r = size.minDimension / 2
                        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = if (dark) 0.28f else 0.20f), Color.Transparent), center = center, radius = r), radius = r, center = center)
                    },
                )
                Box(modifier = Modifier.size(264.dp).clip(RoundedCornerShape(28.dp)).background(plate).padding(18.dp), contentAlignment = Alignment.Center) {
                    if (matrix != null) {
                        QrCode(matrix, foreground = ink, background = plate, contentDescription = "Address QR code", logo = { PearlMark(size = 64.dp, color = ink, behind = plate) })
                    } else {
                        Text("The QR code could not be drawn. Copy or share the address instead.", style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center)
                    }
                }
            }
            if (isRequest) {
                Text(
                    buildString {
                        append("Requesting ")
                        val a = Amount.parse(amountText)
                        append(if (a != null && a > 0) "${Amount.pretty(a)} $ticker" else "any amount")
                        if (label.isNotBlank()) append(" for “${label.trim()}”")
                    },
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (showPq) "Address #${index + 1} • post-quantum variant" else "Address #${index + 1} • Taproot, same as the desktop wallet",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                AddressText(address, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Copy", AppIcons.Copy, onCopy, Modifier.weight(1f), outlined = true)
                ActionButton(if (isRequest) "Share request" else "Share", Icons.Filled.Share, onShare, Modifier.weight(1f))
            }
            SectionCard {
                SectionTitle("Request an amount", modifier = Modifier.padding(bottom = 6.dp))
                AmountHero(
                    text = amountText, onTextChange = onAmountChange, ticker = ticker, fiatMode = false,
                    secondary = fiatHint ?: "Optional", error = amountError, onSwap = null, compact = true,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label, onValueChange = onLabelChange, label = { Text("Label (optional)") }, placeholder = { Text("Invoice #42") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                )
                Text("With an amount or label, the code and the share link become a payment link that fills in the amount for the sender.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(enabled = !rotating, onClick = onRotate) { Text(if (rotating) "Generating…" else "Use a fresh address") }
                if (pqAvailable) TextButton(onClick = onTogglePq) { Text(if (showPq) "Show standard address" else "Show post-quantum variant") }
            }
            if (showPq) InfoBanner(
                "This variant commits an XMSS post-quantum key inside the address. The official desktop wallet does not derive it, so coins sent here are only visible in PocketPRL until Pearl's desktop wallet supports it. Use the standard address unless the payer specifically needs this one.",
                BannerKind.WARNING, title = "Not visible to the desktop wallet",
            )
            InfoBanner("Addresses can be reused, but a fresh one per payer keeps your history private. Incoming payments appear in Activity as soon as the sender broadcasts.", BannerKind.INFO)
            Text("Only send Pearl (${network.displayName}) to this address.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
    }
}
