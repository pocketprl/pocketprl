package dev.pocketprl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.Mono
import dev.pocketprl.ui.theme.TabularNumbers

/**
 * Large amount entry for the Send screen: the number, the unit beside it and the
 * conversion underneath. [fiatMode] puts a dollar sign in front and drops the
 * ticker; the caller does the conversion.
 */
@Composable
fun AmountHero(
    text: String,
    onTextChange: (String) -> Unit,
    ticker: String,
    fiatMode: Boolean,
    /** The amount in the other unit, e.g. "≈ $12.34" or "≈ 22.03 PRL". */
    secondary: String?,
    error: String?,
    /** Null when there is no price to convert with. */
    onSwap: (() -> Unit)?,
    onMax: (() -> Unit)? = null,
    maxSelected: Boolean = false,
    maxEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** Smaller, no heading: for use as one field inside a card. */
    compact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val t = MaterialTheme.typography
    val haptics = rememberHaptics()
    val focus = remember { FocusRequester() }
    val tap = remember { MutableInteractionSource() }
    val size = when {
        compact -> if (text.length <= 9) 36.sp else 28.sp
        text.length <= 7 -> 52.sp
        text.length <= 11 -> 40.sp
        else -> 30.sp
    }
    val style = t.displayMedium.merge(TabularNumbers).copy(fontSize = size, lineHeight = size * 1.15f, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, color = cs.onBackground, textAlign = TextAlign.Center)
    val muted = cs.onSurfaceVariant
    val maxDecimals = if (fiatMode) 2 else 8
    // A text field's intrinsic width is not its text's width; size it to the measured text plus room for the cursor.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fieldWidth = with(density) { measurer.measure(text.ifEmpty { "0" }, style).size.width.toDp() } + 6.dp
    Column(
        modifier = modifier.fillMaxWidth().clickable(interactionSource = tap, indication = null) { focus.requestFocus() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
            Text("Amount", style = t.labelLarge, color = muted)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (fiatMode) Text("$", style = style.copy(fontSize = size * 0.6f, color = muted), modifier = Modifier.alignByBaseline().padding(end = 2.dp))
            BasicTextField(
                value = text,
                onValueChange = { onTextChange(sanitizeAmount(it, maxDecimals)) },
                // An empty field draws its cursor where its text would start; end-aligned puts it after the placeholder.
                textStyle = style.copy(textAlign = if (text.isEmpty()) TextAlign.End else TextAlign.Center),
                singleLine = true,
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.alignByBaseline().width(fieldWidth).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("0", style = style.copy(color = muted.copy(alpha = 0.4f)))
                        inner()
                    }
                },
            )
            if (!fiatMode) {
                Spacer(Modifier.width(6.dp))
                Text(ticker, style = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = muted, modifier = Modifier.alignByBaseline())
            }
        }
        Spacer(Modifier.height(4.dp))
        // Reserved height so the pills below do not jump while typing.
        Box(modifier = Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            val line = error ?: secondary
            if (line != null) Text(line, style = t.titleMedium.merge(TabularNumbers), color = if (error != null) cs.error else muted, maxLines = 1)
        }
        if (onSwap != null || onMax != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSwap != null) SmallPill(if (fiatMode) "Enter in $ticker" else "Enter in USD", onClick = { haptics.tick(); onSwap() }, icon = AppIcons.Swap)
                if (onMax != null) SmallPill(if (maxSelected) "Max ✓" else "Max", onClick = { haptics.tick(); onMax() }, selected = maxSelected, enabled = maxEnabled)
            }
        }
    }
}

/** Digits and one decimal point, at most [maxDecimals] places after it. A comma is a decimal point. */
fun sanitizeAmount(raw: String, maxDecimals: Int): String {
    val sb = StringBuilder()
    var dot = false
    var decimals = 0
    for (c in raw) {
        when {
            c.isDigit() -> { if (dot && decimals >= maxDecimals) continue; if (dot) decimals++; sb.append(c) }
            (c == '.' || c == ',') && !dot -> { dot = true; sb.append('.') }
        }
    }
    return sb.toString()
}

/** Small tonal pill for secondary actions. */
@Composable
fun SmallPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primaryContainer else cs.surfaceContainer
    val fg = when {
        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> cs.onPrimaryContainer
        else -> cs.onSurface
    }
    Row(
        modifier = modifier.clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * One row for the recipient: avatar, the address in mono, and the ways to fill it
 * (contacts, paste, scan) while it is empty, or a clear button once it is not.
 */
@Composable
fun RecipientField(
    address: String,
    onAddressChange: (String) -> Unit,
    hrp: String,
    contactName: String?,
    error: String?,
    onContacts: () -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val edge = when {
        error != null -> cs.error
        focused -> cs.primary
        else -> Color.Transparent
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainer).border(1.5.dp, edge, shape).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (contactName != null) ContactAvatar(contactName, size = 36.dp)
            else Box(modifier = Modifier.size(36.dp).background(cs.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (contactName != null) "To • $contactName" else "To",
                    style = MaterialTheme.typography.labelSmall, color = if (contactName != null) cs.primary else cs.onSurfaceVariant, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, color = cs.onSurface),
                    maxLines = 2,
                    interactionSource = interaction,
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (address.isEmpty()) Text("${hrp}1p… or a contact", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
                            inner()
                        }
                    },
                )
            }
            if (address.isEmpty()) {
                FieldIcon(Icons.Filled.Person, "Contacts", onContacts)
                FieldIcon(AppIcons.Paste, "Paste", onPaste)
                FieldIcon(AppIcons.Scan, "Scan", onScan)
            } else {
                FieldIcon(Icons.Filled.Close, "Clear") { onAddressChange("") }
            }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    }
}

@Composable
private fun FieldIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

class FeeOption<T>(val key: T, val title: String, val eta: String, val rate: String?)

/** Fee tiers as a row of selectable tiles. */
@Composable
fun <T> FeeTierPicker(options: List<FeeOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            val sel = o.key == selected
            val fg = if (sel) cs.onPrimaryContainer else cs.onSurface
            val sub = if (sel) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
            Column(
                modifier = Modifier.weight(1f).clip(shape).background(if (sel) cs.primaryContainer else cs.surfaceContainer)
                    .border(1.5.dp, if (sel) cs.primary else Color.Transparent, shape)
                    .clickable { if (!sel) haptics.tick(); onSelect(o.key) }.padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(o.title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                Text(o.eta, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(o.rate ?: "…", style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = sub, maxLines = 1)
            }
        }
    }
}
