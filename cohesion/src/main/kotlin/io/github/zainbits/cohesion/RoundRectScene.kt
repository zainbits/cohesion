package io.github.zainbits.cohesion

import android.graphics.RuntimeShader

/**
 * Up to [MAX] rounded rectangles drawn as one glass pass, each optionally stretched (elastic pull),
 * joined by a smooth union so pieces closer than [merge] pixels flow together.
 *
 * Fill it before each draw with [clear] and [add]; the scene bumps its version only when something
 * actually changed, so an unchanged frame rebuilds nothing.
 */
public class RoundRectScene : GlassScene() {
    private val rects = FloatArray(MAX * 4)
    private val radii = FloatArray(MAX)
    private val deform = FloatArray(MAX * 4)
    private val lifts = FloatArray(MAX)
    private var count = 0
    private var bandPx = 1f
    private var mergePx = 0f

    // What was last uploaded, to detect changes.
    private val lastRects = FloatArray(MAX * 4)
    private val lastRadii = FloatArray(MAX)
    private val lastDeform = FloatArray(MAX * 4)
    private val lastLifts = FloatArray(MAX)
    private var lastCount = -1
    private var lastBand = -1f
    private var lastMerge = -1f

    init {
        identity(deform)
    }

    /** Shapes added since the last [clear]. */
    public val size: Int get() = count

    /** Starts a new frame of shapes with lens band and merge distance, both in pixels. */
    public fun clear(bandPx: Float, mergePx: Float) {
        count = 0
        this.bandPx = bandPx.coerceAtLeast(1f)
        this.mergePx = mergePx.coerceAtLeast(0f)
        identity(deform)
        lifts.fill(0f)
    }

    /**
     * Adds a rectangle at (x, y) of size (w, h) with corner [radius], in the element's pixels,
     * stretched by [stretch] along (ux, uy) and by [squash] across it, lifted by [lift] (a press:
     * [GlassPullState.lift]). Ignored past [MAX].
     */
    public fun add(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        ux: Float = 1f, uy: Float = 0f, stretch: Float = 1f, squash: Float = 1f,
        lift: Float = 0f,
    ) {
        if (count >= MAX || w <= 0f || h <= 0f) return
        val i = count * 4
        rects[i] = x
        rects[i + 1] = y
        rects[i + 2] = w
        rects[i + 3] = h
        radii[count] = minOf(radius, minOf(w, h) / 2f)
        deform[i] = ux
        deform[i + 1] = uy
        deform[i + 2] = stretch
        deform[i + 3] = squash
        lifts[count] = lift
        count++
    }

    /** Call after the last [add] of a frame: bumps the version if anything changed. */
    public fun commit() {
        if (count == lastCount && bandPx == lastBand && mergePx == lastMerge && same()) return
        rects.copyInto(lastRects)
        radii.copyInto(lastRadii)
        deform.copyInto(lastDeform)
        lifts.copyInto(lastLifts)
        lastCount = count
        lastBand = bandPx
        lastMerge = mergePx
        version++
    }

    /** Rectangle [i] for drawing it without the shader (x, y, w, h, radius). */
    public fun rect(i: Int, out: FloatArray) {
        out[0] = rects[i * 4]; out[1] = rects[i * 4 + 1]; out[2] = rects[i * 4 + 2]; out[3] = rects[i * 4 + 3]
        out[4] = radii[i]
    }

    private fun same(): Boolean {
        for (i in 0 until count * 4) {
            if (rects[i] != lastRects[i] || deform[i] != lastDeform[i]) return false
        }
        for (i in 0 until count) if (radii[i] != lastRadii[i] || lifts[i] != lastLifts[i]) return false
        return true
    }

    override val hasLift: Boolean get() = true

    override val agsl: String = """
uniform float uCount;
uniform float uLift[$MAX];
uniform float uLiftMax;
uniform float uRects[${MAX * 4}];
uniform float uRadii[$MAX];
uniform float uDeform[${MAX * 4}];
uniform float uMerge;
uniform float uShapeBand;
${CohesionAgsl.ROUND_RECT}
${CohesionAgsl.SMOOTH_UNION}
float3 scene(float2 p) {
    float3 acc = float3(100000.0, 0.0, -1.0);
    for (int i = 0; i < $MAX; i++) {
        if (float(i) < uCount) {
            float3 s = sdRoundRect(p, uRects[i * 4], uRects[i * 4 + 1], uRects[i * 4 + 2], uRects[i * 4 + 3],
                uRadii[i], uShapeBand, uDeform[i * 4], uDeform[i * 4 + 1], uDeform[i * 4 + 2], uDeform[i * 4 + 3]);
            acc = smoothUnion(acc, s, uMerge);
        }
    }
    return acc;
}
// The lift of the piece p is in (or nearest to). Nothing to look up while no piece is pressed.
half sceneLift(float2 p) {
    if (uLiftMax <= 0.0) {
        return 0.0;
    }
    float best = 100000.0;
    float l = 0.0;
    for (int i = 0; i < $MAX; i++) {
        if (float(i) < uCount) {
            float3 s = sdRoundRect(p, uRects[i * 4], uRects[i * 4 + 1], uRects[i * 4 + 2], uRects[i * 4 + 3],
                uRadii[i], uShapeBand, uDeform[i * 4], uDeform[i * 4 + 1], uDeform[i * 4 + 2], uDeform[i * 4 + 3]);
            if (s.x < best) {
                best = s.x;
                l = uLift[i];
            }
        }
    }
    return half(l);
}
"""

    override fun applyUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uCount", count.toFloat())
        shader.setFloatUniform("uRects", rects)
        shader.setFloatUniform("uRadii", radii)
        shader.setFloatUniform("uDeform", deform)
        shader.setFloatUniform("uLift", lifts)
        var most = 0f
        for (i in 0 until count) if (lifts[i] > most) most = lifts[i]
        shader.setFloatUniform("uLiftMax", most)
        shader.setFloatUniform("uMerge", mergePx)
        shader.setFloatUniform("uShapeBand", bandPx)
    }

    public companion object {
        /** Most rectangles one pass draws. */
        public const val MAX: Int = 8

        private fun identity(d: FloatArray) {
            for (i in 0 until MAX) {
                d[i * 4] = 1f
                d[i * 4 + 1] = 0f
                d[i * 4 + 2] = 1f
                d[i * 4 + 3] = 1f
            }
        }
    }
}
