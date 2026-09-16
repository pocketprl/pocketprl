package dev.pocketprl.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketprl.core.chain.Network
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.theme.PearlMark
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.theme.AppIcons

/**
 * First screen on a fresh install, and the "add a wallet" entry point when
 * wallets already exist ([onBack] non-null).
 */
@Composable
fun WelcomeScreen(network: Network, onNetworkChange: (Network) -> Unit, onCreate: () -> Unit, onRestore: () -> Unit, onBack: (() -> Unit)? = null) {
    val addMode = onBack != null
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        if (onBack != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Add a wallet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (addMode) 8.dp else 36.dp))
            PearlMark(size = 92.dp)
            Spacer(Modifier.height(20.dp))
            if (addMode) {
                Text("Another wallet, fully separate", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Its own recovery phrase, password, history and contacts. Switch between wallets from the home screen title.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                InfoBanner("Only one wallet is unlocked at a time; switching locks the current one.", BannerKind.INFO)
            } else {
                Text("PocketPRL", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text("Pearl (PRL) in your pocket. Same keys as the desktop wallet, none of the weight.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Feature(AppIcons.Shield, "Self-custody", "Keys are created and stay on this phone, encrypted and biometric-gated.")
                Feature(AppIcons.Key, "Desktop-compatible", "Derives the exact same post-quantum Taproot addresses as oyster. Move between them freely.")
                Feature(AppIcons.History, "Instant overview", "Balances, history and mining rewards from the Pearl indexer. No chain download.")
                Feature(AppIcons.Wallet, "Built for a phone", "Payment links, contacts, notes, multiple wallets and “you got paid” notifications.")
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Network", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (n in Network.entries) {
                    FilterChip(selected = n == network, onClick = { onNetworkChange(n) }, label = { Text(n.displayName, maxLines = 1) })
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Create a new wallet", onCreate)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Restore from recovery phrase", onRestore)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
