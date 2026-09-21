package dev.pocketprl.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.pocketprl.R
import dev.pocketprl.data.AccentTheme
import dev.pocketprl.data.ThemeMode

object PearlColors {
    val Teal = Color(0xFF0F7B6C)
    val TealDark = Color(0xFF0B3D3A)
    val Mint = Color(0xFF7FE0C4)
    val MintSoft = Color(0xFFB8F1E3)
    val Pearl = Color(0xFFF7FAF9)
    val Ink = Color(0xFF0E1413)
    val Surface = Color(0xFF141C1B)
    val SurfaceHigh = Color(0xFF1C2625)
    val Amber = Color(0xFFB45309)
    val AmberSoft = Color(0xFFFEF3C7)
    val Red = Color(0xFFDC2626)
    val Green = Color(0xFF15803D)
    val Violet = Color(0xFF7A5C99)
    // Light-on-dark variants (~7:1 contrast on Ink/Surface).
    val GreenDark = Color(0xFF4ADE80)
    val AmberDark = Color(0xFFFBBF24)
    val TealLight = Color(0xFF5EEAD4)
}

/**
 * Semantic colors Material 3 does not provide. Resolved from the *app's* theme
 * choice (not the system setting) so a forced dark or light theme stays legible.
 */
@Immutable
data class PearlPalette(
    val isDark: Boolean,
    val success: Color,
    val warning: Color,
    val accent: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightPalette = PearlPalette(
    isDark = false,
    success = PearlColors.Green,
    warning = PearlColors.Amber,
    accent = PearlColors.Teal,
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF14532D),
    warningContainer = PearlColors.AmberSoft,
    onWarningContainer = Color(0xFF92400E),
)

private val DarkPalette = PearlPalette(
    isDark = true,
    success = PearlColors.GreenDark,
    warning = PearlColors.AmberDark,
    accent = PearlColors.TealLight,
    successContainer = Color(0xFF052E16),
    onSuccessContainer = Color(0xFF86EFAC),
    warningContainer = Color(0xFF451A03),
    onWarningContainer = Color(0xFFFCD34D),
)

val LocalPearlPalette = staticCompositionLocalOf { LightPalette }

object PearlTheme {
    val palette: PearlPalette
        @Composable get() = LocalPearlPalette.current
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = PearlColors.Teal,
    onPrimary = Color.White,
    primaryContainer = PearlColors.MintSoft,
    onPrimaryContainer = PearlColors.TealDark,
    secondary = Color(0xFF4A6B66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBE5),
    onSecondaryContainer = Color(0xFF07201D),
    tertiary = PearlColors.Violet,
    background = PearlColors.Pearl,
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EFED),
    onSurfaceVariant = Color(0xFF4B5563),
    // Every container level is set explicitly: the M3 defaults are neutral-purple and sheets and dialogs pick them up.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6F5),
    surfaceContainer = Color(0xFFECF2F0),
    surfaceContainerHigh = Color(0xFFE8EFED),
    surfaceContainerHighest = Color(0xFFE1E9E6),
    outline = Color(0xFFCBD5D1),
    outlineVariant = Color(0xFFDFE7E4),
    error = PearlColors.Red,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = PearlColors.Mint,
    onPrimary = PearlColors.TealDark,
    primaryContainer = Color(0xFF12524A),
    onPrimaryContainer = PearlColors.MintSoft,
    secondary = Color(0xFFA9CFC8),
    onSecondary = Color(0xFF0E2B27),
    secondaryContainer = Color(0xFF243D39),
    onSecondaryContainer = Color(0xFFCDEBE5),
    tertiary = Color(0xFFC9B3E6),
    background = PearlColors.Ink,
    onBackground = Color(0xFFE5E7EB),
    surface = PearlColors.Surface,
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = PearlColors.SurfaceHigh,
    onSurfaceVariant = Color(0xFFA1A9A7),
    surfaceContainerLowest = Color(0xFF0B100F),
    surfaceContainerLow = Color(0xFF121A19),
    surfaceContainer = Color(0xFF172120),
    surfaceContainerHigh = PearlColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF26312F),
    outline = Color(0xFF3A4745),
    outlineVariant = Color(0xFF243130),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFCA5A5),
)

/**
 * Dark scheme with true-black surfaces for OLED. Background and surface are pure
 * #000000 so unlit pixels stay off; the container levels keep just enough
 * separation to remain visible without lighting up large areas.
 */
private val OledScheme: ColorScheme = DarkScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF141414),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
)

/**
 * Manrope, one variable TTF. An entry per weight so `fontWeight` resolves through
 * the wght axis instead of faux-bolding a single instance.
 */
val Manrope: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { w ->
        Font(R.font.manrope, weight = w, variationSettings = FontVariation.Settings(w, FontStyle.Normal))
    },
)

private val M3 = Typography()
private fun TextStyle.manrope(weight: FontWeight? = null, letterSpacing: androidx.compose.ui.unit.TextUnit = this.letterSpacing) =
    copy(fontFamily = Manrope, fontWeight = weight ?: fontWeight, letterSpacing = letterSpacing)

val AppTypography = Typography(
    displayLarge = M3.displayLarge.manrope(FontWeight.ExtraBold),
    displayMedium = M3.displayMedium.manrope(FontWeight.ExtraBold),
    displaySmall = M3.displaySmall.manrope(FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = M3.headlineLarge.manrope(FontWeight.Bold),
    headlineMedium = M3.headlineMedium.manrope(FontWeight.SemiBold),
    headlineSmall = M3.headlineSmall.manrope(FontWeight.SemiBold),
    titleLarge = M3.titleLarge.manrope(FontWeight.SemiBold),
    titleMedium = M3.titleMedium.manrope(),
    titleSmall = M3.titleSmall.manrope(),
    bodyLarge = M3.bodyLarge.manrope(),
    bodyMedium = M3.bodyMedium.manrope(),
    bodySmall = M3.bodySmall.manrope(),
    labelLarge = M3.labelLarge.manrope(),
    labelMedium = M3.labelMedium.manrope(),
    labelSmall = M3.labelSmall.manrope(letterSpacing = 0.3.sp),
)

/** Geist Mono for addresses, txids and seed words. */
val Mono: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { w ->
        Font(R.font.geistmono, weight = w, variationSettings = FontVariation.Settings(w, FontStyle.Normal))
    },
)

/** Lining, tabular figures so amounts line up and keep their width while changing. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum, lnum")

fun isDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.OLED -> true
    ThemeMode.AUTO -> systemDark
}

/** True when the user asked for as little motion as possible; components should snap instead of animate. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Primary colour each accent theme uses, per light/dark. */
fun accentPrimary(theme: AccentTheme, dark: Boolean): Color = when (theme) {
    AccentTheme.PEARL -> if (dark) PearlColors.Mint else PearlColors.Teal
    AccentTheme.OCEAN -> if (dark) Color(0xFF7CC4FF) else Color(0xFF1D6FB8)
    AccentTheme.SUNSET -> if (dark) Color(0xFFFDBA74) else Color(0xFFC2410C)
    AccentTheme.VIOLET -> if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)
    AccentTheme.FOREST -> if (dark) Color(0xFF86EFAC) else Color(0xFF15803D)
    AccentTheme.ROSE -> if (dark) Color(0xFFFDA4AF) else Color(0xFFBE185D)
    AccentTheme.MONO -> if (dark) Color(0xFFCBD5E1) else Color(0xFF334155)
}

/** Rebuilds a scheme with the accent family swapped in, leaving the Pearl neutrals in place. */
fun withAccent(base: ColorScheme, accent: Color, dark: Boolean): ColorScheme {
    val container = accent.copy(alpha = if (dark) 0.24f else 0.16f).compositeOver(base.surface)
    val onContainer = lerp(accent, if (dark) Color.White else Color.Black, 0.25f)
    val onAccent = if (dark) Color(0xFF0E1413) else Color.White
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        tertiary = lerp(accent, if (dark) Color(0xFFC9B3E6) else PearlColors.Violet, 0.4f),
        onTertiary = onAccent,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
    )
}

@Composable
fun PocketPrlTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    accentTheme: AccentTheme = AccentTheme.PEARL,
    dynamicColor: Boolean = false,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode, isSystemInDarkTheme())
    val context = LocalContext.current
    val base = when {
        themeMode == ThemeMode.OLED -> OledScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    val scheme = when {
        // Dynamic colour has no OLED variant; an explicit OLED choice wins over it.
        dynamicColor && themeMode != ThemeMode.OLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> withAccent(base, accentPrimary(accentTheme, dark), dark)
    }
    CompositionLocalProvider(
        LocalPearlPalette provides if (dark) DarkPalette else LightPalette,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

/** Preview/test helper that follows the system theme with the default accent. */
@Composable
fun PocketPrlTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    PocketPrlTheme(themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT, content = content)
}

@Composable
fun successGreen(): Color = PearlTheme.palette.success

@Composable
fun warningAmber(): Color = PearlTheme.palette.warning

@Composable
fun accentTeal(): Color = PearlTheme.palette.accent
