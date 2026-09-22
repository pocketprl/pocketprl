package dev.pocketprl.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.Locales
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.format.DecimalSeparator
import dev.pocketprl.core.format.FiatCurrency
import dev.pocketprl.core.format.GroupingSeparator
import dev.pocketprl.data.AccentTheme
import dev.pocketprl.data.ThemeMode
import dev.pocketprl.ui.Biometrics
import dev.pocketprl.ui.components.OdometerText
import dev.pocketprl.ui.components.LanguageDialog
import dev.pocketprl.ui.components.languageLabel
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SettingRow
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.accentPrimary
import dev.pocketprl.ui.theme.isDarkTheme
import dev.pocketprl.ui.vm.appContainer

/** A sample balance used only to preview the number formatting live. */
private const val DEMO_GRAIN = 1_263_800_000_000L
private const val DEMO_PRICE = 0.55

/** Gap between sections, so the list breathes instead of running together. */
private val SectionGap = 20.dp

/**
 * First-run personalization: theme, accent, number format, currency and motion,
 * with a live preview. Every change writes straight to [dev.pocketprl.data.Settings],
 * so the whole app (including this screen) re-themes and re-formats as you tap.
 */
@Composable
fun PersonalizeScreen(next: String, onCreate: () -> Unit, onRestore: () -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val activity = LocalActivity.current
    var showLanguage by remember { mutableStateOf(false) }
    val dark = isDarkTheme(settings.themeMode, isSystemInDarkTheme())
    val accent = accentPrimary(settings.accentTheme, dark)
    val bioAvailable = activity != null && Biometrics.available(activity)

    val preview = Amount.pretty(DEMO_GRAIN)
    val previewFiat = Amount.fiat(DEMO_GRAIN, DEMO_PRICE)

    ScreenScaffold(title = stringResource(R.string.personalize_title), onBack = onBack) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.personalize_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(22.dp))
        PreviewCard(accent = accent, preview = preview, ticker = settings.preferredNetwork.ticker, fiat = previewFiat, reducedMotion = settings.reducedMotion, haptics = settings.odometerHaptics)

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.settings_section_language))
        Spacer(Modifier.height(8.dp))
        SectionCard {
            SettingRow(stringResource(R.string.settings_language), languageLabel(settings.appLanguage), onClick = { showLanguage = true })
        }

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.personalize_section_look))
        Spacer(Modifier.height(8.dp))
        SectionCard {
            // A 2×2 grid instead of one row of four: the theme names are long and
            // were the tightest thing on the screen.
            SegmentedGrid(
                options = listOf(ThemeMode.AUTO, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.OLED),
                selected = settings.themeMode,
                columns = 2,
                label = { stringResource(themeLabelRes(it)) },
                onSelect = { haptics.tick(); container.settings.themeMode = it },
            )
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AccentTheme.entries.forEach { theme ->
                    AccentSwatch(
                        color = accentPrimary(theme, dark),
                        selected = settings.accentTheme == theme,
                        label = accentLabel(theme),
                        onClick = { haptics.tick(); container.settings.accentTheme = theme },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SettingRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_sub)) {
                Switch(checked = settings.dynamicColor, onCheckedChange = { haptics.toggle(it); container.settings.dynamicColor = it })
            }
        }

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.personalize_section_security))
        Spacer(Modifier.height(8.dp))
        SectionCard {
            SettingRow(
                stringResource(R.string.settings_biometric),
                stringResource(R.string.personalize_biometric_sub),
                icon = AppIcons.Fingerprint,
                enabled = bioAvailable,
            ) {
                Switch(
                    checked = settings.pendingBiometricSetup,
                    enabled = bioAvailable,
                    onCheckedChange = { haptics.toggle(it); container.settings.pendingBiometricSetup = it },
                )
            }
        }

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.personalize_section_numbers))
        Spacer(Modifier.height(8.dp))
        SectionCard {
            Text(stringResource(R.string.settings_decimals), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Segmented(
                options = listOf(2, 4, 6, 8),
                selected = settings.decimals,
                label = { stringResource(R.string.option_value, it) },
                onSelect = { haptics.tick(); container.settings.decimals = it },
            )
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.settings_decimal_sep), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Segmented(
                options = DecimalSeparator.entries,
                selected = settings.decimalSeparator,
                label = { stringResource(if (it == DecimalSeparator.COMMA) R.string.decimal_comma else R.string.decimal_period) },
                onSelect = { haptics.tick(); container.settings.decimalSeparator = it },
            )
            Spacer(Modifier.height(14.dp))
            SettingRow(stringResource(R.string.personalize_grouping_toggle)) {
                Switch(
                    checked = settings.groupingSeparator != GroupingSeparator.NONE,
                    onCheckedChange = { on ->
                        haptics.toggle(on)
                        container.settings.groupingSeparator = if (on) GroupingSeparator.NARROW_SPACE else GroupingSeparator.NONE
                    },
                )
            }
        }

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.personalize_section_currency))
        Spacer(Modifier.height(8.dp))
        SectionCard(padding = 12.dp) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(FiatCurrency.entries.toList()) { fiat ->
                    FiatChip(
                        fiat = fiat,
                        selected = settings.fiatCurrency == fiat,
                        onClick = { haptics.tick(); container.settings.fiatCurrency = fiat },
                    )
                }
            }
        }

        Spacer(Modifier.height(SectionGap))
        SectionTitle(stringResource(R.string.personalize_section_motion))
        Spacer(Modifier.height(8.dp))
        SectionCard {
            SettingRow(stringResource(R.string.settings_reduced_motion), stringResource(R.string.settings_reduced_motion_sub)) {
                Switch(checked = settings.reducedMotion, onCheckedChange = { haptics.toggle(it); container.settings.reducedMotion = it })
            }
            SettingRow(stringResource(R.string.settings_odometer), stringResource(R.string.settings_odometer_sub)) {
                Switch(checked = settings.odometer, onCheckedChange = { haptics.toggle(it); container.settings.odometer = it })
            }
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            text = stringResource(if (next == "restore") R.string.welcome_restore else R.string.personalize_continue),
            onClick = { haptics.confirm(); if (next == "restore") onRestore() else onCreate() },
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = stringResource(if (next == "restore") R.string.welcome_create else R.string.welcome_restore),
            onClick = { haptics.click(); if (next == "restore") onCreate() else onRestore() },
        )
        Spacer(Modifier.height(28.dp))
    }
    }

    if (showLanguage) {
        LanguageDialog(
            current = settings.appLanguage,
            onPick = { tag ->
                haptics.tick()
                container.settings.appLanguage = tag
                showLanguage = false
                activity?.let { Locales.applyNow(it, tag) }
            },
            onDismiss = { showLanguage = false },
        )
    }
}

/** Animated card that previews the chosen accent and the live number format. */
@Composable
private fun PreviewCard(accent: Color, preview: String, ticker: String, fiat: String?, reducedMotion: Boolean, haptics: Boolean) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(accent.copy(alpha = 0.16f).compositeOver(cs.surface), label = "previewBg")
    val border by animateColorAsState(accent.copy(alpha = 0.45f), label = "previewBorder")
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp)).padding(20.dp),
    ) {
        Text(stringResource(R.string.personalize_preview), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OdometerText(
            text = "$preview $ticker",
            style = MaterialTheme.typography.headlineLarge,
            color = cs.onSurface,
            animate = !reducedMotion,
            haptics = haptics,
            extraTurns = 1,
        )
        if (fiat != null) {
            Spacer(Modifier.height(2.dp))
            OdometerText(text = stringResource(R.string.send_fiat_approx, fiat), style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, animate = !reducedMotion, haptics = haptics)
        }
    }
}

/** One row of equal-width segmented choices. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            SegmentedCell(option, option == selected, label, onSelect, Modifier.weight(1f))
        }
    }
}

/** A wrapped grid of segmented choices, so long labels get a whole cell each. */
@Composable
private fun <T> SegmentedGrid(options: List<T>, selected: T, columns: Int = 2, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    SegmentedCell(option, option == selected, label, onSelect, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun <T> SegmentedCell(option: T, isSelected: Boolean, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "segBg")
    val fg by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "segFg")
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable { onSelect(option) }.padding(vertical = 11.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label(option), color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, animationSpec = spring(dampingRatio = 0.5f), label = "swatchScale")
    // A 48dp selectable box keeps the touch target accessible; the visible circle stays 36dp.
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(36.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(color)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier),
        )
    }
}

@Composable
private fun accentLabel(accent: AccentTheme): String = stringResource(when (accent) {
    AccentTheme.PEARL -> R.string.accent_pearl
    AccentTheme.OCEAN -> R.string.accent_ocean
    AccentTheme.SUNSET -> R.string.accent_sunset
    AccentTheme.VIOLET -> R.string.accent_violet
    AccentTheme.FOREST -> R.string.accent_forest
    AccentTheme.ROSE -> R.string.accent_rose
    AccentTheme.MONO -> R.string.accent_mono
})

@Composable
private fun FiatChip(fiat: FiatCurrency, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "fiatBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "fiatFg")
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(fiat.symbol, color = fg, style = MaterialTheme.typography.labelLarge)
        Text(fiat.code.uppercase(), color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

private fun themeLabelRes(mode: ThemeMode) = when (mode) {
    ThemeMode.AUTO -> R.string.theme_auto
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.OLED -> R.string.theme_oled
}
