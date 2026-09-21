package dev.pocketprl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.WalletEntry
import dev.pocketprl.data.WalletSnapshot
import dev.pocketprl.ui.components.ContactAvatar
import dev.pocketprl.ui.components.HIDDEN
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.vm.appContainer
import kotlinx.coroutines.flow.MutableStateFlow

/** Bottom sheet listing every wallet on the device. Picking one locks the current wallet and opens the other. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSwitcherSheet(wallets: List<WalletEntry>, activeId: String?, onPick: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.switcher_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        for (w in wallets.sortedBy { it.createdAt }) {
            val active = w.id == activeId
            WalletRow(w, active) {
                if (!active) { haptics.click(); onPick(w.id) }
                onDismiss()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onAdd() }.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.switcher_add_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.switcher_add_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One wallet: name, network and its balance. The balance is read from that
 * wallet's own (public) database, so it shows while the wallet is still locked.
 */
@Composable
private fun WalletRow(w: WalletEntry, active: Boolean, onClick: () -> Unit) {
    val container = appContainer()
    val snapshotFlow = remember(w.id) { container.context(w.id)?.repository?.snapshot ?: MutableStateFlow(WalletSnapshot()) }
    val snap by snapshotFlow.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val net = Network.fromId(w.network)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(w.name)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(w.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (net.isMainnet) net.displayName else stringResource(R.string.switcher_test_coins, net.displayName),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (settings.hideBalance) HIDDEN else "${Amount.pretty(snap.balances.total)} ${net.ticker}",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (active) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.switcher_current), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
