package dev.pocketprl.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * One vocabulary of taps for the whole app, so a toggle feels like a toggle
 * everywhere and a copy feels like a copy.
 */
class Haptics(private val h: HapticFeedback) {
    /** A selection changed: fee tier, filter chip, unit swap. */
    fun tick() = h.performHapticFeedback(HapticFeedbackType.SegmentTick)
    /** A switch flipped. */
    fun toggle(on: Boolean) = h.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    /** Something succeeded: copied, unlocked, sent. */
    fun confirm() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    /** Something was refused: wrong password, failed auth. */
    fun reject() = h.performHapticFeedback(HapticFeedbackType.Reject)
    /** A plain tap on something that does work: rotate address, pick a wallet. */
    fun click() = h.performHapticFeedback(HapticFeedbackType.ContextClick)
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    return remember(h) { Haptics(h) }
}
