package io.github.zainbits.cohesion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The content that glass surfaces show through them.
 *
 * Mark the content once with [glassSource]; any number of [glass] surfaces placed *outside*
 * that content (siblings drawn on top of it) then sample it. The content is recorded into one
 * GPU display list per frame and that same list is both shown on screen and replayed by every
 * glass surface, so the content is never drawn twice on the UI thread.
 */
@Stable
public class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
    private val changed = MutableStateFlow(0L)
    private val scrolled = MutableStateFlow(0L)

    /**
     * Ticks when what the glass shows may have changed: the content was recorded again, a fling ended, or
     * [notifyChanged]. Adaptive text colour ([GlassToneProbe]) reads the content once these settle.
     */
    public val changes: StateFlow<Long> get() = changed

    /** Ticks while something inside the content scrolls under the finger; readings wait until it stops. */
    public val scrolls: StateFlow<Long> get() = scrolled

    /**
     * Tells adaptive glass that the content behind it changed in a way the source cannot see, such as
     * scrolling done in code (`animateScrollToItem`).
     */
    public fun notifyChanged() {
        changed.value++
    }

    internal val scrollSignal = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed != Offset.Zero) scrolled.value++
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            notifyChanged()
            return Velocity.Zero
        }
    }
}

/** Creates a [GlassBackdrop] owned by the current composition. */
@Composable
public fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Records this content into [backdrop] so glass surfaces can show it.
 *
 * A glass surface must not be inside the content it samples (it would sample itself); put it
 * in a sibling drawn after this one, for example a later child of the same `Box`.
 */
public fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    // Own layer first, so redraws of unrelated siblings (a clock, a counter) do not make the
    // source record its content again.
    // The nested-scroll hook sees any scrolling inside the content, for adaptive text colour.
    this.graphicsLayer().nestedScroll(backdrop.scrollSignal) then GlassSourceElement(backdrop)

private class GlassSourceElement(val backdrop: GlassBackdrop) :
    ModifierNodeElement<GlassSourceNode>() {
    override fun create(): GlassSourceNode = GlassSourceNode(backdrop)

    override fun update(node: GlassSourceNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop = backdrop
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassSource"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean =
        other is GlassSourceElement && other.backdrop === backdrop

    override fun hashCode(): Int = System.identityHashCode(backdrop)
}

private class GlassSourceNode(var backdrop: GlassBackdrop) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {

    override fun ContentDrawScope.draw() {
        val layer = backdrop.layer
        // Record once, show the recording. Glass surfaces replay the same display list.
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
        CohesionDebug.sourceRecords++
        backdrop.notifyChanged()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        backdrop.coordinates = coordinates
    }

    override fun onDetach() {
        backdrop.coordinates = null
    }
}

/** Where [coordinates] sit inside this backdrop's content, or null before both are placed. */
public fun GlassBackdrop.positionOf(coordinates: LayoutCoordinates): androidx.compose.ui.geometry.Offset? {
    val src = this.coordinates ?: return null
    if (!src.isAttached || !coordinates.isAttached) return null
    return src.localPositionOf(coordinates, androidx.compose.ui.geometry.Offset.Zero)
}
