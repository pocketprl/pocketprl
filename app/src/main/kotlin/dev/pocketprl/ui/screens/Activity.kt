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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
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
import dev.pocketprl.ui.components.etaBlocks
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.TxidText
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TxFilter { ALL, IN, OUT, MINED }

@Composable
private fun TxFilter.label(): String = stringResource(when (this) {
    TxFilter.ALL -> R.string.activity_filter_all
    TxFilter.IN -> R.string.activity_filter_received
    TxFilter.OUT -> R.string.activity_filter_sent
    TxFilter.MINED -> R.string.activity_filter_mined
})

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
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Null until the first load so the empty state does not flash.
    val loaded by produceState<List<TxRow>?>(initialValue = null, snap.revision) {
        value = withContext(Dispatchers.IO) { vm.allTxs() }
    }
    val all = loaded
    val q = query.trim().lowercase()
    val txs = remember(all, filter, q, snap.revision) {
        val base = when (filter) {
            TxFilter.ALL -> all
            TxFilter.IN -> all?.filter { it.kind == TxKind.RECEIVED }
            TxFilter.OUT -> all?.filter { it.kind == TxKind.SENT || it.kind == TxKind.SELF }
            TxFilter.MINED -> all?.filter { it.kind == TxKind.MINED }
        } ?: return@remember null
        if (q.isEmpty()) base else base.filter { tx ->
            tx.txid.lowercase().contains(q) ||
                tx.counterparty?.lowercase()?.contains(q) == true ||
                tx.ownAddress?.lowercase()?.contains(q) == true ||
                tx.counterparty?.let { snap.contactNames[it]?.lowercase()?.contains(q) } == true ||
                snap.notes[tx.txid]?.lowercase()?.contains(q) == true
        }
    }
    ScreenScaffold(
        title = stringResource(R.string.activity_title),
        onBack = onBack,
        actions = {
            if (!all.isNullOrEmpty()) IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) query = "" // closing search clears it
            }) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = stringResource(R.string.activity_search)) }
            if (!all.isNullOrEmpty()) IconButton(onClick = {
                scope.launch {
                    val intent = withContext(Dispatchers.IO) { runCatching { vm.exportCsvIntent(context) }.getOrNull() }
                    if (intent != null) runCatching { leavingApp(); context.startActivity(intent) }
                }
            }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.activity_export)) }
        },
    ) {
        val haptics = rememberHaptics()
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.activity_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.activity_search_clear)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                shape = FieldShape,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in TxFilter.entries) FilterChip(selected = filter == f, onClick = { if (filter != f) haptics.tick(); filter = f }, label = { Text(f.label()) })
        }
        if (all == null || txs == null) { LoadingBlock(stringResource(R.string.activity_loading)); return@ScreenScaffold }
        if (txs.isEmpty()) {
            val searching = q.isNotEmpty()
            EmptyState(
                AppIcons.History,
                if (all.isEmpty()) stringResource(R.string.activity_none) else stringResource(R.string.activity_nothing),
                text = when {
                    searching -> stringResource(R.string.activity_search_no_match, query.trim())
                    all.isEmpty() -> stringResource(R.string.activity_none_body)
                    else -> null
                },
            )
            return@ScreenScaffold
        }
        // The pull indicator only shows for an actual pull, not for background polls.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(sync.syncing) { if (!sync.syncing) pulled = false }
        PullToRefreshBox(isRefreshing = pulled && sync.syncing, onRefresh = { pulled = true; vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            var lastDay: LocalDate? = null
            for (tx in txs) {
                val labelEpoch = tx.time.takeIf { tx.height > 0 } ?: 0
                val day = if (labelEpoch > 0) Instant.ofEpochSecond(labelEpoch).atZone(ZoneId.systemDefault()).toLocalDate() else null
                if (day != lastDay) {
                    lastDay = day
                    // Keyed by the opening transaction, not the label: block timestamps can go
                    // backwards, so the same day label can head two groups.
                    item(key = "h:${tx.txid}") {
                        Text(dayLabel(labelEpoch), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    }
                }
                item(key = tx.txid) {
                    TxListItem(tx, snap.network, sync.tipHeight, hide = settings.hideBalance, contactName = tx.counterparty?.let { snap.contactNames[it] }, note = snap.notes[tx.txid], onClick = { onTx(tx.txid) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Text(pluralStringResource(R.plurals.activity_tx_count, txs.size, txs.size), modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    ScreenScaffold(title = stringResource(R.string.activity_title_single), onBack = onBack) {
        if (tx == null) { EmptyState(AppIcons.History, stringResource(R.string.activity_not_found)); return@ScreenScaffold }
        val conf = tx.confirmations(sync.tipHeight)
        val net = snap.network
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tx.kind.title(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A self transfer's net cost is the fee.
                val signed = when (tx.kind) { TxKind.RECEIVED, TxKind.MINED -> tx.amount; TxKind.SENT -> -tx.amount; TxKind.SELF -> -tx.fee }
                AmountText(signed, net, signed = true, hidden = settings.hideBalance, color = if (signed > 0) palette.success else MaterialTheme.colorScheme.onBackground)
                if (tx.kind == TxKind.SELF) Text(stringResource(R.string.activity_self), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.showFiat && !settings.hideBalance && net.isMainnet && tx.kind != TxKind.SELF) {
                    Amount.fiat(tx.amount, price.usdPerPrl)?.let { Text(stringResource(R.string.activity_fiat_today, it), style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        conf == 0 -> stringResource(R.string.activity_pending_mempool)
                        tx.kind == TxKind.MINED && conf < Network.COINBASE_MATURITY -> stringResource(R.string.activity_conf_of_maturity, conf, Network.COINBASE_MATURITY)
                        conf < 6 -> pluralStringResource(R.plurals.activity_confirmations, conf, conf)
                        else -> stringResource(R.string.activity_confirmed, Amount.group(conf.toLong()))
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (conf == 0) palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tx.kind == TxKind.MINED && conf in 1 until Network.COINBASE_MATURITY) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { conf / Network.COINBASE_MATURITY.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = palette.accent, trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.activity_spendable_in, Network.COINBASE_MATURITY - conf, etaBlocks(Network.COINBASE_MATURITY - conf, secondsPerBlock)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            NoteCard(note = snap.notes[tx.txid], onSave = { vm.setNote(tx.txid, it) })

            SectionCard {
                KeyValueRow(stringResource(R.string.label_date), formatDateTime(tx.time))
                if (tx.height > 0) KeyValueRow(stringResource(R.string.label_block), Amount.group(tx.height))
                if (tx.kind != TxKind.MINED) {
                    val feeValue = "${Amount.pretty(tx.fee, 8)} ${net.ticker}"
                    KeyValueRow(stringResource(R.string.label_fee), if (tx.vsize > 0) stringResource(R.string.activity_fee_vsize, feeValue, Amount.formatGrainPerVbyte(tx.fee * 1000 / tx.vsize)) else feeValue)
                }
                if (tx.kind == TxKind.SENT) KeyValueRow(stringResource(R.string.activity_total_out), "${Amount.pretty(tx.amount + tx.fee, 8)} ${net.ticker}")
                KeyValueRow(stringResource(R.string.activity_inputs_outputs), stringResource(R.string.activity_inouts, tx.nIn, tx.nOut))
                if (tx.vsize > 0) KeyValueRow(stringResource(R.string.activity_size_label), stringResource(R.string.activity_size, tx.vsize))
            }

            tx.counterparty?.let { cp ->
                val name = snap.contactNames[cp]
                var editing by remember(cp) { mutableStateOf(false) }
                SectionCard {
                    Text(if (tx.kind == TxKind.SENT) stringResource(R.string.activity_to) else stringResource(R.string.activity_from), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    if (name != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(name, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_edit)) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    val addressLabel = stringResource(R.string.label_address)
                    AddressLine(cp) { copyToClipboard(context, addressLabel, cp) }
                    if (name == null) TextButton(onClick = { editing = true }) { Text(stringResource(R.string.activity_save_contact)) }
                }
                if (editing) ContactDialog(address = cp, initialName = name ?: "", onDismiss = { editing = false }, onSave = { vm.saveContact(cp, it); editing = false })
            }
            tx.ownAddress?.let { own ->
                SectionCard {
                    Text(if (tx.kind == TxKind.SENT || tx.kind == TxKind.SELF) stringResource(R.string.activity_from_your) else stringResource(R.string.activity_to_your), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    val addressLabel = stringResource(R.string.label_address)
                    AddressLine(own) { copyToClipboard(context, addressLabel, own) }
                }
            }
            SectionCard {
                Text(stringResource(R.string.activity_txid), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TxidText(tx.txid, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    val haptics = rememberHaptics()
                    val txidLabel = stringResource(R.string.clipboard_txid)
                    IconButton(onClick = { haptics.confirm(); copyToClipboard(context, txidLabel, tx.txid) }) { Icon(AppIcons.Copy, contentDescription = stringResource(R.string.action_copy)) }
                }
            }
            SecondaryButton(stringResource(R.string.activity_view_explorer), icon = AppIcons.OpenInNew, onClick = { runCatching { leavingApp(); context.startActivity(Intent(Intent.ACTION_VIEW, vm.explorerTxUrl(tx.txid).toUri())) } })
            if (tx.kind == TxKind.MINED) InfoBanner(stringResource(R.string.activity_maturity_info, Network.COINBASE_MATURITY), BannerKind.INFO)
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
            value = text, onValueChange = { text = it.take(140) }, label = { Text(stringResource(R.string.activity_note)) }, placeholder = { Text(stringResource(R.string.activity_note_placeholder)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
            trailingIcon = { if (dirty) TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        )
        Text(stringResource(R.string.activity_note_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
}

@Composable
fun ContactDialog(address: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) stringResource(R.string.activity_contact_new) else stringResource(R.string.activity_contact_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                AddressText(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
fun AddressLine(text: String, onCopy: () -> Unit) {
    val haptics = rememberHaptics()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddressText(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { haptics.confirm(); onCopy() }) { Icon(AppIcons.Copy, contentDescription = stringResource(R.string.action_copy)) }
    }
}
