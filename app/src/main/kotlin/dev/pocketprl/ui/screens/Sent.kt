package dev.pocketprl.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import dev.pocketprl.core.chain.Network
import dev.pocketprl.ui.components.AmountText
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.copyToClipboard
import dev.pocketprl.ui.components.etaBlocks
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.LocalReducedMotion
import dev.pocketprl.ui.theme.PearlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Confirmation screen after a broadcast: plane flies into a badge, then the amount
 * and details animate in. [animate] false renders the resting state straight away.
 */
@Composable
fun SentContent(
    txid: String,
    amountGrain: Long,
    network: Network,
    toLabel: String,
    onExplorer: () -> Unit,
    onDone: () -> Unit,
    animate: Boolean = true,
    odometer: Boolean = true,
    odometerHaptics: Boolean = true,
    secondsPerBlock: Long = Network.TARGET_BLOCK_SECONDS,
    titleRes: Int = R.string.sent_title,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val palette = PearlTheme.palette
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReducedMotion.current
    val fly = remember { Animatable(if (animate) 0f else 1f) }
    val badge = remember { Animatable(if (animate) 0f else 1f) }
    val tick = remember { Animatable(if (animate) 0f else 1f) }
    var stage by remember { mutableIntStateOf(if (animate) 0 else 9) }
    var shownGrain by remember { mutableLongStateOf(if (animate) 0L else amountGrain) }

    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        launch { fly.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        delay(430)
        launch { badge.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f)) }
        delay(170)
        stage = 1
        delay(300)
        launch { tick.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 520f)) }
        haptics.tick()
        shownGrain = amountGrain
        stage = 2
        delay(350)
        stage = 3
        delay(250)
        stage = 4
    }
    val amountAlpha by animateFloatAsState(if (stage >= 2) 1f else 0f, tween(300), label = "amount")

    ScreenScaffold(title = "", onBack = null) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(120.dp)
                        .graphicsLayer { scaleX = badge.value; scaleY = badge.value; alpha = badge.value.coerceIn(0f, 1f) }
                        .background(palette.successContainer, CircleShape),
                )
                val t = fly.value
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = palette.success,
                    modifier = Modifier.size(46.dp).graphicsLayer {
                        val ease = t
                        translationX = -150.dp.toPx() * (1f - ease)
                        translationY = 120.dp.toPx() * (1f - ease) - sin(ease * PI).toFloat() * 44.dp.toPx()
                        rotationZ = -30f - 18f * (1f - ease)
                        val s = 1f + 0.35f * (1f - ease)
                        scaleX = s; scaleY = s
                        alpha = min(1f, t * 5f)
                    },
                )
                Box(
                    modifier = Modifier.offset(x = 42.dp, y = 42.dp).size(36.dp)
                        .graphicsLayer { scaleX = tick.value; scaleY = tick.value }
                        .background(palette.success, CircleShape)
                        .border(3.dp, cs.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = cs.background, modifier = Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(visible = stage >= 1, enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.headlineLarge, color = cs.onBackground)
            }
            Spacer(Modifier.height(6.dp))
            // Always in the tree so the odometer has a starting value to roll from.
            AmountText(shownGrain, network, style = MaterialTheme.typography.displaySmall, odometer = true, animate = odometer && !reduce, haptics = odometerHaptics, modifier = Modifier.graphicsLayer { alpha = amountAlpha })
            AnimatedVisibility(visible = stage >= 3, enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sent_to, toLabel), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sent_confirms, etaBlocks(1, secondsPerBlock)), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(visible = stage >= 4, enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 5 }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard {
                        Text(stringResource(R.string.sent_txid), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        val txidLabel = stringResource(R.string.clipboard_txid)
                        AddressLine(txid) { haptics.confirm(); copyToClipboard(context, txidLabel, txid) }
                    }
                    SecondaryButton(stringResource(R.string.sent_view_explorer), icon = AppIcons.OpenInNew, onClick = onExplorer)
                    PrimaryButton(stringResource(R.string.sent_done), onClick = onDone)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
