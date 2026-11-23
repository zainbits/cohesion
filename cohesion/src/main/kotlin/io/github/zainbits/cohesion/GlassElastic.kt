package io.github.zainbits.cohesion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** How far a pulled piece can stretch toward the finger, by default. */
public val DefaultMaxPull: Dp = 22.dp

/**
 * How elastic glass feels, in Apple's spring terms (bounce = 1 - damping ratio, response in
 * seconds) so values can be compared with UIKit and SwiftUI.
 *
 * Defaults are the Liquid Lens springs found in iOS 26.5 UIKitCore (`_UILiquidLensViewVariantSpec`
 * base values, per Innei's UIKitCore reverse-engineering report): lift damping 0.625 / 0.27 s,
 * unlift damping 0.7 / 0.5 s.
 *
 * @property bounce overshoot when released (0 = none).
 * @property response how long the release takes to settle, roughly.
 * @property pressBounce overshoot of the press swell.
 * @property pressResponse how quickly the press swell happens.
 * @property maxPull how far a piece can stretch toward the finger.
 * @property stretch how much a piece lengthens per unit of pull (0 = it only moves).
 * @property pressScale swell while pressed (1 = none).
 */
@Immutable
public data class GlassPullSpec(
    val bounce: Float = 0.30f,
    val response: Float = 0.50f,
    val pressBounce: Float = 0.375f,
    val pressResponse: Float = 0.27f,
    val maxPull: Dp = DefaultMaxPull,
    val stretch: Float = 0.8f,
    val pressScale: Float = 1.08f,
)

internal fun springStiffness(response: Float): Float {
    val w = (2.0 * Math.PI / response.coerceIn(0.05f, 3f)).toFloat()
    return w * w
}

internal fun dampingOf(bounce: Float): Float = (1f - bounce).coerceIn(0.1f, 1f)

/** The pull feel elastic glass pieces use when none is passed explicitly. */
public val LocalGlassPullSpec: ProvidableCompositionLocal<GlassPullSpec> = compositionLocalOf { GlassPullSpec() }

/**
 * The elastic pull of one glass piece: how far the finger has dragged it (raw, unbounded) and
 * how much it swells while pressed. Both spring back on release with [spec].
 *
 * Only the group's shader numbers change while it moves; nothing is recomposed or re-recorded.
 */
@Stable
public class GlassPullState internal constructor(internal val scope: CoroutineScope, spec: GlassPullSpec) {
    internal val pull = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val press = Animatable(1f)

    /** The feel; changes apply from the next press. */
    public var spec: GlassPullSpec = spec
        internal set

    /** Finger drag in pixels (raw). Read it only in draw or layer blocks. */
    public val offset: Offset get() = pull.value

    /** Whether the glass piece is a circle; set by whoever draws it, read by [glassPullContent]. */
    public var round: Boolean = true

    /** Press swell (1 = rest). Read it only in draw or layer blocks. */
    public val scale: Float get() = press.value

    /**
     * How far the piece is lifted by the press, 0 (rest) to about 1 (fully pressed; a bouncy spring overshoots
     * a little): the glass bends more and its rim and the spot under the finger light up. Read it in draw.
     */
    public val lift: Float
        get() {
            val full = spec.pressScale - 1f
            return if (full <= 0.0001f) 0f else ((press.value - 1f) / full).coerceIn(0f, 1.2f)
        }

    /** Where the finger is on the piece (its own pixels), for the light under it. Read it in draw. */
    public var touch: Offset by mutableStateOf(Offset.Unspecified)
        internal set

    /** The pointer a [glassPullExempt] control inside the piece took; the piece ignores that gesture. */
    internal var exempt: PointerId? = null

    /** The pointer that went down in a [glassPullNoTap] zone: it pulls the glass but is not a tap. */
    internal var noTap: PointerId? = null

    internal fun down(at: Offset) {
        touch = at
        scope.launch { press.animateTo(spec.pressScale, pressSpring()) }
    }

    internal fun drag(to: Offset, at: Offset) {
        touch = at
        scope.launch { pull.snapTo(to) }
    }

    /**
     * Springs back. [velocity] (pixels per second) is how fast the finger was moving when it let go: the
     * stretch keeps that speed and swings on with it before settling, like a real elastic thrown loose.
     */
    internal fun release(velocity: Offset = Offset.Zero) {
        // Unlift: the swell goes back with the release spring, like UIKit's unlift.
        scope.launch {
            press.animateTo(1f, spring(dampingRatio = dampingOf(spec.bounce), stiffness = springStiffness(spec.response)))
        }
        val v = velocity.getDistance().let { len -> if (len > MAX_RELEASE_SPEED) velocity * (MAX_RELEASE_SPEED / len) else velocity }
        scope.launch {
            pull.animateTo(
                Offset.Zero,
                spring(dampingRatio = dampingOf(spec.bounce), stiffness = springStiffness(spec.response)),
                initialVelocity = v,
            )
        }
    }

    private companion object {
        /** Fastest release carried into the spring (px/s), so a hard flick does not throw the shape off. */
        const val MAX_RELEASE_SPEED = 6000f
    }

    private fun pressSpring() = spring<Float>(
        dampingRatio = dampingOf(spec.pressBounce),
        stiffness = springStiffness(spec.pressResponse),
    )
}

@Composable
public fun rememberGlassPullState(spec: GlassPullSpec = LocalGlassPullSpec.current): GlassPullState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { GlassPullState(scope, spec) }
    state.spec = spec
    return state
}

/**
 * Lets the finger pull this element: press swells it, dragging stretches it toward the finger
 * (with rubber-band resistance), release springs it back. A press that ends without leaving the
 * element (beyond the touch slop) counts as a tap and calls [onTap].
 *
 * @param onLongPress called when the press is held still for the long-press timeout (a menu); the glass
 *   springs back and the release is then not a tap.
 * @param scrollAxis the element sits in a list that scrolls along this axis: a drag that starts along it
 *   belongs to the list (the glass springs back, no tap); a drag across it still stretches the glass.
 */
public fun Modifier.glassPullGesture(
    state: GlassPullState,
    enabled: Boolean = true,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    scrollAxis: Orientation? = null,
): Modifier = if (!enabled) this else this.pointerInput(state, onTap, onLongPress, scrollAxis) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A control inside the piece took this touch (marked with glassPullExempt, or it consumed the
        // press): the glass neither swells, stretches nor taps. Nested pointer input sees the event first.
        if (down.isConsumed || state.exempt == down.id) return@awaitEachGesture
        state.down(down.position)
        // The finger's speed when it lets go is carried into the spring back.
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var moved = false
        var last = down.position
        // A child that takes the gesture (a scrolling row, a slider, a button's tap) keeps it: no pull, no tap.
        var childOwns = false
        while (true) {
            val event = if (onLongPress != null && !moved) {
                val wait = longPressAt - (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis)
                withTimeoutOrNull(wait.coerceAtLeast(0L)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                // Held still: a long press. The rest of this touch is not a tap or a pull.
                state.release()
                onLongPress?.invoke()
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                last = change.position
                if (change.isConsumed) childOwns = true
                break
            }
            last = change.position
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!moved && change.isConsumed) {
                childOwns = true
                break
            }
            val delta = change.position - down.position
            if (!moved && delta.getDistance() > slop) {
                val along = when (scrollAxis) {
                    Orientation.Horizontal -> abs(delta.x) > abs(delta.y)
                    Orientation.Vertical -> abs(delta.y) > abs(delta.x)
                    null -> false
                }
                // A drag along the list's axis scrolls the list: leave it unconsumed for the list.
                if (along) {
                    childOwns = true
                    break
                }
                moved = true
            }
            if (moved) {
                state.drag(delta, change.position)
                change.consume()
            }
        }
        val inside = last.x >= -slop && last.y >= -slop &&
            last.x <= size.width + slop && last.y <= size.height + slop
        val speed = if (moved && !childOwns) tracker.calculateVelocity().let { Offset(it.x, it.y) } else Offset.Zero
        state.release(speed)
        if (onTap != null && inside && !childOwns && state.noTap != down.id) onTap()
    }
}

/**
 * Marks a control inside elastic glass (a slider, a row of cards, a button) whose touches belong to it:
 * pressing, scrolling or dragging it leaves the glass still (no swell, no stretch, no tap on the glass).
 * Touches elsewhere on the glass still pull it.
 */
public fun Modifier.glassPullExempt(state: GlassPullState?): Modifier = if (state == null) this else this.pointerInput(state) {
    awaitEachGesture {
        // Main pass runs from the innermost element out, so this is seen before the glass's own gesture.
        state.exempt = awaitFirstDown(requireUnconsumed = false).id
    }
}

/**
 * Marks a passive zone inside elastic glass (the empty part of an open panel): pressing and dragging it
 * still swells and stretches the glass, but releasing there is not a tap on the glass.
 */
public fun Modifier.glassPullNoTap(state: GlassPullState?): Modifier = if (state == null) this else this.pointerInput(state) {
    awaitEachGesture {
        state.noTap = awaitFirstDown(requireUnconsumed = false).id
    }
}

/**
 * Turns a raw drag into the piece's deformation. The stretch saturates at [maxPx] (rubber band),
 * the piece lengthens toward the finger while its far side stays almost anchored, and it thins
 * across the drag so it keeps roughly its area.
 */
/**
 * Turns a raw drag into a glass piece's deformation (shift, stretch direction, stretch, squash);
 * the same numbers drive the shape (shader) and its content ([glassPullContent]).
 */
public class ElasticDeform {
    public var shiftX: Float = 0f
    public var shiftY: Float = 0f
    public var ux: Float = 1f
    public var uy: Float = 0f
    public var stretch: Float = 1f
    public var squash: Float = 1f

    /**
     * @param round a circle stretches along the drag in any direction; every other shape (a
     *   capsule, a rounded panel) stretches only along its own x and y axes, like on iOS.
     */
    public fun compute(pull: Offset, w: Float, h: Float, maxPx: Float, stretchFactor: Float, round: Boolean) {
        val len = pull.getDistance()
        if (len < 0.01f || maxPx <= 0f) {
            shiftX = 0f; shiftY = 0f; ux = 1f; uy = 0f; stretch = 1f; squash = 1f
            return
        }
        val dx = pull.x / len
        val dy = pull.y / len
        val e = maxPx * (1f - exp(-len / maxPx))
        shiftX = dx * e * 0.5f
        shiftY = dy * e * 0.5f
        if (round) {
            ux = dx
            uy = dy
            stretch = 1f + stretchFactor * e / (abs(dx) * w + abs(dy) * h).coerceAtLeast(1f)
            squash = 1f / sqrt(stretch)
        } else {
            // Axis-aligned: lengthen along x by the drag's x part and along y by its y part,
            // each thinning the other axis a little so the piece keeps roughly its area.
            val sx = 1f + stretchFactor * abs(dx) * e / w.coerceAtLeast(1f)
            val sy = 1f + stretchFactor * abs(dy) * e / h.coerceAtLeast(1f)
            ux = 1f
            uy = 0f
            stretch = sx / sqrt(sy)
            squash = sy / sqrt(sx)
        }
    }

    public companion object {
        /** A circle (or close enough): square, with corners at least half its side. */
        public fun isRound(w: Float, h: Float, radius: Float): Boolean =
            abs(w - h) < 0.5f && radius >= minOf(w, h) / 2f - 0.5f
    }
}

/**
 * Makes content ride with its elastic glass: the same shift, stretch, squash and press swell the
 * glass gets, so text and icons never spill past the edge. Apply it to a box the same size as the
 * glass piece. It is a layer transform, so following the finger costs no recomposition or redraw.
 */
public fun Modifier.glassPullContent(state: GlassPullState): Modifier {
    val elastic = ElasticDeform()
    return this.graphicsLayer {
        val k = state.scale
        val w = size.width * k
        val h = size.height * k
        elastic.compute(state.offset, w, h, state.spec.maxPull.toPx(), state.spec.stretch, round = state.round)
        // The glass stretches along the drag; a layer only scales along x and y, so project it.
        val ux2 = elastic.ux * elastic.ux
        val uy2 = elastic.uy * elastic.uy
        scaleX = k * (elastic.stretch * ux2 + elastic.squash * uy2)
        scaleY = k * (elastic.stretch * uy2 + elastic.squash * ux2)
        translationX = elastic.shiftX
        translationY = elastic.shiftY
    }
}
