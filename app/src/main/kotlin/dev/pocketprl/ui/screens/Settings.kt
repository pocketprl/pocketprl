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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.BuildConfig
import dev.pocketprl.Locales
import dev.pocketprl.R
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.core.format.DecimalSeparator
import dev.pocketprl.core.format.FiatCurrency
import dev.pocketprl.core.format.GroupingSeparator
import dev.pocketprl.core.wallet.AddressVariant
import dev.pocketprl.data.AccentTheme
import dev.pocketprl.data.PollMode
import dev.pocketprl.data.ThemeMode
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
import dev.pocketprl.ui.components.LanguageDialog
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.languageLabel
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
import dev.pocketprl.ui.components.SlideToConfirm
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
    onStats: () -> Unit,
    onAbout: () -> Unit,
    onAddWallet: () -> Unit,
    onErase: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var bioEnabled by remember { mutableStateOf(vm.biometricEnabled) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var notifyError by remember { mutableStateOf<String?>(null) }
    var priceAlertError by remember { mutableStateOf<String?>(null) }
    var priceAlertBlocked by remember { mutableStateOf(false) }
    var showPriceThreshold by remember { mutableStateOf(false) }
    var showAutoLock by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showAccent by remember { mutableStateOf(false) }
    var showDecimals by remember { mutableStateOf(false) }
    var showDecimalSep by remember { mutableStateOf(false) }
    var showGrouping by remember { mutableStateOf(false) }
    var showFiatCurrency by remember { mutableStateOf(false) }
    var showHero by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
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
            PermissionOutcome.DENIED -> { notifBlocked = false; notifyError = context.getString(R.string.settings_notif_off) }
            PermissionOutcome.DENIED_PERMANENTLY -> { notifBlocked = true; notifyError = context.getString(R.string.settings_notif_blocked) }
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

    val askPriceAlertNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { outcome ->
        when (outcome) {
            PermissionOutcome.GRANTED -> { priceAlertBlocked = false; vm.setPriceAlert(true) }
            PermissionOutcome.DENIED -> { priceAlertBlocked = false; priceAlertError = context.getString(R.string.settings_notif_off) }
            PermissionOutcome.DENIED_PERMANENTLY -> { priceAlertBlocked = true; priceAlertError = context.getString(R.string.settings_notif_blocked) }
        }
    }
    LifecycleResumeEffect(priceAlertBlocked) {
        if (priceAlertBlocked && Permissions.notificationsGranted(context)) {
            priceAlertBlocked = false
            priceAlertError = null
            vm.setPriceAlert(true)
        }
        onPauseOrDispose {}
    }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.settings_section_wallet))
            SectionCard {
                SettingRow(stringResource(R.string.settings_name), vm.walletName, onClick = { showRename = true }, icon = Icons.Filled.Edit)
                HorizontalDivider()
                SettingRow(stringResource(R.string.label_network), vm.network.displayName, icon = AppIcons.Globe)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_created), formatDateTime(vm.createdAt / 1000), icon = Icons.Filled.DateRange)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_stats), stringResource(R.string.settings_stats_sub), onClick = onStats, icon = AppIcons.History)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_wallets), if (wallets.wallets.size == 1) stringResource(R.string.settings_wallets_one) else stringResource(R.string.settings_wallets_n, wallets.wallets.size), onClick = { showSwitcher = true }, icon = AppIcons.Wallet)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_contacts), stringResource(R.string.settings_contacts_saved, snap.contactNames.size), onClick = onContacts, icon = Icons.Filled.Person)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_addresses), stringResource(R.string.settings_addresses_derived, snap.addressCount), onClick = onAddresses, icon = AppIcons.Key)
            }

            SectionTitle(stringResource(R.string.settings_section_language))
            SectionCard {
                SettingRow(stringResource(R.string.settings_language), languageLabel(settings.appLanguage), onClick = { showLanguage = true }, icon = AppIcons.Globe)
            }

            SectionTitle(stringResource(R.string.settings_section_appearance))
            SectionCard {
                SettingRow(stringResource(R.string.settings_theme), themeLabel(settings.themeMode), onClick = { showTheme = true }, icon = AppIcons.Palette)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_accent), accentLabel(settings.accentTheme), onClick = { showAccent = true }, icon = AppIcons.Palette)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_sub), icon = AppIcons.Palette) {
                    Switch(checked = settings.dynamicColor, onCheckedChange = { haptics.toggle(it); vm.setDynamicColor(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_reduced_motion), stringResource(R.string.settings_reduced_motion_sub), icon = AppIcons.Odometer) {
                    Switch(checked = settings.reducedMotion, onCheckedChange = { haptics.toggle(it); vm.setReducedMotion(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_odometer), stringResource(R.string.settings_odometer_sub), icon = AppIcons.Odometer) {
                    Switch(checked = settings.odometer, onCheckedChange = { haptics.toggle(it); vm.setOdometer(it) })
                }
                HorizontalDivider()
                SettingRow(
                    stringResource(R.string.settings_odometer_haptics),
                    if (settings.odometer) stringResource(R.string.settings_odometer_haptics_sub) else stringResource(R.string.settings_odometer_haptics_off),
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

            SectionTitle(stringResource(R.string.settings_section_numbers))
            SectionCard {
                SettingRow(stringResource(R.string.settings_show_fiat), icon = AppIcons.Dollar) {
                    Switch(checked = settings.showFiat, onCheckedChange = { haptics.toggle(it); vm.setShowFiat(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_fiat_currency), fiatLabel(settings.fiatCurrency), onClick = { showFiatCurrency = true }, icon = AppIcons.Dollar)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_decimals), stringResource(R.string.option_value, settings.decimals), onClick = { showDecimals = true }, icon = AppIcons.Odometer)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_decimal_sep), decimalSeparatorLabel(settings.decimalSeparator), onClick = { showDecimalSep = true }, icon = AppIcons.Odometer)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_grouping_sep), groupingLabel(settings.groupingSeparator), onClick = { showGrouping = true }, icon = AppIcons.Odometer)
            }

            SectionTitle(stringResource(R.string.settings_section_dashboard))
            SectionCard {
                SettingRow(stringResource(R.string.settings_hero), heroLabel(settings.heroSpendable), onClick = { showHero = true }, icon = AppIcons.Wallet)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_show_change), stringResource(R.string.settings_show_change_sub), icon = AppIcons.TrendingUp) {
                    Switch(checked = settings.showChange24h, onCheckedChange = { haptics.toggle(it); vm.setShowChange24h(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_show_mining), stringResource(R.string.settings_show_mining_sub), icon = AppIcons.Pickaxe) {
                    Switch(checked = settings.showMiningCard, onCheckedChange = { haptics.toggle(it); vm.setShowMiningCard(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_hide_balances), stringResource(R.string.settings_hide_balances_sub), icon = AppIcons.VisibilityOff) {
                    Switch(checked = settings.hideBalance, onCheckedChange = { haptics.toggle(it); vm.setHideBalance(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_start_hidden), stringResource(R.string.settings_start_hidden_sub), icon = AppIcons.VisibilityOff) {
                    Switch(checked = settings.startHidden, onCheckedChange = { haptics.toggle(it); vm.setStartHidden(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_poll_mode), pollLabel(settings.pollMode), onClick = { showPoll = true }, icon = AppIcons.Clock)
            }

            SectionTitle(stringResource(R.string.settings_section_security))
            SectionCard {
                SettingRow(stringResource(R.string.settings_change_password), onClick = onChangePassword, icon = Icons.Filled.Lock)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_biometric), if (bioAvailable) stringResource(R.string.settings_biometric_sub) else stringResource(R.string.settings_biometric_none), icon = AppIcons.Fingerprint) {
                    Switch(checked = bioEnabled, enabled = bioAvailable, onCheckedChange = { want ->
                        haptics.toggle(want)
                        bioError = null
                        if (!want) { vm.disableBiometric(); bioEnabled = false; return@Switch }
                        val act = activity ?: return@Switch
                        val cipher = vm.biometricEncryptCipher() ?: run { bioError = context.getString(R.string.settings_keystore_error); return@Switch }
                        scope.launch {
                            when (val r = Biometrics.authenticate(act, context.getString(R.string.settings_biometric_prompt), context.getString(R.string.app_name), cipher, negative = context.getString(R.string.action_cancel))) {
                                is Biometrics.Outcome.Success -> { val err = vm.enableBiometric(r.cipher); if (err == null) bioEnabled = true else bioError = err }
                                is Biometrics.Outcome.Error -> bioError = r.message
                                is Biometrics.Outcome.Cancelled -> Unit
                            }
                        }
                    })
                }
                bioError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_confirm_send), stringResource(R.string.settings_confirm_send_sub), icon = AppIcons.ShieldCheck) {
                    Switch(checked = settings.requireAuthToSend, onCheckedChange = { haptics.toggle(it); vm.setRequireAuthToSend(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_wipe_attempts), stringResource(R.string.settings_wipe_attempts_sub), icon = AppIcons.Shield) {
                    Switch(checked = settings.wipeAfterFailedAttempts, onCheckedChange = { haptics.toggle(it); vm.setWipeAfterFailedAttempts(it) })
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_auto_lock), autoLockLabel(settings.autoLockSeconds), onClick = { showAutoLock = true }, icon = AppIcons.Clock)
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_secure_all), stringResource(R.string.settings_secure_all_sub), icon = AppIcons.ShieldCheck) {
                    Switch(checked = settings.secureAllScreens, onCheckedChange = { haptics.toggle(it); vm.setSecureAllScreens(it) })
                }
            }

            SectionTitle(stringResource(R.string.settings_section_notifications))
            SectionCard {
                SettingRow(stringResource(R.string.settings_notify_incoming), stringResource(R.string.settings_notify_incoming_sub, stringResource(R.string.notif_period)), icon = Icons.Filled.Notifications) {
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
                        if (notifBlocked) TextButton(onClick = { leavingApp(); Permissions.openAppSettings(context) }) { Text(stringResource(R.string.settings_open_settings)) }
                    }
                }
                HorizontalDivider()
                SettingRow(stringResource(R.string.settings_price_alerts), stringResource(R.string.settings_price_alerts_sub), icon = AppIcons.TrendingUp) {
                    Switch(checked = settings.priceAlert, onCheckedChange = { want ->
                        haptics.toggle(want)
                        priceAlertError = null
                        if (!want) { vm.setPriceAlert(false); return@Switch }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askPriceAlertNotifications() else vm.setPriceAlert(true)
                    })
                }
                priceAlertError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (priceAlertBlocked) TextButton(onClick = { leavingApp(); Permissions.openAppSettings(context) }) { Text(stringResource(R.string.settings_open_settings)) }
                    }
                }
                HorizontalDivider()
                SettingRow(
                    stringResource(R.string.settings_alert_threshold),
                    stringResource(R.string.settings_alert_threshold_sub, priceAlertLabel(settings.priceAlertPercent)),
                    onClick = { showPriceThreshold = true },
                    icon = AppIcons.Dollar,
                    enabled = settings.priceAlert,
                )
            }

            SectionTitle(stringResource(R.string.settings_section_network))
            SectionCard {
                SettingRow(stringResource(R.string.settings_indexer), vm.blockbookUrl().substringAfter("://").trimEnd('/'), onClick = onNetwork, icon = AppIcons.Server)
            }

            SectionTitle(stringResource(R.string.settings_section_backup))
            SectionCard {
                SettingRow(stringResource(R.string.settings_reveal_seed), stringResource(R.string.settings_reveal_seed_sub), onClick = onRevealSeed, icon = AppIcons.Shield)
            }

            SectionTitle(stringResource(R.string.settings_section_about))
            SectionCard {
                SettingRow(stringResource(R.string.app_name), stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME), onClick = onAbout, icon = Icons.Filled.Info)
            }

            SectionTitle(stringResource(R.string.settings_section_danger), color = MaterialTheme.colorScheme.error)
            SecondaryButton(stringResource(R.string.settings_delete), danger = true, onClick = onErase)
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
            title = { Text(stringResource(R.string.settings_rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.renameWallet(name); showRename = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showAutoLock) {
        OptionDialog(
            title = stringResource(R.string.settings_auto_lock_title),
            options = AUTO_LOCK_OPTIONS,
            selected = settings.autoLockSeconds,
            label = { autoLockLabel(it) },
            onPick = { haptics.tick(); vm.setAutoLock(it); showAutoLock = false },
            onDismiss = { showAutoLock = false },
        )
    }

    if (showTheme) {
        OptionDialog(
            title = stringResource(R.string.settings_theme_title),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { themeLabel(it) },
            onPick = { haptics.tick(); vm.setThemeMode(it); showTheme = false },
            onDismiss = { showTheme = false },
        )
    }

    if (showLanguage) {
        LanguageDialog(
            current = settings.appLanguage,
            onPick = { tag ->
                haptics.tick()
                vm.setAppLanguage(tag)
                showLanguage = false
                activity?.let { Locales.applyNow(it, tag) }
            },
            onDismiss = { showLanguage = false },
        )
    }

    if (showAccent) {
        OptionDialog(
            title = stringResource(R.string.settings_accent_title),
            options = AccentTheme.entries,
            selected = settings.accentTheme,
            label = { accentLabel(it) },
            onPick = { haptics.tick(); vm.setAccentTheme(it); showAccent = false },
            onDismiss = { showAccent = false },
        )
    }

    if (showDecimals) {
        OptionDialog(
            title = stringResource(R.string.settings_decimals),
            options = DECIMAL_OPTIONS,
            selected = settings.decimals,
            label = { stringResource(R.string.option_value, it) },
            onPick = { haptics.tick(); vm.setDecimals(it); showDecimals = false },
            onDismiss = { showDecimals = false },
        )
    }

    if (showDecimalSep) {
        OptionDialog(
            title = stringResource(R.string.settings_decimal_sep),
            options = DecimalSeparator.entries,
            selected = settings.decimalSeparator,
            label = { decimalSeparatorLabel(it) },
            onPick = { haptics.tick(); vm.setDecimalSeparator(it); showDecimalSep = false },
            onDismiss = { showDecimalSep = false },
        )
    }

    if (showGrouping) {
        OptionDialog(
            title = stringResource(R.string.settings_grouping_sep),
            options = GroupingSeparator.entries,
            selected = settings.groupingSeparator,
            label = { groupingLabel(it) },
            onPick = { haptics.tick(); vm.setGroupingSeparator(it); showGrouping = false },
            onDismiss = { showGrouping = false },
        )
    }

    if (showFiatCurrency) {
        OptionDialog(
            title = stringResource(R.string.settings_fiat_currency),
            options = FiatCurrency.entries,
            selected = settings.fiatCurrency,
            label = { fiatLabel(it) },
            onPick = { haptics.tick(); vm.setFiatCurrency(it); showFiatCurrency = false },
            onDismiss = { showFiatCurrency = false },
        )
    }

    if (showHero) {
        OptionDialog(
            title = stringResource(R.string.settings_hero),
            options = HERO_OPTIONS,
            selected = settings.heroSpendable,
            label = { heroLabel(it) },
            onPick = { haptics.tick(); vm.setHeroSpendable(it); showHero = false },
            onDismiss = { showHero = false },
        )
    }

    if (showPoll) {
        OptionDialog(
            title = stringResource(R.string.settings_poll_mode),
            options = PollMode.entries,
            selected = settings.pollMode,
            label = { pollLabel(it) },
            onPick = { haptics.tick(); vm.setPollMode(it); showPoll = false },
            onDismiss = { showPoll = false },
        )
    }

    if (showPriceThreshold) {
        OptionDialog(
            title = stringResource(R.string.settings_threshold_title),
            options = PRICE_ALERT_OPTIONS,
            selected = settings.priceAlertPercent,
            intro = stringResource(R.string.settings_threshold_intro),
            label = { priceAlertLabel(it) },
            onPick = { haptics.tick(); vm.setPriceAlertPercent(it); showPriceThreshold = false },
            onDismiss = { showPriceThreshold = false },
        )
    }
}

private val AUTO_LOCK_OPTIONS = listOf(0, 60, 300, 900, 3600, -1)

private val DECIMAL_OPTIONS = listOf(2, 4, 6, 8)

private val HERO_OPTIONS = listOf(false, true)

/** Threshold choices for price alerts, in percent over 24 hours. */
private val PRICE_ALERT_OPTIONS = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 25.0)

@Composable
private fun autoLockLabel(sec: Int): String = when (sec) {
    0 -> stringResource(R.string.autolock_immediately)
    60 -> stringResource(R.string.autolock_1m)
    300 -> stringResource(R.string.autolock_5m)
    900 -> stringResource(R.string.autolock_15m)
    3600 -> stringResource(R.string.autolock_1h)
    -1 -> stringResource(R.string.autolock_never)
    else -> stringResource(R.string.duration_seconds, sec)
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AUTO -> stringResource(R.string.theme_auto)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun accentLabel(accent: AccentTheme): String = when (accent) {
    AccentTheme.PEARL -> stringResource(R.string.accent_pearl)
    AccentTheme.OCEAN -> stringResource(R.string.accent_ocean)
    AccentTheme.SUNSET -> stringResource(R.string.accent_sunset)
    AccentTheme.VIOLET -> stringResource(R.string.accent_violet)
    AccentTheme.FOREST -> stringResource(R.string.accent_forest)
    AccentTheme.ROSE -> stringResource(R.string.accent_rose)
    AccentTheme.MONO -> stringResource(R.string.accent_mono)
}

@Composable
private fun decimalSeparatorLabel(sep: DecimalSeparator): String = when (sep) {
    DecimalSeparator.PERIOD -> stringResource(R.string.decimal_period)
    DecimalSeparator.COMMA -> stringResource(R.string.decimal_comma)
}

@Composable
private fun groupingLabel(sep: GroupingSeparator): String = when (sep) {
    GroupingSeparator.NARROW_SPACE -> stringResource(R.string.grouping_narrow_space)
    GroupingSeparator.SPACE -> stringResource(R.string.grouping_space)
    GroupingSeparator.COMMA -> stringResource(R.string.grouping_comma)
    GroupingSeparator.PERIOD -> stringResource(R.string.grouping_period)
    GroupingSeparator.APOSTROPHE -> stringResource(R.string.grouping_apostrophe)
    GroupingSeparator.NONE -> stringResource(R.string.grouping_none)
}

@Composable
private fun fiatLabel(fiat: FiatCurrency): String = stringResource(R.string.fiat_label, fiat.code.uppercase(), fiat.symbol)

@Composable
private fun heroLabel(spendable: Boolean): String =
    stringResource(if (spendable) R.string.settings_hero_spendable else R.string.settings_hero_total)

@Composable
private fun pollLabel(mode: PollMode): String = when (mode) {
    PollMode.LIVE -> stringResource(R.string.settings_poll_live)
    PollMode.BALANCED -> stringResource(R.string.settings_poll_balanced)
    PollMode.BATTERY -> stringResource(R.string.settings_poll_battery)
}

/** "5%" / "2.5%": trims a trailing .0 so whole percentages read cleanly. */
@Composable
private fun priceAlertLabel(p: Double): String {
    val s = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
    return stringResource(R.string.settings_threshold_custom, s)
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    intro: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (intro != null) {
                    Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                for (o in options) DialogOptionRow(selected = o == selected, label = label(o)) { onPick(o) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DialogOptionRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
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
    ScreenScaffold(title = stringResource(R.string.settings_password_title), onBack = onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PasswordField(current, { current = it; error = null }, stringResource(R.string.settings_password_current))
            PasswordField(new, { new = it; error = null }, stringResource(R.string.settings_password_new))
            if (new.isNotEmpty()) PasswordStrength(new)
            PasswordField(confirm, { confirm = it; error = null }, stringResource(R.string.settings_password_confirm), imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != new)
            error?.let { InfoBanner(it, BannerKind.ERROR) }
            if (success) InfoBanner(stringResource(R.string.settings_password_updated), BannerKind.SUCCESS)
            val ready = current.isNotEmpty() && new.length >= 8 && new == confirm && new != current && passwordScore(new) >= 2
            SlideToConfirm(
                onComplete = {
                    if (!ready || busy) return@SlideToConfirm
                    busy = true; success = false
                    scope.launch {
                        val err = vm.changePassword(current, new)
                        busy = false
                        if (err == null) { success = true; current = ""; new = ""; confirm = "" } else error = err
                    }
                },
                label = stringResource(R.string.settings_password_slide),
                enabled = ready && !busy,
                held = busy,
                busy = busy,
                icon = Icons.Filled.Lock,
                notReady = stringResource(R.string.settings_password_not_ready),
                sending = stringResource(R.string.settings_password_updating),
                confirming = stringResource(R.string.settings_password_updating),
                slideHint = stringResource(R.string.settings_password_slide_hint),
            )
            InfoBanner(stringResource(R.string.settings_password_info), BannerKind.INFO)
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
    val context = LocalContext.current
    ScreenScaffold(title = stringResource(R.string.settings_seed_title), onBack = onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val m = material
            if (m == null) {
                InfoBanner(stringResource(R.string.settings_seed_warning), BannerKind.WARNING)
                PasswordField(password, { password = it; error = null }, stringResource(R.string.settings_seed_password), imeAction = ImeAction.Done)
                error?.let { InfoBanner(it, BannerKind.ERROR) }
                SlideToConfirm(
                    onComplete = {
                        if (password.isEmpty() || busy) return@SlideToConfirm
                        busy = true
                        scope.launch {
                            val err = vm.checkPassword(password)
                            busy = false
                            if (err == null) material = runCatching { vm.revealSeed() }.getOrElse { error = it.message; null } else error = err
                        }
                    },
                    label = stringResource(R.string.settings_seed_reveal),
                    enabled = password.isNotEmpty() && !busy,
                    held = busy,
                    busy = busy,
                    icon = AppIcons.Shield,
                    notReady = stringResource(R.string.settings_seed_not_ready),
                    sending = stringResource(R.string.settings_seed_revealing),
                    confirming = stringResource(R.string.settings_seed_revealing),
                    slideHint = stringResource(R.string.settings_seed_slide_hint),
                )
            } else {
                when (m) {
                    is SeedMaterial.Mnemonic -> SectionCard { WordGrid(m.words.split(' ')) }
                    is SeedMaterial.Hex -> SectionCard { Text(stringResource(R.string.settings_seed_hex), style = MaterialTheme.typography.labelLarge); MonoText(m.bytes.toHex()) }
                }
                InfoBanner(stringResource(R.string.settings_seed_written_hint), BannerKind.WARNING)
                PrimaryButton(stringResource(R.string.action_done), onClick = onBack)
            }
        }
    }
}

@Composable
fun NetworkSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf(vm.blockbookUrl()) }
    var saved by remember { mutableStateOf(false) }
    val valid = url.trim().startsWith("https://") && url.trim().length > 12
    ScreenScaffold(title = stringResource(R.string.indexer_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.indexer_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = url, onValueChange = { url = it; saved = false }, label = { Text(stringResource(R.string.indexer_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, isError = !valid)
            Text(stringResource(R.string.indexer_default, vm.defaultBlockbookUrl()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved) InfoBanner(stringResource(R.string.indexer_saved), BannerKind.SUCCESS)
            PrimaryButton(stringResource(R.string.action_save), enabled = valid, onClick = { vm.setBlockbookUrl(url); saved = true })
            SecondaryButton(stringResource(R.string.indexer_reset), onClick = { url = vm.defaultBlockbookUrl(); vm.setBlockbookUrl(null); saved = true })
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
        title = stringResource(R.string.settings_addresses_title),
        subtitle = if (sync.syncing) sync.progress ?: stringResource(R.string.settings_addresses_checking) else stringResource(R.string.settings_addresses_derived_sub, snap.addressCount),
        onBack = onBack,
        actions = { IconButton(onClick = { vm.rescan() }, enabled = !sync.syncing) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_addresses_rescan)) } },
    ) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(rows, key = { it.address }) { r ->
                val haptics = rememberHaptics()
                Column(modifier = Modifier.fillMaxWidth().clickable { haptics.confirm(); copyToClipboard(context, context.getString(R.string.clipboard_address), r.address) }.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val base = stringResource(if (r.branch == 0) R.string.settings_addresses_receive else R.string.settings_addresses_change, r.index + 1)
                        Text(if (r.variant == AddressVariant.PQ) stringResource(R.string.settings_addresses_pq, base) else base, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (r.used) Text(stringResource(R.string.settings_addresses_used), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MonoText(r.address, style = MaterialTheme.typography.bodySmall)
                    if (r.balance != 0L || r.unconfirmed != 0L) Text("${if (settings.hideBalance) HIDDEN else Amount.pretty(r.balance + r.unconfirmed)} ${vm.network.ticker}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Text(stringResource(R.string.settings_addresses_info, vm.network.coinType), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    ScreenScaffold(title = stringResource(R.string.settings_contacts_title), onBack = onBack, actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_contacts_add)) } }) {
        if (contacts.isEmpty()) {
            EmptyState(Icons.Filled.Person, stringResource(R.string.settings_contacts_empty), text = stringResource(R.string.settings_contacts_empty_body))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { PrimaryButton(stringResource(R.string.settings_contacts_add), onClick = { adding = true }) }
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
                        IconButton(onClick = { haptics.confirm(); copyToClipboard(context, c.name, c.address) }) { Icon(AppIcons.Copy, contentDescription = stringResource(R.string.settings_copy_address)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Text(stringResource(R.string.settings_contacts_info), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.settings_contacts_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40); error = null }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                    OutlinedTextField(value = address, onValueChange = { address = it; error = null }, label = { Text(stringResource(R.string.settings_contacts_address, vm.network.displayName)) }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = FieldShape, isError = error != null)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank() && address.isNotBlank(), onClick = { error = vm.saveContact(address.trim(), name); if (error == null) adding = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
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

    ScreenScaffold(title = stringResource(R.string.settings_about_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                KeyValueRow(stringResource(R.string.settings_about_version_label), BuildConfig.VERSION_NAME)
                KeyValueRow(stringResource(R.string.settings_about_network), vm.network.displayName)
                KeyValueRow(stringResource(R.string.settings_about_coin_type), vm.network.coinType.toString())
                KeyValueRow(stringResource(R.string.settings_about_addresses), stringResource(R.string.settings_about_addresses_value, vm.network.hrp))
                KeyValueRow(stringResource(R.string.settings_about_signing), stringResource(R.string.settings_about_signing_value))
                KeyValueRow(stringResource(R.string.settings_about_pq), stringResource(R.string.settings_about_pq_value))
            }
            Text(stringResource(R.string.settings_about_blurb), style = MaterialTheme.typography.bodyMedium)

            PrimaryButton(
                text = if (checking) stringResource(R.string.settings_about_checking) else stringResource(R.string.settings_about_check),
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
            if (upToDate) InfoBanner(stringResource(R.string.settings_about_latest, BuildConfig.VERSION_NAME), BannerKind.SUCCESS)
            if (failed) InfoBanner(stringResource(R.string.settings_about_failed), BannerKind.ERROR)

            SecondaryButton(stringResource(R.string.settings_about_source), onClick = { haptics.click(); open(UpdateChecker.REPO_URL) })

            Spacer(Modifier.height(6.dp))

            SecondaryButton(stringResource(R.string.settings_about_pearl), onClick = { open("https://pearlresearch.ai") })
            SecondaryButton(stringResource(R.string.settings_about_pearl_source), onClick = { open("https://github.com/pearl-research-labs/pearl") })
            SecondaryButton(stringResource(R.string.settings_about_explorer), icon = AppIcons.OpenInNew, onClick = { open(vm.explorerUrl()) })

            InfoBanner(stringResource(R.string.settings_about_warranty), BannerKind.INFO)
        }
    }

    pending?.let { release ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.settings_about_update_title)) },
            text = { Text(stringResource(R.string.settings_about_update_body, release.version, BuildConfig.VERSION_NAME)) },
            confirmButton = {
                TextButton(onClick = { pending = null; haptics.confirm(); open(release.htmlUrl) }) { Text(stringResource(R.string.settings_about_update)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.settings_about_later)) } },
        )
    }
}
