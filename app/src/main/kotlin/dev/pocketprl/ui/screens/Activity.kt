package dev.pocketprl.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.components.AddressText
import dev.pocketprl.ui.components.AmountText
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.ContactAvatar
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.KeyValueRow
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.copyToClipboard
import dev.pocketprl.ui.components.dayLabel
import dev.pocketprl.ui.components.formatDateTime
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.TabularNumbers
import dev.pocketprl.ui.vm.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TxFilter(val label: String) { ALL("All"), IN("Received"), OUT("Sent"), MINED("Mined") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: WalletViewModel, onTx: (String) -> Unit, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val leavingApp = rememberLeaveAppMarker()
    var filter by rememberSaveable { mutableStateOf(TxFilter.ALL) }
    // Null until the first load so the empty state does not flash.
    val loaded by produceState<List<TxRow>?>(initialValue = null, snap.revision) {
        value = withContext(Dispatchers.IO) { vm.allTxs() }
    }
    val all = loaded
    val txs = remember(all, filter) {
        when (filter) {
            TxFilter.ALL -> all
            TxFilter.IN -> all?.filter { it.kind == TxKind.RECEIVED }
            TxFilter.OUT -> all?.filter { it.kind == TxKind.SENT || it.kind == TxKind.SELF }
            TxFilter.MINED -> all?.filter { it.kind == TxKind.MINED }
        }
    }
    ScreenScaffold(
        title = "Activity",
        onBack = onBack,
        actions = {
            if (!all.isNullOrEmpty()) IconButton(onClick = {
                scope.launch {
                    val intent = withContext(Dispatchers.IO) { runCatching { vm.exportCsvIntent(context) }.getOrNull() }
                    if (intent != null) runCatching { leavingApp(); context.startActivity(intent) }
                }
            }) { Icon(Icons.Filled.Share, contentDescription = "Export CSV") }
        },
    ) {
        val haptics = rememberHaptics()
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in TxFilter.entries) FilterChip(selected = filter == f, onClick = { if (filter != f) haptics.tick(); filter = f }, label = { Text(f.label) })
        }
        if (all == null || txs == null) { LoadingBlock("Loading history…"); return@ScreenScaffold }
        if (txs.isEmpty()) {
            EmptyState(AppIcons.History, if (all.isEmpty()) "No activity yet" else "Nothing here", text = if (all.isEmpty()) "Payments and mining rewards will show up as soon as the indexer sees them." else null)
            return@ScreenScaffold
        }
        // The pull indicator only shows for an actual pull, not for background polls.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(sync.syncing) { if (!sync.syncing) pulled = false }
        PullToRefreshBox(isRefreshing = pulled && sync.syncing, onRefresh = { pulled = true; vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            var lastDay: String? = null
            for (tx in txs) {
                val day = dayLabel(tx.time.takeIf { tx.height > 0 } ?: 0)
                if (day != lastDay) {
                    lastDay = day
                    // Keyed by the opening transaction, not the label: block timestamps can go
                    // backwards, so the same day label can head two groups.
                    item(key = "h:${tx.txid}") {
                        Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    }
                }
                item(key = tx.txid) {
                    TxListItem(tx, snap.network, sync.tipHeight, hide = settings.hideBalance, contactName = tx.counterparty?.let { snap.contactNames[it] }, note = snap.notes[tx.txid], onClick = { onTx(tx.txid) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Text("${txs.size} ${if (txs.size == 1) "transaction" else "transactions"}", modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
    }
}

@Composable
fun TxDetailScreen(vm: WalletViewModel, txid: String, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val price by vm.price.collectAsStateWithLifecycle()
    val secondsPerBlock by vm.blockSeconds.collectAsStateWithLifecycle()
    val tx = remember(snap.revision, txid) { vm.tx(txid) }
    val context = LocalContext.current
    val leavingApp = rememberLeaveAppMarker()
    val palette = PearlTheme.palette
    ScreenScaffold(title = "Transaction", onBack = onBack) {
        if (tx == null) { EmptyState(AppIcons.History, "Transaction not found"); return@ScreenScaffold }
        val conf = tx.confirmations(sync.tipHeight)
        val net = snap.network
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tx.kind.title(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A self transfer's net cost is the fee.
                val signed = when (tx.kind) { TxKind.RECEIVED, TxKind.MINED -> tx.amount; TxKind.SENT -> -tx.amount; TxKind.SELF -> -tx.fee }
                AmountText(signed, net, signed = true, hidden = settings.hideBalance, color = if (signed > 0) palette.success else MaterialTheme.colorScheme.onBackground)
                if (tx.kind == TxKind.SELF) Text("Sent to yourself • network fee only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.showFiat && !settings.hideBalance && net.isMainnet && tx.kind != TxKind.SELF) {
                    Amount.fiat(tx.amount, price.usdPerPrl)?.let { Text("≈ $it today", style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        conf == 0 -> "Pending in mempool"
                        tx.kind == TxKind.MINED && conf < Network.COINBASE_MATURITY -> "$conf of ${Network.COINBASE_MATURITY} confirmations to spend"
                        conf < 6 -> "$conf ${if (conf == 1) "confirmation" else "confirmations"}"
                        else -> "Confirmed • ${Amount.group(conf.toLong())} confirmations"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (conf == 0) palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tx.kind == TxKind.MINED && conf in 1 until Network.COINBASE_MATURITY) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { conf / Network.COINBASE_MATURITY.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = palette.accent, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Spendable in ${Network.COINBASE_MATURITY - conf} blocks (${Network.etaForBlocks(Network.COINBASE_MATURITY - conf, secondsPerBlock)})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            NoteCard(note = snap.notes[tx.txid], onSave = { vm.setNote(tx.txid, it) })

            SectionCard {
                KeyValueRow("Date", formatDateTime(tx.time))
                if (tx.height > 0) KeyValueRow("Block", Amount.group(tx.height))
                if (tx.kind != TxKind.MINED) KeyValueRow("Fee", "${Amount.pretty(tx.fee, 8)} ${net.ticker}" + if (tx.vsize > 0) " (${Amount.formatGrainPerVbyte(tx.fee * 1000 / tx.vsize)} grain/vB)" else "")
                if (tx.kind == TxKind.SENT) KeyValueRow("Total out", "${Amount.pretty(tx.amount + tx.fee, 8)} ${net.ticker}")
                KeyValueRow("Inputs / outputs", "${tx.nIn} / ${tx.nOut}")
                if (tx.vsize > 0) KeyValueRow("Size", "${tx.vsize} vB")
            }

            tx.counterparty?.let { cp ->
                val name = snap.contactNames[cp]
                var editing by remember(cp) { mutableStateOf(false) }
                SectionCard {
                    Text(if (tx.kind == TxKind.SENT) "To" else "From", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    if (name != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(name, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editing = true }) { Text("Edit") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    AddressLine(cp) { copyToClipboard(context, "Address", cp) }
                    if (name == null) TextButton(onClick = { editing = true }) { Text("Save as contact") }
                }
                if (editing) ContactDialog(address = cp, initialName = name ?: "", onDismiss = { editing = false }, onSave = { vm.saveContact(cp, it); editing = false })
            }
            tx.ownAddress?.let { own ->
                SectionCard {
                    Text(if (tx.kind == TxKind.SENT || tx.kind == TxKind.SELF) "From your address" else "To your address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    AddressLine(own) { copyToClipboard(context, "Address", own) }
                }
            }
            SectionCard {
                Text("Transaction ID", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(tx.txid, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    val haptics = rememberHaptics()
                    IconButton(onClick = { haptics.confirm(); copyToClipboard(context, "Transaction ID", tx.txid) }) { Icon(AppIcons.Copy, contentDescription = "Copy") }
                }
            }
            SecondaryButton("View in explorer", icon = AppIcons.OpenInNew, onClick = { runCatching { leavingApp(); context.startActivity(Intent(Intent.ACTION_VIEW, vm.explorerTxUrl(tx.txid).toUri())) } })
            if (tx.kind == TxKind.MINED) InfoBanner("Block rewards become spendable after ${Network.COINBASE_MATURITY} confirmations, like on every Bitcoin-derived chain.", BannerKind.INFO)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NoteCard(note: String?, onSave: (String?) -> Unit) {
    var text by remember(note) { mutableStateOf(note ?: "") }
    val dirty = text.trim() != (note ?: "")
    SectionCard {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(140) }, label = { Text("Note") }, placeholder = { Text("What was this for?") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
            trailingIcon = { if (dirty) TextButton(onClick = { onSave(text) }) { Text("Save") } },
        )
        Text("Notes stay on this phone only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
}

@Composable
fun ContactDialog(address: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) "New contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                AddressText(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun AddressLine(text: String, onCopy: () -> Unit) {
    val haptics = rememberHaptics()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddressText(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { haptics.confirm(); onCopy() }) { Icon(AppIcons.Copy, contentDescription = "Copy") }
    }
}
