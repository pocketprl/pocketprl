package dev.pocketprl.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How far along the track the thumb must be when the finger lifts for the slide to count. */
private const val COMMIT_FRACTION = 0.85f

/**
 * Slide-to-send track. Drag the thumb (or anywhere on the track) to the far end
 * and let go: the thumb parks there and [onComplete] fires. Let go early and it
 * springs back. While [held] the thumb stays parked at the end; drop [held]
 * without sending and the thumb returns on its own.
 */
@Composable
fun SlideToSend(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    held: Boolean = false,
    /** Shows a spinner in the thumb. */
    busy: Boolean = false,
    label: String = stringResource(R.string.send_slider),
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val notReady = stringResource(R.string.send_not_ready)
    val sending = stringResource(R.string.send_sending)
    val confirming = stringResource(R.string.send_confirming)
    val slideHint = stringResource(R.string.send_slide)
    val trackHeight = 60.dp
    val inset = 5.dp
    val thumb = trackHeight - inset * 2
    val thumbPx = with(density) { thumb.toPx() }
    val insetPx = with(density) { inset.toPx() }
    var trackWidth by remember { mutableIntStateOf(0) }
    val maxX = (trackWidth - thumbPx - insetPx * 2).coerceAtLeast(1f)
    val offset = remember { Animatable(0f) }
    var completed by remember { mutableStateOf(false) }

    // Parked at the end and released by the caller: spring back.
    LaunchedEffect(held, completed) {
        if (completed && !held) {
            completed = false
            offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    // Disabled mid-drag: reset without animation.
    LaunchedEffect(enabled) { if (!enabled && !completed) offset.snapTo(0f) }

    val progress = (offset.value / maxX).coerceIn(0f, 1f)
    val scheme = MaterialTheme.colorScheme
    val track = if (enabled) scheme.primary else scheme.surfaceVariant
    val thumbColor = if (enabled) scheme.primaryContainer else scheme.surface
    val glyph = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val labelColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant

    fun springBack() { scope.launch { offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) } }
    fun commit() {
        scope.launch {
            offset.animateTo(maxX, tween(120))
            completed = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onComplete()
        }
    }
    // Haptic tick once per crossing of the commit point; re-armed if the thumb comes back.
    var armed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(CircleShape)
            .background(track)
            // A drag-only control is invisible to TalkBack and switch access; expose the track as one button.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = when {
                    !enabled -> notReady
                    busy -> sending
                    completed -> confirming
                    else -> slideHint
                }
                if (enabled && !completed) onClick(label = label) { commit(); true }
            }
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(enabled, completed, maxX) {
                if (!enabled || completed) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offset.value >= maxX * COMMIT_FRACTION) commit() else springBack()
                        armed = false
                    },
                    onDragCancel = { springBack(); armed = false },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(0f, maxX)
                        val past = next >= maxX * COMMIT_FRACTION
                        if (past && !armed) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        armed = past
                        scope.launch { offset.snapTo(next) }
                    },
                )
            },
    ) {
        Text(
            label,
            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) }.clearAndSetSemantics {},
            style = MaterialTheme.typography.titleMedium,
            color = labelColor,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(inset)
                .size(thumb)
                .clip(CircleShape)
                .background(thumbColor)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = glyph)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = glyph, modifier = Modifier.size(24.dp))
        }
    }
}
