package dev.pocketprl.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.BuildConfig
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.core.wallet.AddressVariant
import dev.pocketprl.data.ThemeMode
import dev.pocketprl.data.notify.PaymentCheckJob
import dev.pocketprl.data.update.ReleaseInfo
import dev.pocketprl.data.update.UpdateChecker
import dev.pocketprl.data.vault.SeedMaterial
import dev.pocketprl.ui.Biometrics
import dev.pocketprl.ui.PermissionOutcome
import dev.pocketprl.ui.Permissions
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.rememberPermissionRequest
import dev.pocketprl.ui.components.AddressText
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.ContactAvatar
import dev.pocketprl.ui.components.HIDDEN
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.KeyValueRow
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.PasswordField
import dev.pocketprl.ui.components.PasswordStrength
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.SecureWindow
import dev.pocketprl.ui.components.SettingRow
import dev.pocketprl.ui.components.copyToClipboard
import dev.pocketprl.ui.components.formatDateTime
import dev.pocketprl.ui.components.passwordScore
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onRevealSeed: () -> Unit,
    onNetwork: () -> Unit,
    onAddresses: () -> Unit,
    onContacts: () -> Unit,
    onAbout: () -> Unit,
    onAddWallet: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var bioEnabled by remember { mutableStateOf(vm.biometricEnabled) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var notifyError by remember { mutableStateOf<String?>(null) }
    var showAutoLock by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    val bioAvailable = activity != null && Biometrics.available(activity)

    var notifBlocked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val leavingApp = rememberLeaveAppMarker()
    val askNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { outcome ->
        when (outcome) {
            PermissionOutcome.GRANTED -> { notifBlocked = false; vm.setNotifyIncoming(true) }
            PermissionOutcome.DENIED -> { notifBlocked = false; notifyError = "Notifications are off, so this stays disabled." }
            PermissionOutcome.DENIED_PERMANENTLY -> { notifBlocked = true; notifyError = "Notifications are blocked for PocketPRL." }
        }
    }
    // Finish enabling the toggle if the user granted the permission in system settings and came back.
    LifecycleResumeEffect(notifBlocked) {
        if (notifBlocked && Permissions.notificationsGranted(context)) {
            notifBlocked = false
            notifyError = null
            vm.setNotifyIncoming(true)
        }
        onPauseOrDispose {}
    }

    ScreenScaffold(title = "Settings", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Wallet")
            SectionCard {
                SettingRow("Name", vm.walletName, onClick = { showRename = true }, icon = Icons.Filled.Edit)
                HorizontalDivider()
                SettingRow("Network", vm.network.displayName, icon = AppIcons.Globe)
                HorizontalDivider()
                SettingRow("Created", formatDateTime(vm.createdAt / 1000), icon = Icons.Filled.DateRange)
                HorizontalDivider()
                SettingRow("Wallets", if (wallets.wallets.size == 1) "Just this one" else "${wallets.wallets.size} on this device", onClick = { showSwitcher = true }, icon = AppIcons.Wallet)
                HorizontalDivider()
                SettingRow("Contacts", "${snap.contactNames.size} saved", onClick = onContacts, icon = Icons.Filled.Person)
                HorizontalDivider()
                SettingRow("Addresses", "${snap.addressCount} derived", onClick = onAddresses, icon = AppIcons.Key)
            }

            SectionTitle("Security")
            SectionCard {
                SettingRow("Change password", onClick = onChangePassword, icon = Icons.Filled.Lock)
                HorizontalDivider()
                SettingRow("Biometric unlock", if (bioAvailable) "Fingerprint or face" else "None enrolled", icon = AppIcons.Fingerprint) {
                    Switch(checked = bioEnabled, enabled = bioAvailable, onCheckedChange = { want ->
                        haptics.toggle(want)
                        bioError = null
                        if (!want) { vm.disableBiometric(); bioEnabled = false; return@Switch }
                        val act = activity ?: return@Switch
                        val cipher = vm.biometricEncryptCipher() ?: run { bioError = "Could not create a Keystore key"; return@Switch }
                        scope.launch {
                            when (val r = Biometrics.authenticate(act, "Enable biometric unlock", "PocketPRL", cipher, negative = "Cancel")) {
                                is Biometrics.Outcome.Success -> { val err = vm.enableBiometric(r.cipher); if (err == null) bioEnabled = true else bioError = err }
                                is Biometrics.Outcome.Error -> bioError = r.message
                                is Biometrics.Outcome.Cancelled -> Unit
                            }
                        }
                    })
                }
                bioError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                SettingRow("Confirm before sending", "Ask on every payment", icon = AppIcons.ShieldCheck) {
                    Switch(checked = settings.requireAuthToSend, onCheckedChange = { haptics.toggle(it); vm.setRequireAuthToSend(it) })
                }
                HorizontalDivider()
                SettingRow("Auto-lock", autoLockLabel(settings.autoLockSeconds), onClick = { showAutoLock = true }, icon = AppIcons.Clock)
                HorizontalDivider()
                SettingRow("Hide balances", "Mask every amount", icon = AppIcons.VisibilityOff) {
                    Switch(checked = settings.hideBalance, onCheckedChange = { haptics.toggle(it); vm.setHideBalance(it) })
                }
            }

            SectionTitle("Notifications")
            SectionCard {
                SettingRow("Incoming payments", "Checked ${PaymentCheckJob.PERIOD_LABEL}", icon = Icons.Filled.Notifications) {
                    Switch(checked = settings.notifyIncoming, onCheckedChange = { want ->
                        haptics.toggle(want)
                        notifyError = null
                        if (!want) { vm.setNotifyIncoming(false); return@Switch }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications() else vm.setNotifyIncoming(true)
                    })
                }
                notifyError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (notifBlocked) TextButton(onClick = { leavingApp(); Permissions.openAppSettings(context) }) { Text("Open settings") }
                    }
                }
            }

            SectionTitle("Display")
            SectionCard {
                SettingRow("Theme", themeLabel(settings.themeMode), onClick = { showTheme = true }, icon = AppIcons.Palette)
                HorizontalDivider()
                SettingRow("Show USD value", icon = AppIcons.Dollar) {
                    Switch(checked = settings.showFiat, onCheckedChange = { haptics.toggle(it); vm.setShowFiat(it) })
                }
                HorizontalDivider()
                SettingRow("Odometer animation", "Rolling digits on balances", icon = AppIcons.Odometer) {
                    Switch(checked = settings.odometer, onCheckedChange = { haptics.toggle(it); vm.setOdometer(it) })
                }
                HorizontalDivider()
                SettingRow(
                    "Odometer haptics",
                    if (settings.odometer) "Tick with each roll" else "No effect while animation is off",
                    icon = AppIcons.Haptics,
                    enabled = settings.odometer,
                ) {
                    Switch(
                        checked = settings.odometerHaptics,
                        enabled = settings.odometer,
                        onCheckedChange = { haptics.toggle(it); vm.setOdometerHaptics(it) },
                    )
                }
            }

            SectionTitle("Backup")
            SectionCard {
                SettingRow("Show recovery phrase", "Requires your password", onClick = onRevealSeed, icon = AppIcons.Shield)
            }

            SectionTitle("Network")
            SectionCard {
                SettingRow("Indexer (Blockbook)", vm.blockbookUrl().substringAfter("://").trimEnd('/'), onClick = onNetwork, icon = AppIcons.Server)
            }

            SectionTitle("About")
            SectionCard {
                SettingRow("PocketPRL", "Version ${BuildConfig.VERSION_NAME}", onClick = onAbout, icon = Icons.Filled.Info)
            }

            SectionTitle("Danger zone", color = MaterialTheme.colorScheme.error)
            SecondaryButton("Delete this wallet", danger = true, onClick = { showDelete = true })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSwitcher) {
        WalletSwitcherSheet(wallets = wallets.wallets, activeId = vm.walletId, onPick = { vm.switchWallet(it) }, onAdd = onAddWallet, onDismiss = { showSwitcher = false })
    }

    if (showRename) {
        var name by remember { mutableStateOf(vm.walletName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename wallet") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.renameWallet(name); showRename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showAutoLock) {
        AlertDialog(
            onDismissRequest = { showAutoLock = false },
            title = { Text("Auto-lock after") },
            text = {
                Column {
                    for ((sec, label) in AUTO_LOCK_OPTIONS) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); vm.setAutoLock(sec); showAutoLock = false }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.autoLockSeconds == sec, onClick = { haptics.tick(); vm.setAutoLock(sec); showAutoLock = false })
                            Spacer(Modifier.width(8.dp)); Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAutoLock = false }) { Text("Close") } },
        )
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Theme") },
            text = {
                Column {
                    for (mode in ThemeMode.entries) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); vm.setThemeMode(mode); showTheme = false }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.themeMode == mode, onClick = { haptics.tick(); vm.setThemeMode(mode); showTheme = false })
                            Spacer(Modifier.width(8.dp)); Text(themeLabel(mode))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Close") } },
        )
    }

    if (showDelete) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete “${vm.walletName}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This removes this wallet's encrypted keys, cached history, contacts and notes from this phone. Other wallets are not affected. Make sure you have the recovery phrase. Type DELETE to confirm.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                }
            },
            confirmButton = { TextButton(enabled = typed == "DELETE", onClick = { scope.launch { vm.deleteWallet(); showDelete = false } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

private val AUTO_LOCK_OPTIONS = listOf(0 to "Immediately", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes", 3600 to "1 hour", -1 to "Never")

private fun autoLockLabel(sec: Int) = AUTO_LOCK_OPTIONS.firstOrNull { it.first == sec }?.second ?: "$sec seconds"

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AUTO -> "Auto (system)"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
fun ChangePasswordScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScreenScaffold(title = "Change password", onBack = onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PasswordField(current, { current = it; error = null }, "Current password")
            PasswordField(new, { new = it; error = null }, "New password")
            if (new.isNotEmpty()) PasswordStrength(new)
            PasswordField(confirm, { confirm = it; error = null }, "Confirm new password", imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != new)
            error?.let { InfoBanner(it, BannerKind.ERROR) }
            if (success) InfoBanner("Password changed.", BannerKind.SUCCESS)
            PrimaryButton("Update password", loading = busy, enabled = current.isNotEmpty() && new.length >= 8 && new == confirm && new != current && passwordScore(new) >= 2, onClick = {
                busy = true; success = false
                scope.launch {
                    val err = vm.changePassword(current, new)
                    busy = false
                    if (err == null) { success = true; current = ""; new = ""; confirm = "" } else error = err
                }
            })
            InfoBanner("Changing the password re-encrypts the key on this device only. Your recovery phrase stays the same.", BannerKind.INFO)
        }
    }
}

@Composable
fun RevealSeedScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var material by remember { mutableStateOf<SeedMaterial?>(null) }
    val scope = rememberCoroutineScope()
    ScreenScaffold(title = "Recovery phrase", onBack = onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val m = material
            if (m == null) {
                InfoBanner("Never share your recovery phrase. PocketPRL staff or Pearl developers will never ask for it.", BannerKind.WARNING)
                PasswordField(password, { password = it; error = null }, "Wallet password", imeAction = ImeAction.Done)
                error?.let { InfoBanner(it, BannerKind.ERROR) }
                PrimaryButton("Reveal", loading = busy, enabled = password.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { vm.verifyPassword(password) }
                        busy = false
                        if (ok) material = runCatching { vm.revealSeed() }.getOrElse { error = it.message; null } else error = "Incorrect password"
                    }
                })
            } else {
                when (m) {
                    is SeedMaterial.Mnemonic -> SectionCard { WordGrid(m.words.split(' ')) }
                    is SeedMaterial.Hex -> SectionCard { Text("Hex seed", style = MaterialTheme.typography.labelLarge); MonoText(m.bytes.toHex()) }
                }
                InfoBanner("Write it down on paper. Screenshots are blocked on this screen.", BannerKind.WARNING)
                PrimaryButton("Done", onClick = onBack)
            }
        }
    }
}

@Composable
fun NetworkSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf(vm.blockbookUrl()) }
    var saved by remember { mutableStateOf(false) }
    val valid = url.trim().startsWith("https://") && url.trim().length > 12
    ScreenScaffold(title = "Indexer", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("PocketPRL reads balances, history and fee estimates from a Blockbook indexer and broadcasts through it. Keys never leave the phone, but the indexer learns which addresses you look up. Point it at your own instance if that matters to you.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = url, onValueChange = { url = it; saved = false }, label = { Text("Blockbook URL (https only)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, isError = !valid)
            Text("Default: ${vm.defaultBlockbookUrl()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved) InfoBanner("Saved. Pull to refresh on the home screen to resync.", BannerKind.SUCCESS)
            PrimaryButton("Save", enabled = valid, onClick = { vm.setBlockbookUrl(url); saved = true })
            SecondaryButton("Reset to default", onClick = { url = vm.defaultBlockbookUrl(); vm.setBlockbookUrl(null); saved = true })
        }
    }
}

@Composable
fun AddressesScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    // Every index has a plain row and an XMSS-committed twin; the twin is listed only once it has activity.
    val rows = remember(snap.revision) { vm.addresses().filter { it.variant == AddressVariant.PLAIN || it.used || it.balance != 0L || it.unconfirmed != 0L } }
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ScreenScaffold(
        title = "Addresses",
        subtitle = if (sync.syncing) sync.progress ?: "Checking…" else "${snap.addressCount} indices derived",
        onBack = onBack,
        actions = { IconButton(onClick = { vm.rescan() }, enabled = !sync.syncing) { Icon(Icons.Filled.Refresh, contentDescription = "Rescan every address") } },
    ) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(rows, key = { it.address }) { r ->
                val haptics = rememberHaptics()
                Column(modifier = Modifier.fillMaxWidth().clickable { haptics.confirm(); copyToClipboard(context, "Pearl address", r.address) }.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (r.branch == 0) "Receive" else "Change"} #${r.index + 1}" + if (r.variant == AddressVariant.PQ) " • post-quantum variant" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (r.used) Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MonoText(r.address, style = MaterialTheme.typography.bodySmall)
                    if (r.balance != 0L || r.unconfirmed != 0L) Text("${if (settings.hideBalance) HIDDEN else Amount.pretty(r.balance + r.unconfirmed)} ${vm.network.ticker}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Text("Tap an address to copy it. Derivation: m/86'/${vm.network.coinType}'/0'/branch/index, BIP-86 key path like the desktop wallet. Each index also has a post-quantum variant (XMSS tapleaf from purpose 222') that is watched and listed here once it has been used. The refresh button checks every address, both variants, right now; the home screen only checks the ones in use.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun ContactsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val contacts = remember(snap.revision) { vm.contacts() }
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(title = "Contacts", onBack = onBack, actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add contact") } }) {
        if (contacts.isEmpty()) {
            EmptyState(Icons.Filled.Person, "No contacts yet", text = "Save the addresses you pay often. Names show up in your activity and in the recipient field.")
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { PrimaryButton("Add contact", onClick = { adding = true }) }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                items(contacts, key = { it.address }) { c ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { editing = c.address }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(c.name)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(Address.short(c.address, 16, 10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val haptics = rememberHaptics()
                        IconButton(onClick = { haptics.confirm(); copyToClipboard(context, c.name, c.address) }) { Icon(AppIcons.Copy, contentDescription = "Copy address") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Text("Contacts are stored on this phone only and are not part of your recovery phrase.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40); error = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                    OutlinedTextField(value = address, onValueChange = { address = it; error = null }, label = { Text("${vm.network.displayName} address") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = FieldShape, isError = error != null)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank() && address.isNotBlank(), onClick = { error = vm.saveContact(address.trim(), name); if (error == null) adding = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    editing?.let { addr ->
        val current = contacts.firstOrNull { it.address == addr }
        if (current != null) ContactDialog(
            address = addr, initialName = current.name,
            onDismiss = { editing = null },
            onSave = { vm.saveContact(addr, it); editing = null },
            onDelete = { vm.deleteContact(addr); editing = null },
        )
    }
}

@Composable
fun AboutScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val leavingApp = rememberLeaveAppMarker()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    fun open(url: String) = runCatching { leavingApp(); context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }

    var checking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ReleaseInfo?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    ScreenScaffold(title = "About", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrimaryButton(
                text = if (checking) "Checking…" else "Check for updates",
                loading = checking,
                onClick = {
                    haptics.click()
                    upToDate = false
                    failed = false
                    checking = true
                    scope.launch {
                        val release = UpdateChecker.latest()
                        checking = false
                        when {
                            release == null -> failed = true
                            UpdateChecker.compare(release.version, BuildConfig.VERSION_NAME) > 0 -> { haptics.confirm(); pending = release }
                            else -> { haptics.tick(); upToDate = true }
                        }
                    }
                },
            )
            if (upToDate) InfoBanner("You are on the latest version (v${BuildConfig.VERSION_NAME}).", BannerKind.SUCCESS)
            if (failed) InfoBanner("Could not reach GitHub. Check your connection and try again.", BannerKind.ERROR)

            SecondaryButton("PocketPRL source code", onClick = { haptics.click(); open(UpdateChecker.REPO_URL) })

            Spacer(Modifier.height(6.dp))

            SecondaryButton("Pearl Research Labs", onClick = { open("https://pearlresearch.ai") })
            SecondaryButton("Pearl source code (GitHub)", onClick = { open("https://github.com/pearl-research-labs/pearl") })
            SecondaryButton("Block explorer", icon = AppIcons.OpenInNew, onClick = { open(vm.explorerUrl()) })

            SectionCard {
                KeyValueRow("Version", BuildConfig.VERSION_NAME)
                KeyValueRow("Network", vm.network.displayName)
                KeyValueRow("Coin type", vm.network.coinType.toString())
                KeyValueRow("Addresses", "Taproot (bech32m, ${vm.network.hrp}1p…)")
                KeyValueRow("Signing", "BIP-340 Schnorr key path, libsecp256k1")
                KeyValueRow("PQ commitment", "XMSS-SHAKE256_5_256 tapleaf")
            }
            Text("PocketPRL is an independent, open-source light wallet for the Pearl network. It derives the same addresses as the official desktop wallet (oyster) and is verified against it with test vectors generated from the Pearl source code.", style = MaterialTheme.typography.bodyMedium)
            InfoBanner("This software is provided as-is without warranty. Verify builds, keep your recovery phrase offline, and test with small amounts first.", BannerKind.INFO)
        }
    }

    pending?.let { release ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Update available") },
            text = { Text("Version ${release.version} is available. You are on v${BuildConfig.VERSION_NAME}.") },
            confirmButton = {
                TextButton(onClick = { pending = null; haptics.confirm(); open(release.htmlUrl) }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Later") } },
        )
    }
}
