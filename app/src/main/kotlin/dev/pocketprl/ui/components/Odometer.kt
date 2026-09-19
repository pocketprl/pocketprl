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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
/** Blur radius is rounded to this many steps so the RenderEffect is rebuilt rarely. */
private const val BLUR_QUANTISATION = 6f
/** Highest digit-strip index rendered: floor(roll % 10) peaks at 9, plus one cell of blur margin each side. */
private const val DIGIT_STRIP_END = 11
/** Steady free-run speed band, in digits per second; each roller holds one speed for the whole spin. */
private const val SPIN_MIN_DIGITS_PER_SECOND = 15f
private const val SPIN_MAX_DIGITS_PER_SECOND = 20f
/** Digits per free-run leg. Equal-length legs at a fixed speed chain without a seam. */
private const val SPIN_LEG_DIGITS = 40
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
    val cellHeightPx = with(LocalDensity.current) { height.toPx() }
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
            // Steady free-run. Each roller picks one speed and holds it. This used to
            // chain legs of randomly varying pace, so every ~1 s the tween restarted and
            // the velocity (and therefore the motion blur) jumped; once the balance
            // stopped changing and the free-run ran uninterrupted that read as the digits
            // "aligning" and ticking instead of blurring. A constant velocity per roller
            // keeps the same smooth look the settle has.
            val rng = Random
            val digitsPerSecond = SPIN_MIN_DIGITS_PER_SECOND +
                rng.nextFloat() * (SPIN_MAX_DIGITS_PER_SECOND - SPIN_MIN_DIGITS_PER_SECOND)
            val legMillis = (SPIN_LEG_DIGITS / digitsPerSecond * 1000f).roundToInt().coerceAtLeast(16)
            while (true) {
                position.animateTo(
                    position.value + SPIN_LEG_DIGITS,
                    tween(legMillis, easing = LinearEasing),
                    block = tickFrame,
                )
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

    // Blur goes through a derived state: the block re-evaluates on each velocity
    // change but only invalidates composition when the rounded radius moves, so the
    // RenderEffect is rebuilt a handful of times per roll instead of every frame.
    val blur by remember {
        derivedStateOf {
            val s = (abs(position.velocity) / BLUR_FULL_SPEED).coerceIn(0f, 1f)
            MAX_BLUR * ((s * BLUR_QUANTISATION).roundToInt() / BLUR_QUANTISATION)
        }
    }
    // "Is this roller moving" is read only inside the layer/draw lambdas below. Reading
    // the animation state in composition would recompose and re-measure the cell on
    // every frame; inside these it only invalidates the layer/draw phase.
    val isMoving: () -> Boolean = {
        val p = position.value
        position.velocity != 0f || abs(p - floor(p)) > 0.001f
    }

    Box(
        modifier = Modifier.width(width).height(height).clipToBounds()
            .graphicsLayer {
                // Fade the top and bottom edges while moving; at rest the digit is drawn plain.
                val speed = (abs(position.velocity) / BLUR_FULL_SPEED).coerceIn(0f, 1f)
                alpha = 1f - 0.08f * speed
                // DstIn fade needs an offscreen buffer; only pay for it while moving.
                compositingStrategy = if (isMoving()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                if (isMoving()) drawRect(brush = EDGE_FADE, blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Unbounded: a blur clamped at the strip's own rectangle shows as a faint band at the cell edges.
        Box(modifier = if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier) {
            // A static 0..9 strip; the content never recomposes, only this translation
            // moves each frame. The window reaches one cell past the visible digit
            // either way so the blur has a neighbour to smear in from the edges.
            Box(
                modifier = Modifier.graphicsLayer {
                    // One full turn, then repeat: identity is periodic every 10.
                    translationY = -position.value.mod(10f) * cellHeightPx
                },
            ) {
                for (j in -1..DIGIT_STRIP_END) {
                    Text(
                        ('0' + mod10(j)).toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset(y = (j * height.value).dp),
                    )
                }
            }
        }
    }
}
