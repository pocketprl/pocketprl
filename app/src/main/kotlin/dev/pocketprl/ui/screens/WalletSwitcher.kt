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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.WalletEntry
import dev.pocketprl.ui.components.ContactAvatar
import dev.pocketprl.ui.components.rememberHaptics

/** Bottom sheet listing every wallet on the device. Picking one locks the current wallet and opens the other. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSwitcherSheet(wallets: List<WalletEntry>, activeId: String?, onPick: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.switcher_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        for (w in wallets.sortedBy { it.createdAt }) {
            val net = Network.fromId(w.network)
            val active = w.id == activeId
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onDismiss(); if (!active) { haptics.click(); onPick(w.id) } }.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContactAvatar(w.name)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                    Text(if (net.isMainnet) net.displayName else stringResource(R.string.switcher_test_coins, net.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.switcher_current), tint = MaterialTheme.colorScheme.primary)
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
