package dev.pocketprl.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pocketprl.ui.theme.TabularNumbers
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Number display that rolls like a mechanical odometer: when the text changes,
 * only the digits that differ spin, with a motion blur, before settling on the
 * new value. Digit slots keep their identity relative to the decimal point, so
 * "9.99" -> "10.05" spins the decimals in place. Non-digit characters are drawn
 * as ordinary text. While [spinning] every roller free-runs at its own pace;
 * when it stops, the rollers land left to right on the value in [text].
 * Every digit boundary a roller crosses fires one shared throttled tick, so a
 * spin feels like a slot machine and settling lands as slowing clicks.
 * [haptics] false silences the ticks; [animate] false already implies silence
 * because frozen rollers never cross a boundary.
 */
@Composable
fun OdometerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    /** Set false to freeze the display (hidden balances, previews): changes snap instead of rolling. */
    animate: Boolean = true,
    /** Keep the trailing digits turning until this goes false (a sync in progress). */
    spinning: Boolean = false,
    /** Slot-machine ticks on every digit roll; silently ignored when [animate] is false. */
    haptics: Boolean = true,
    durationMillis: Int = 900,
    /** Whole 0..9 turns a changed digit makes before it lands on its target. */
    extraTurns: Int = 1,
    /**
     * Landing rank of the first digit. A number split across two OdometerTexts
     * (whole part, then decimals) passes the whole part's digit count here so the
     * decimals land after it instead of alongside its first digit.
     */
    firstRank: Int = 0,
) {
    val digitStyle = style.merge(TabularNumbers)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Tabular figures: one measurement sizes every slot.
    val cell = remember(digitStyle, density, measurer) { measurer.measure("0", digitStyle).size }
    val cellWidth = with(density) { cell.width.toDp() }
    val cellHeight = with(density) { cell.height.toDp() }

    val anchor = text.indexOf('.').let { if (it >= 0) it else text.length }

    // Ticks only exist while the rollers can actually move; the driver holds
    // the only reference, so disabled rollers keep no watcher at all.
    val feedback = LocalHapticFeedback.current
    val fire: () -> Unit = { RollTicks.fire(feedback) }
    val onRoll: (() -> Unit)? = remember(feedback, haptics, animate) {
        if (haptics && animate) fire else null
    }

    // One semantics node for the whole string, or a screen reader announces digit by digit.
    val a11y = modifier.clearAndSetSemantics {
        contentDescription = text
        liveRegion = LiveRegionMode.Polite
    }
    Row(modifier = a11y, verticalAlignment = Alignment.CenterVertically) {
        var i = 0
        var rank = firstRank
        while (i < text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                // Slot identity is the offset from the decimal point, so a digit keeps
                // its roller when the integer part grows or shrinks.
                key(i - anchor) {
                    DigitRoller(
                        digit = ch - '0',
                        rank = rank,
                        style = digitStyle,
                        color = color,
                        width = cellWidth,
                        height = cellHeight,
                        animate = animate,
                        spinning = spinning && animate,
                        durationMillis = durationMillis,
                        extraTurns = extraTurns,
                        onRoll = onRoll,
                    )
                }
                rank++
                i++
            } else {
                val start = i
                while (i < text.length && !text[i].isDigit()) i++
                Text(
                    text.substring(start, i),
                    style = digitStyle,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
    }
}

/** Fastest speed, in digits per second, that the blur is scaled against. */
private const val BLUR_FULL_SPEED = 28f
/** Blur at full speed. */
private val MAX_BLUR = 1.1.dp
/** Pace band for a free-running roller, in milliseconds per full 0..9 turn; each roller picks its own base within it. */
private const val SPIN_TURN_MIN_MILLIS = 600
private const val SPIN_TURN_MAX_MILLIS = 920
/** Extra settle time per digit of rank when landing from a spin, so the number stops left to right. */
private const val LAND_STAGGER_MILLIS = 90
/** Furthest a landing reel overshoots or undershoots its digit before correcting. */
private const val LAND_MISS_MAX_DIGITS = 3
/** How long the wrong digit stays on screen before the correction. */
private const val LAND_MISS_HOLD_MIN_MILLIS = 120L
private const val LAND_MISS_HOLD_MAX_MILLIS = 280L
/**
 * Fastest the roll ticks may fire: every odometer on screen shares one budget,
 * so rollers crossing a boundary on the same frame collapse into a single tick
 * instead of stacking vibrator Binder calls on the UI thread. Main thread only.
 */
private const val ROLL_TICK_MIN_INTERVAL_MILLIS = 50L
/** Minimum strip travel between two ticks: the landing spring rings around its
 * digit, and re-crossings smaller than this are settle wobble, not rolls. */
private const val ROLL_TICK_MIN_TRAVEL_DIGITS = 0.6f

private object RollTicks {
    private var last = 0L
    fun fire(feedback: HapticFeedback) {
        val now = SystemClock.uptimeMillis()
        if (now - last >= ROLL_TICK_MIN_INTERVAL_MILLIS) {
            last = now
            feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
}

/**
 * Boundary-crossing detector with a settle guard. One per roller, kept across
 * launches so a spin, its landing and the correction share continuity.
 * Plain holder, not state: only animation frames touch it, never composition.
 */
private class RollTickTracker {
    private var tickedIdx: Int? = null
    private var anchor = 0f

    /** Call on every animation frame; [fire] runs once per genuine digit roll. */
    fun onFrame(pos: Float, fire: () -> Unit) {
        val idx = mod10(floor(pos).toInt())
        val last = tickedIdx
        if (last == null) {
            tickedIdx = idx
            anchor = pos
            return
        }
        if (idx != last && abs(pos - anchor) >= ROLL_TICK_MIN_TRAVEL_DIGITS) {
            tickedIdx = idx
            anchor = pos
            fire()
        }
    }

    /**
     * Call when the choreography completes: if a tick was throttled or held
     * back as wobble, the final digit still gets its click. Bypasses the
     * throttle on purpose so the tick lands exactly with the last digit.
     */
    fun flush(pos: Float, fire: () -> Unit) {
        val idx = mod10(floor(pos).toInt())
        if (tickedIdx != idx) {
            tickedIdx = idx
            anchor = pos
            fire()
        }
    }
}

/** True mathematical mod: the render strip only ever shows value mod 10. */
private fun mod10(v: Int) = ((v % 10) + 10) % 10

/** Alpha mask for a moving cell: solid through the middle, gone at the top and bottom edge. */
private val EDGE_FADE = Brush.verticalGradient(
    0f to Color.Transparent,
    0.22f to Color.Black,
    0.78f to Color.Black,
    1f to Color.Transparent,
)

@Composable
private fun DigitRoller(
    digit: Int,
    rank: Int,
    style: TextStyle,
    color: Color,
    width: Dp,
    height: Dp,
    animate: Boolean,
    spinning: Boolean,
    durationMillis: Int,
    extraTurns: Int,
    /** Fired once per digit boundary the roller crosses; null disables roll ticks. */
    onRoll: (() -> Unit)? = null,
) {
    // Rolling digit counter; the fractional part is the strip's travel between two digits.
    val position = remember { Animatable(digit.toFloat()) }
    var shownDigit by remember { mutableStateOf<Int?>(null) }
    // Plain holder, not state: only the effect reads it, and a write must not recompose.
    val wasSpinning = remember { booleanArrayOf(false) }
    val tracker = remember { RollTickTracker() }
    // Ticks ride the animation frames themselves: no watcher, no flow, nothing
    // waking up between frames. Snaps never pass through here, so they stay silent.
    val tickFrame: (Animatable<Float, AnimationVector1D>) -> Unit = { anim ->
        if (onRoll != null) tracker.onFrame(anim.value, onRoll)
    }

    LaunchedEffect(digit, animate, spinning, onRoll) {
        val previous = shownDigit
        shownDigit = digit
        val landing = wasSpinning[0] && !spinning
        wasSpinning[0] = spinning
        // Fold the counter back into 0..10: rendering only uses the value mod 10,
        // and this stops the float from drifting over a long session.
        val current = position.value.mod(10f)
        position.snapTo(current)
        if (spinning) {
            // Free-run until cancelled; the next launch rolls on to the real digit from wherever this stopped.
            val rng = Random
            val base = rng.nextInt(SPIN_TURN_MIN_MILLIS, SPIN_TURN_MAX_MILLIS + 1)
            var legDigits = rng.nextInt(2, 12)
            while (true) {
                val pace = base * rng.nextInt(75, 126) / 100
                position.animateTo(
                    position.value + legDigits,
                    tween((pace * legDigits / 10f).roundToInt(), easing = LinearEasing),
                    block = tickFrame,
                )
                legDigits = rng.nextInt(5, 16)
            }
        }
        // A roller stopped mid-turn must finish its journey even if the digit did not change.
        val resting = abs(current - digit) < 0.01f
        if (previous == null || !animate || (previous == digit && resting)) {
            position.snapTo(digit.toFloat())
            return@LaunchedEffect
        }
        val delta = (digit - current + 10f).mod(10f)
        val target = current + delta + 10f * extraTurns
        if (!landing) {
            position.animateTo(target, tween(durationMillis, easing = FastOutSlowInEasing), block = tickFrame)
            // The throttle may have eaten the last crossing: land the final click exactly.
            if (onRoll != null) tracker.flush(position.value, onRoll)
            return@LaunchedEffect
        }
        // Landing from a spin: settle left to right, overshoot, hold, then spring back.
        val stagger = rank * LAND_STAGGER_MILLIS + Random.nextInt(0, 120)
        val miss = Random.nextInt(1, LAND_MISS_MAX_DIGITS + 1) * (if (Random.nextBoolean()) 1 else -1)
        position.animateTo(target + miss, tween(durationMillis + stagger, easing = FastOutSlowInEasing), block = tickFrame)
        delay(Random.nextLong(LAND_MISS_HOLD_MIN_MILLIS, LAND_MISS_HOLD_MAX_MILLIS))
        position.animateTo(target, spring(dampingRatio = 0.5f, stiffness = 260f), block = tickFrame)
        // The spring rings around the digit after arriving; the guard held those
        // back as wobble, so flush the one click the landing actually ends on.
        if (onRoll != null) tracker.flush(position.value, onRoll)
    }

    val p = position.value
    val speed = (abs(position.velocity) / BLUR_FULL_SPEED).coerceIn(0f, 1f)
    // Quantised so a new RenderEffect is not built on every frame.
    val blur = MAX_BLUR * ((speed * 6f).roundToInt() / 6f)
    val base = floor(p)
    val moving = speed > 0f || abs(p - base) > 0.001f

    Box(
        modifier = Modifier.width(width).height(height).clipToBounds()
            // Fade the top and bottom edges while moving; at rest the digit is drawn plain.
            .graphicsLayer { compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
            .drawWithContent {
                drawContent()
                if (moving) drawRect(brush = EDGE_FADE, blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Unbounded: a blur clamped at the strip's own rectangle shows as a faint band at the cell edges.
        Box(modifier = if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier) {
            // One digit either side of the window so the blur has something to smear in from the edges.
            for (k in -1..2) {
                val value = base + k
                val shown = ((value.toInt() % 10) + 10) % 10
                Text(
                    ('0' + shown).toString(),
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.graphicsLayer {
                        translationY = (value - p) * size.height
                        alpha = 1f - 0.08f * speed.coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}
