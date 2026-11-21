package io.github.zainbits.cohesion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

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
 *
 * Layers: with [under], the part of that backdrop behind this content (clipped to this content's
 * bounds) is recorded first, then the content on top. Glass surfaces inside this content can be glass
 * over [under] (tabs over a page), and glass over this backdrop then shows both (a button the tabs
 * scroll under). [under] is drawn by reference, so it stays live without this content recording again.
 */
public fun Modifier.glassSource(backdrop: GlassBackdrop, under: GlassBackdrop? = null): Modifier =
    // Own layer first, so redraws of unrelated siblings (a clock, a counter) do not make the
    // source record its content again.
    this.graphicsLayer() then GlassSourceElement(backdrop, under)

private class GlassSourceElement(val backdrop: GlassBackdrop, val under: GlassBackdrop?) :
    ModifierNodeElement<GlassSourceNode>() {
    override fun create(): GlassSourceNode = GlassSourceNode(backdrop, under)

    override fun update(node: GlassSourceNode) {
        if (node.backdrop !== backdrop || node.under !== under) {
            node.backdrop = backdrop
            node.under = under
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassSource"
        properties["backdrop"] = backdrop
        properties["under"] = under
    }

    override fun equals(other: Any?): Boolean =
        other is GlassSourceElement && other.backdrop === backdrop && other.under === under

    override fun hashCode(): Int = 31 * System.identityHashCode(backdrop) + System.identityHashCode(under)
}

private class GlassSourceNode(var backdrop: GlassBackdrop, var under: GlassBackdrop?) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {

    private var placed: LayoutCoordinates? = null
    private var placedAt = androidx.compose.ui.geometry.Offset.Unspecified

    override fun ContentDrawScope.draw() {
        val layer = backdrop.layer
        val below = under
        val at = if (below != null) placed?.let { below.positionOf(it) } else null
        // Record once, show the recording. Glass surfaces replay the same display list.
        layer.record {
            if (below != null && at != null) {
                clipRect { translate(-at.x, -at.y) { drawLayer(below.layer) } }
            }
            this@draw.drawContent()
        }
        drawLayer(layer)
        CohesionDebug.sourceRecords++
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        backdrop.coordinates = coordinates
        placed = coordinates
        // Moved: the part of [under] behind this content changed, record again.
        val at = coordinates.positionInRoot()
        if (under != null && at != placedAt) invalidateDraw()
        placedAt = at
    }

    override fun onDetach() {
        backdrop.coordinates = null
        placed = null
        placedAt = androidx.compose.ui.geometry.Offset.Unspecified
    }
}

/** Where [coordinates] sit inside this backdrop's content, or null before both are placed. */
public fun GlassBackdrop.positionOf(coordinates: LayoutCoordinates): androidx.compose.ui.geometry.Offset? {
    val src = this.coordinates ?: return null
    if (!src.isAttached || !coordinates.isAttached) return null
    return src.localPositionOf(coordinates, androidx.compose.ui.geometry.Offset.Zero)
}
