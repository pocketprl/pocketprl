package dev.pocketprl.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.AddressError
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.R
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.Mono
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.TabularNumbers
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.IdentityHashMap
import kotlinx.coroutines.delay

val FieldShape = RoundedCornerShape(12.dp)
val CardShape = RoundedCornerShape(20.dp)
val ButtonShape = CircleShape
/** Minimum height for every full-width button; a large font scale grows the button instead of clipping the label. */
val ButtonHeight = 52.dp

/** What an amount shows as while balances are hidden. */
const val HIDDEN = "•••••"
/** Content alpha for a disabled settings row (Material3 has no ContentAlpha). */
private const val DisabledContentAlpha = 0.38f

/**
 * Standard screen frame: top bar, system-bar insets and keyboard (IME) insets.
 * Edge-to-edge apps get no `adjustResize` for free, so the IME padding here is
 * what keeps text fields above the keyboard on every screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    /** When set, the title becomes a drop-down trigger (used for the wallet switcher). */
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleModifier = if (onTitleClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTitleClick).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier
                    Column(modifier = titleModifier) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (onTitleClick != null) Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.action_switch_wallet), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), content = content)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight), shape = ButtonShape) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight), shape = ButtonShape,
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors(),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Icon-and-label button for the dashboard's Send / Receive pair: filled for the primary action, outlined for the other. */
@Composable
fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false, height: androidx.compose.ui.unit.Dp = ButtonHeight) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.heightIn(min = height)
    if (outlined) OutlinedButton(onClick = onClick, modifier = m, shape = ButtonShape, content = content)
    else Button(onClick = onClick, modifier = m, shape = ButtonShape, content = content)
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) AppIcons.VisibilityOff else AppIcons.Visibility, contentDescription = stringResource(if (visible) R.string.pwd_hide else R.string.pwd_show))
            }
        },
        shape = FieldShape,
    )
}

/** Rough zxcvbn-style score without the dictionary: 0..4. */
fun passwordScore(pw: String): Int {
    if (pw.isEmpty()) return 0
    var score = 0
    if (pw.length >= 8) score++
    if (pw.length >= 12) score++
    val classes = listOf(pw.any { it.isLowerCase() }, pw.any { it.isUpperCase() }, pw.any { it.isDigit() }, pw.any { !it.isLetterOrDigit() }).count { it }
    if (classes >= 3) score++
    if (pw.length >= 16 && classes >= 3) score++
    if (pw.length < 8) score = minOf(score, 1)
    return score.coerceIn(0, 4)
}

@Composable
fun PasswordStrength(password: String, modifier: Modifier = Modifier) {
    val score = passwordScore(password)
    val palette = PearlTheme.palette
    val (label, color) = when (score) {
        0, 1 -> stringResource(R.string.pwd_weak) to MaterialTheme.colorScheme.error
        2 -> stringResource(R.string.pwd_fair) to palette.warning
        3 -> stringResource(R.string.pwd_good) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.pwd_strong) to palette.success
    }
    val target = (score / 4f).coerceAtLeast(0.08f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "strength",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.pwd_strength, label), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

enum class BannerKind { INFO, WARNING, ERROR, SUCCESS }

@Composable
fun InfoBanner(
    text: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    /** Optional action at the end of the banner, e.g. a "Retry" button on an error. */
    action: (@Composable () -> Unit)? = null,
) {
    val palette = PearlTheme.palette
    val (bg, fg) = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerKind.WARNING -> palette.warningContainer to palette.onWarningContainer
        BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerKind.SUCCESS -> palette.successContainer to palette.onSuccessContainer
    }
    Row(modifier = modifier.fillMaxWidth().background(bg, FieldShape).padding(12.dp), verticalAlignment = if (action != null) Alignment.CenterVertically else Alignment.Top) {
        Icon(if (kind == BannerKind.INFO || kind == BannerKind.SUCCESS) Icons.Filled.Info else Icons.Filled.Warning, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) Text(title, color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = m.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(modifier = Modifier.padding(padding), content = content) }
}

/** Small tracked-out uppercase label above a group of settings or cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        color = color, modifier = modifier.padding(top = 6.dp, bottom = 0.dp),
    )
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, icon: ImageVector? = null, enabled: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }.heightIn(min = 48.dp).padding(vertical = 4.dp).alpha(if (enabled) 1f else DisabledContentAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, style: TextStyle = MaterialTheme.typography.bodyMedium, maxLines: Int = Int.MAX_VALUE, textAlign: TextAlign? = null) {
    Text(text, fontFamily = Mono, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

/** Bech32 address in monospace, grouped in fours so it can be checked by eye against another screen. */
@Composable
fun AddressText(address: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, style: TextStyle = MaterialTheme.typography.bodyMedium, textAlign: TextAlign? = null) {
    val grouped = remember(address) { address.chunked(4).joinToString(" ") }
    Text(grouped, fontFamily = Mono, modifier = modifier, color = color, style = style, textAlign = textAlign)
}

@Composable
fun AmountText(
    grain: Long,
    network: Network,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    signed: Boolean = false,
    hidden: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    /** Roll the changed digits like an odometer instead of swapping the text. */
    odometer: Boolean = false,
    /** Odometer only: keep every digit turning while the number is still being worked out. */
    spinning: Boolean = false,
    /** Master switch for the roll animation itself; false snaps to the new value. */
    animate: Boolean = true,
    /** Slot-machine tick on every digit roll; needs [animate] to have any effect. */
    haptics: Boolean = true,
) {
    val sign = if (signed && grain > 0) "+" else ""
    val hiddenText = stringResource(R.string.hidden_placeholder)
    val hiddenDesc = stringResource(R.string.balance_hidden)
    val body = if (hidden) hiddenText else "$sign${Amount.pretty(grain)}"
    val full = "$body ${network.ticker}"
    if (odometer && !hidden) {
        OdometerText(full, modifier = modifier, style = style.merge(TextStyle(fontWeight = FontWeight.Bold)), color = color, spinning = spinning, animate = animate, haptics = haptics)
    } else {
        // Screen readers announce the bullets literally; say what they mean instead.
        val m = if (hidden) modifier.semantics { contentDescription = hiddenDesc } else modifier
        Text(full, modifier = m, style = style.merge(TabularNumbers), color = color, fontWeight = FontWeight.Bold, textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        if (mono) MonoText(value, color = valueColor, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
        else Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers), modifier = Modifier.weight(0.6f), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LoadingBlock(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, text: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

@Composable
fun WordChip(index: Int, word: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
        MonoText(word, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Circle with the contact's initial, used wherever a contact name is shown. */
@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier = modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
    }
}

/**
 * Blocks screenshots / recents thumbnails while any composable using this is on
 * screen. Reference-counted per window so two overlapping users (e.g. a step
 * transition inside one screen) never clear the flag early.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        if (window != null) SecureFlags.acquire(window)
        onDispose { if (window != null) SecureFlags.release(window) }
    }
}

object SecureFlags {
    private val counts = IdentityHashMap<Window, Int>()

    @Volatile
    private var appWide = false

    /** App-wide screenshot blocking (Settings › Security). Survives per-screen acquire/release. */
    fun setAppWide(w: Window, enabled: Boolean) {
        appWide = enabled
        if (enabled) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            synchronized(counts) { if ((counts[w] ?: 0) == 0) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    fun acquire(w: Window) = synchronized(counts) {
        val n = (counts[w] ?: 0) + 1
        counts[w] = n
        if (n == 1) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(w: Window) = synchronized(counts) {
        val n = (counts[w] ?: 1) - 1
        if (n <= 0) {
            counts.remove(w)
            if (!appWide) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else counts[w] = n
    }
}

fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false, toast: Boolean = true) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    if (toast && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/**
 * "just now" for the first five seconds, then "6s ago", "7s ago"… by the second,
 * minutes from there. Pass a ticking [nowSeconds] (see [rememberNowSeconds]) so
 * the label keeps counting between recompositions.
 */
@Composable
fun timeAgo(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    if (epochSeconds <= 0) return stringResource(R.string.time_pending)
    val diff = (nowSeconds - epochSeconds).coerceAtLeast(0)
    return when {
        diff <= 5 -> stringResource(R.string.time_just_now)
        diff < 60 -> stringResource(R.string.time_ago_s, diff)
        diff < 3600 -> stringResource(R.string.time_ago_m, diff / 60)
        diff < 86400 -> stringResource(R.string.time_ago_h, diff / 3600)
        diff < 86400 * 30 -> stringResource(R.string.time_ago_d, diff / 86400)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))
    }
}

/** Wall-clock seconds as state that advances on the second. */
@Composable
fun rememberNowSeconds(): Long {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            val ms = System.currentTimeMillis()
            // Aligned to the next whole second so every label ticks together.
            delay(1000 - ms % 1000)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}

@Composable
fun formatDateTime(epochSeconds: Long): String =
    if (epochSeconds <= 0) stringResource(R.string.label_unconfirmed) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

/** Localized message for an address parse failure. */
fun addressErrorText(context: Context, invalid: Address.Result.Invalid): String = when (invalid.error) {
    AddressError.ENTER -> context.getString(R.string.address_err_enter)
    AddressError.INVALID -> context.getString(R.string.address_err_invalid)
    AddressError.UNKNOWN_PREFIX -> context.getString(R.string.address_err_unknown_prefix, invalid.arg1 ?: "")
    AddressError.ONLY_TAPROOT -> context.getString(R.string.address_err_only_taproot, invalid.arg1 ?: "")
    AddressError.INVALID_LENGTH -> context.getString(R.string.address_err_invalid_length)
    AddressError.WRONG_NETWORK -> context.getString(R.string.address_err_wrong_network, invalid.arg1 ?: "", invalid.arg2 ?: "")
}

/** Rough human ETA for a confirmation target, localized; see [Network.etaForBlocks]. */
@Composable
fun etaBlocks(blocks: Int, secondsPerBlock: Long = Network.TARGET_BLOCK_SECONDS): String {
    val minutes = (blocks * secondsPerBlock + 30) / 60
    return when {
        minutes < 60 -> stringResource(R.string.eta_minutes, minutes)
        minutes % 60 < 15 -> stringResource(R.string.eta_hours, minutes / 60)
        else -> stringResource(R.string.eta_hours_half, minutes / 60)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DAY_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "Today" / "Yesterday" / "Tue, 3 Sep" / "3 Sep 2025" group header for a timestamp. */
@Composable
fun dayLabel(epochSeconds: Long, now: LocalDate = LocalDate.now()): String {
    if (epochSeconds <= 0) return stringResource(R.string.time_pending)
    val day = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day == now -> stringResource(R.string.day_today)
        day == now.minusDays(1) -> stringResource(R.string.day_yesterday)
        day.year == now.year -> DAY_FORMAT.format(day)
        else -> DAY_YEAR_FORMAT.format(day)
    }
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
