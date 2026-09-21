package dev.pocketprl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.Balances
import dev.pocketprl.data.MiningStats
import dev.pocketprl.data.PriceState
import dev.pocketprl.data.SyncState
import dev.pocketprl.data.WalletSnapshot
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import dev.pocketprl.ui.components.ActionButton
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.OdometerText
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.rememberNowSeconds
import dev.pocketprl.ui.components.etaBlocks
import dev.pocketprl.ui.components.timeAgo
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.LocalReducedMotion
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.TabularNumbers
import dev.pocketprl.ui.components.UpdateDot
import dev.pocketprl.ui.vm.WalletViewModel
import dev.pocketprl.ui.vm.appContainer
import java.util.Locale
import kotlin.math.abs

@Composable
fun DashboardScreen(vm: WalletViewModel, onSend: () -> Unit, onReceive: () -> Unit, onActivity: () -> Unit, onTx: (String) -> Unit, onSettings: () -> Unit, onLock: () -> Unit, onAddWallet: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val price by vm.price.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val secondsPerBlock by vm.blockSeconds.collectAsStateWithLifecycle()
    val reduce = LocalReducedMotion.current
    var showSwitcher by remember { mutableStateOf(false) }

    if (showSwitcher) {
        WalletSwitcherSheet(wallets = wallets.wallets, activeId = vm.walletId, onPick = { vm.switchWallet(it) }, onAdd = onAddWallet, onDismiss = { showSwitcher = false })
    }

    DashboardContent(
        snap = snap, sync = sync, price = price, walletCount = wallets.wallets.size,
        hide = settings.hideBalance, showFiat = settings.showFiat, secondsPerBlock = secondsPerBlock,
        heroSpendable = settings.heroSpendable, showChange24h = settings.showChange24h, showMiningCard = settings.showMiningCard,
        odometer = settings.odometer && !reduce, odometerHaptics = settings.odometerHaptics && !reduce,
        onRefresh = vm::refresh, onToggleHide = vm::toggleHideBalance, onSwitchWallet = { showSwitcher = true },
        onSend = onSend, onReceive = onReceive, onActivity = onActivity, onTx = onTx, onSettings = onSettings, onLock = onLock,
    )
}

/** Everything on the dashboard below the view-model: plain state in, callbacks out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    snap: WalletSnapshot,
    sync: SyncState,
    price: PriceState,
    walletCount: Int,
    hide: Boolean,
    showFiat: Boolean,
    heroSpendable: Boolean,
    showChange24h: Boolean,
    showMiningCard: Boolean,
    odometer: Boolean,
    odometerHaptics: Boolean,
    onRefresh: () -> Unit,
    secondsPerBlock: Long = Network.TARGET_BLOCK_SECONDS,
    onToggleHide: () -> Unit,
    onSwitchWallet: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onActivity: () -> Unit,
    onTx: (String) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    val appName = stringResource(R.string.app_name)
    val container = appContainer()
    val updateAvailable by container.updateAvailable.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.checkForUpdate() }
    ScreenScaffold(
        title = snap.walletName.ifBlank { appName },
        subtitle = if (walletCount > 1) pluralStringResource(R.plurals.dash_wallets, walletCount, walletCount, snap.network.displayName) else null,
        onTitleClick = onSwitchWallet,
        actions = {
            Box {
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title)) }
                if (updateAvailable) UpdateDot(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp))
            }
            IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock)) }
        },
    ) {
        // The pull indicator only shows for an actual pull, not for background polls.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(sync.syncing) { if (!sync.syncing) pulled = false }
        PullToRefreshBox(isRefreshing = pulled && sync.syncing, onRefresh = { pulled = true; onRefresh() }, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!snap.network.isMainnet) InfoBanner(stringResource(R.string.dash_testnet_warning, snap.network.displayName), BannerKind.WARNING)
                val syncError = sync.error
                if (syncError != null && !sync.syncing) {
                    InfoBanner(syncError, BannerKind.ERROR, title = stringResource(R.string.dash_sync_failed), action = { TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) } })
                }

                // No USD for testnet coins. The market price is not a balance, so it stays when balances are hidden.
                val fiat = if (showFiat && !hide && snap.network.isMainnet) Amount.fiat(snap.balances.total, price.usdPerPrl) else null
                val quote = if (showFiat && snap.network.isMainnet) price else null
                val haptics = rememberHaptics()
                BalanceHero(snap.balances, snap.network, sync, hide = hide, fiat = fiat, quote = quote, heroSpendable = heroSpendable, showChange24h = showChange24h, odometer = odometer, odometerHaptics = odometerHaptics, onToggleHide = { haptics.toggle(!hide); onToggleHide() })

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(stringResource(R.string.action_send), AppIcons.SendArrow, onSend, Modifier.weight(1f), height = 56.dp)
                    ActionButton(stringResource(R.string.action_receive), AppIcons.ReceiveArrow, onReceive, Modifier.weight(1f), outlined = true, height = 56.dp)
                }

                if (showMiningCard) snap.mining?.let { MiningCard(it, snap.network, hide, secondsPerBlock) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.dash_activity))
                    if (snap.txCount > 0) TextButton(onClick = onActivity) { Text(stringResource(R.string.dash_view_all, snap.txCount)) }
                }
                if (snap.recentTxs.isEmpty()) {
                    SectionCard(padding = 0.dp) {
                        if (sync.syncing && sync.lastSyncAt == 0L) EmptyState(AppIcons.History, stringResource(R.string.dash_looking))
                        else EmptyState(
                            AppIcons.ReceiveArrow, stringResource(R.string.dash_no_tx), text = stringResource(R.string.dash_no_tx_body),
                            action = { TextButton(onClick = onReceive) { Text(stringResource(R.string.dash_show_address)) } },
                        )
                    }
                } else {
                    SectionCard(padding = 0.dp) {
                        snap.recentTxs.forEachIndexed { i, tx ->
                            TxListItem(
                                tx, snap.network, sync.tipHeight, hide = hide,
                                contactName = tx.counterparty?.let { snap.contactNames[it] }, note = snap.notes[tx.txid],
                                onClick = { onTx(tx.txid) }, modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            if (i < snap.recentTxs.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The balance sits straight on the page: no card, big type, the decimals and
 * ticker set smaller after it. Tap anywhere on it to hide or show the balance.
 */
@Composable
private fun BalanceHero(b: Balances, network: Network, sync: SyncState, hide: Boolean, fiat: String?, quote: PriceState?, heroSpendable: Boolean, showChange24h: Boolean, odometer: Boolean, odometerHaptics: Boolean, onToggleHide: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val palette = PearlTheme.palette
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggleHide, onClickLabel = stringResource(if (hide) R.string.action_show_balance else R.string.action_hide_balance))
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (heroSpendable) R.string.dash_spendable else R.string.dash_total_balance), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(if (hide) AppIcons.VisibilityOff else AppIcons.Visibility, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(6.dp))
        BigAmount(if (heroSpendable) b.spendable else b.total, network, hide = hide, spinning = sync.syncing, odometer = odometer, odometerHaptics = odometerHaptics)
        val usd = quote?.usdPerPrl
        if (fiat != null || usd != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fiat != null) OdometerText("≈ $fiat", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, spinning = sync.syncing, animate = odometer, haptics = odometerHaptics)
                if (usd != null) PriceLine(network, usd, quote.change24h, showChange24h)
            }
        }
        if (!hide && (b.pendingIncoming != 0L || b.immature != 0L)) {
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                MiniStat(stringResource(R.string.dash_spendable), Amount.pretty(b.spendable), cs.primary, Modifier.weight(1.2f))
                if (b.pendingIncoming != 0L) {
                    VerticalDivider(color = cs.outlineVariant, modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp))
                    MiniStat(stringResource(R.string.dash_incoming), Amount.pretty(b.pendingIncoming), palette.warning, Modifier.weight(1f))
                }
                if (b.immature != 0L) {
                    VerticalDivider(color = cs.outlineVariant, modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp))
                    MiniStat(stringResource(R.string.dash_maturing), Amount.pretty(b.immature), cs.secondary, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SyncLine(sync)
    }
}

/**
 * "12 638" in display type, then ".1789 PRL" smaller and quieter on the same
 * baseline. Steps down a size for a long whole part so it never leaves the screen.
 */
@Composable
private fun BigAmount(grain: Long, network: Network, hide: Boolean, spinning: Boolean, odometer: Boolean, odometerHaptics: Boolean) {
    val cs = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    val pretty = Amount.pretty(grain)
    val hiddenText = stringResource(R.string.hidden_placeholder)
    val hiddenDesc = stringResource(R.string.balance_hidden)
    val whole = if (hide) hiddenText else pretty.substringBefore('.')
    val frac = if (hide) "" else pretty.substringAfter('.', "")
    val tail = (if (frac.isEmpty()) "" else ".$frac") + " " + network.ticker
    val chars = whole.length + tail.length
    // (big style, small style, bottom padding on the small text so the baselines meet)
    val (big, small, lift) = when {
        hide -> Triple(t.displaySmall.copy(letterSpacing = 2.sp), t.titleLarge, 4.dp)
        chars <= 15 -> Triple(t.displayLarge.copy(letterSpacing = (-2.5).sp), t.headlineMedium, 8.dp)
        chars <= 18 -> Triple(t.displayMedium.copy(letterSpacing = (-1.8).sp), t.headlineSmall, 5.dp)
        else -> Triple(t.displaySmall.copy(letterSpacing = (-1).sp), t.titleLarge, 4.dp)
    }
    val bigStyle = big.merge(TabularNumbers).copy(fontWeight = FontWeight.ExtraBold)
    val smallStyle = small.merge(TabularNumbers).copy(fontWeight = FontWeight.SemiBold)
    // Read to a screen reader as one number, not digit by digit.
    val m = Modifier.semantics(mergeDescendants = true) { contentDescription = if (hide) hiddenDesc else "$pretty ${network.ticker}" }
    Row(modifier = m, verticalAlignment = Alignment.Bottom) {
        if (hide) Text(whole, style = bigStyle, color = cs.onBackground, maxLines = 1, softWrap = false)
        else OdometerText(whole, style = bigStyle, color = cs.onBackground, spinning = spinning, animate = odometer, haptics = odometerHaptics)
        OdometerText(tail, style = smallStyle, color = cs.onSurfaceVariant, spinning = spinning, animate = odometer, haptics = odometerHaptics, firstRank = whole.count { it.isDigit() }, modifier = Modifier.padding(bottom = lift))
    }
}

@Composable
private fun MiniStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers).copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "▲ 3.2%  PRL $0.5601": the day's move as a small chip, then the market price.
 * Informational, so it is never rolled and never hidden with the balances.
 */
@Composable
private fun PriceLine(network: Network, usdPerPrl: Double, change24h: Double?, showChange: Boolean) {
    val cs = MaterialTheme.colorScheme
    val palette = PearlTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showChange && change24h != null) {
            // Anything that rounds to zero shows as "0.0%", never "-0.0%".
            val flat = change24h > -0.05 && change24h < 0.05
            val (fg, bg) = when {
                flat -> cs.onSurfaceVariant to cs.surfaceVariant
                change24h > 0 -> palette.success to palette.successContainer
                else -> cs.error to cs.errorContainer
            }
            val arrow = when { flat -> "–"; change24h > 0 -> "▲"; else -> "▼" }
            val pct = "%.1f".format(Locale.US, abs(change24h))
            val desc = stringResource(R.string.dash_price_content_desc, arrow, pct)
            Text(
                "$arrow $pct%",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers).copy(fontWeight = FontWeight.Bold), color = fg,
                modifier = Modifier.background(bg, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp).semantics { contentDescription = desc },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${network.ticker} ${Amount.usdPrice(usdPerPrl)}", style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SyncLine(s: SyncState) {
    val now = rememberNowSeconds()
    val text = when {
        s.syncing -> s.progress ?: stringResource(R.string.dash_sync_syncing)
        s.error != null -> if (s.tipHeight > 0) stringResource(R.string.dash_sync_last, timeAgo(s.lastSyncAt / 1000, now), Amount.group(s.tipHeight)) else stringResource(R.string.dash_sync_not_synced)
        s.tipHeight > 0 -> stringResource(R.string.dash_sync_block, Amount.group(s.tipHeight), timeAgo(s.lastSyncAt / 1000, now))
        else -> stringResource(R.string.dash_sync_not_synced)
    }
    val (dot, state) = when {
        s.syncing -> MaterialTheme.colorScheme.primary to stringResource(R.string.dash_sync_syncing)
        s.error != null -> MaterialTheme.colorScheme.error to stringResource(R.string.dash_sync_failed)
        else -> PearlTheme.palette.success to stringResource(R.string.dash_sync_synced)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$state. $text" }) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiningCard(m: MiningStats, network: Network, hide: Boolean, secondsPerBlock: Long) {
    val accent = PearlTheme.palette.accent
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(32.dp).background(accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Pickaxe, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.dash_mining_rewards), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Stat(stringResource(R.string.dash_last_7_days), if (hide) stringResource(R.string.hidden_placeholder) else Amount.pretty(m.last7Days), pluralStringResource(R.plurals.dash_blocks, m.blocksLast7Days, m.blocksLast7Days), Modifier.weight(1f))
            Stat(stringResource(R.string.dash_all_time), if (hide) stringResource(R.string.hidden_placeholder) else Amount.pretty(m.totalMined), pluralStringResource(R.plurals.dash_blocks, m.blocks, m.blocks), Modifier.weight(1f))
        }
        if (m.immature > 0 && m.nextMatureIn != null) {
            Spacer(Modifier.height(12.dp))
            val progress = 1f - m.nextMatureIn / Network.COINBASE_MATURITY.toFloat()
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), color = accent, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(6.dp))
            val progressText = if (hide) stringResource(R.string.dash_mining_rewards_maturing)
                else stringResource(R.string.dash_mining_progress, "${Amount.pretty(m.immature)} ${network.ticker}", m.nextMatureIn, etaBlocks(m.nextMatureIn, secondsPerBlock))
            Text(
                progressText,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TxKind.title(): String = stringResource(when (this) {
    TxKind.RECEIVED -> R.string.tx_received
    TxKind.SENT -> R.string.tx_sent
    TxKind.SELF -> R.string.tx_self
    TxKind.MINED -> R.string.tx_mined
})

@Composable
fun TxListItem(
    tx: TxRow,
    network: Network,
    tip: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hide: Boolean = false,
    contactName: String? = null,
    note: String? = null,
) {
    val palette = PearlTheme.palette
    val conf = tx.confirmations(tip)
    val (icon, tint) = when (tx.kind) {
        TxKind.RECEIVED -> AppIcons.ReceiveArrow to palette.success
        TxKind.SENT -> AppIcons.SendArrow to MaterialTheme.colorScheme.onSurfaceVariant
        TxKind.SELF -> AppIcons.Swap to MaterialTheme.colorScheme.onSurfaceVariant
        TxKind.MINED -> AppIcons.Pickaxe to palette.accent
    }
    val title = when {
        contactName != null && tx.kind == TxKind.SENT -> stringResource(R.string.tx_to_contact, contactName)
        contactName != null && tx.kind == TxKind.RECEIVED -> stringResource(R.string.tx_from_contact, contactName)
        else -> tx.kind.title()
    }
    val now = rememberNowSeconds()
    val seen = if (tx.firstSeen > 0) timeAgo(tx.firstSeen, now) else null
    val status = if (conf == 0) {
        if (seen != null) stringResource(R.string.tx_pending_seen, seen) else stringResource(R.string.tx_pending)
    } else stringResource(R.string.tx_conf, timeAgo(tx.time, now), conf)
    Row(modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(40.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (note != null) "$note • $status" else status,
                style = MaterialTheme.typography.bodySmall, color = if (conf == 0) palette.warning else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val signed = tx.netAmount
        val color: Color = if (signed > 0) palette.success else MaterialTheme.colorScheme.onSurface
        Text(
            if (hide) "${stringResource(R.string.hidden_placeholder)} ${network.ticker}" else (if (signed > 0) "+" else "") + Amount.pretty(signed) + " " + network.ticker,
            style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers), fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        )
    }
}
