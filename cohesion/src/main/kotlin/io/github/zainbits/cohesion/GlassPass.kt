package io.github.zainbits.cohesion

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Trace
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * One offscreen glass pass for any [GlassScene]: the backdrop's existing display list replayed under
 * the element, blurred, then shaded once for the whole scene (lens, colour boost, tint, lit rim).
 *
 * It re-records only when the element moves or resizes and rebuilds its effect only when the look
 * or the scene changes; when the content behind scrolls, the render thread refreshes the pass with
 * no UI-thread work. In [GlassMode.Solid] it draws the scene flat, with no offscreen pass at all.
 *
 * Owners call [attach]/[detach] from their node's lifecycle and [draw] from its draw.
 */
public class GlassPass(private val scene: GlassScene) {
    private var attached = false
    private var layer: GraphicsLayer? = null
    private var blurLayer: GraphicsLayer? = null

    private var effectVersion = -1
    private var effectSize = IntSize.Zero
    private var effectDensity = 0f
    private var effectStyle: GlassStyle? = null
    private var effectMode: GlassMode? = null
    private var effectDs = 1
    private var effectTaps = false
    private var effectMargin = 0
    private var effectTouch = Offset.Unspecified
    private var effectSource = IntSize.Zero
    private var effectOrigin = Offset.Unspecified

    private var recordedOrigin = Offset.Unspecified
    private var recordedSize = IntSize.Zero
    private var recordedSource: GraphicsLayer? = null

    /** The offscreen layer is created on the first glass draw, so a pass that only ever draws Solid holds none. */
    public fun attach(context: GraphicsContext) {
        attached = true
    }

    public fun detach(context: GraphicsContext) {
        layer?.let { context.releaseGraphicsLayer(it) }
        blurLayer?.let { context.releaseGraphicsLayer(it) }
        layer = null
        blurLayer = null
        attached = false
        effectVersion = -1
        effectStyle = null
        recordedOrigin = Offset.Unspecified
        recordedSource = null
    }

    /**
     * Draws the scene as glass over [backdrop], whose content sits at [offset] relative to this
     * element ([GlassBackdrop.positionOf]). [margin] lets the pass extend past the element so a
     * stretched shape is not cut off; the pass always reaches a little past the shape anyway, so the edge
     * reflects and blurs what is really around it and the shadow has room. [touch] is where a finger
     * presses (this element's pixels; the scene's lift says which piece), for the light under it.
     */
    public fun DrawScope.draw(
        context: GraphicsContext,
        backdrop: GlassBackdrop,
        offset: Offset,
        style: GlassStyle,
        mode: GlassMode,
        margin: Dp = 0.dp,
        touch: Offset = Offset.Unspecified,
    ) {
        if (mode == GlassMode.Solid || !attached || offset == Offset.Unspecified) {
            drawSolid(style, margin)
            return
        }
        val layer = this@GlassPass.layer ?: context.createGraphicsLayer().also {
            it.clip = true
            this@GlassPass.layer = it
        }
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection("Cohesion#glass")
        try {
            drawPass(context, backdrop.layer, backdrop.size, offset, style, mode, margin, touch, layer)
        } finally {
            if (tracing) Trace.endSection()
        }
    }

    private fun DrawScope.drawPass(
        context: GraphicsContext,
        source: GraphicsLayer,
        sourceSize: IntSize,
        offset: Offset,
        style: GlassStyle,
        mode: GlassMode,
        margin: Dp,
        touch: Offset,
        layer: GraphicsLayer,
    ) {
        val blurPx = if (mode == GlassMode.NoBlur) 0f else style.blurRadius.toPx()
        val m = ceil(maxOf(margin.toPx(), reach(style, blurPx))).toInt()
        val mf = m.toFloat()
        val intSize = IntSize(size.width.toInt() + 2 * m, size.height.toInt() + 2 * m)
        val origin = Offset(offset.x - mf, offset.y - mf)
        val taps = blurPx >= 0.5f && CohesionDebug.blurMethod == 1
        val ds = when {
            blurPx < 0.5f -> 1
            CohesionDebug.blurDownsample > 0 -> CohesionDebug.blurDownsample
            taps -> ceil(blurPx / CohesionDebug.blurSpanPx).toInt().coerceIn(1, 8)
            else -> 1
        }
        if (scene.version != effectVersion || intSize != effectSize || density != effectDensity ||
            style != effectStyle || mode != effectMode || ds != effectDs || taps != effectTaps || m != effectMargin ||
            touch != effectTouch || sourceSize != effectSource || origin != effectOrigin
        ) {
            val shader = Shaders.glass(scene)
            applyGlassUniforms(shader, intSize, mf, style, if (taps) blurPx else 0f)
            // Where the content ends, in the pass's pixels: samples stay inside it (past it there is nothing).
            if (sourceSize.width > 0 && sourceSize.height > 0) {
                shader.setFloatUniform("uGSrcMin", -origin.x, -origin.y)
                shader.setFloatUniform("uGSrcMax", sourceSize.width - origin.x, sourceSize.height - origin.y)
            } else {
                shader.setFloatUniform("uGSrcMin", -1e6f, -1e6f)
                shader.setFloatUniform("uGSrcMax", 1e6f, 1e6f)
            }
            if (touch != Offset.Unspecified) shader.setFloatUniform("uGTouch", touch.x, touch.y) else shader.setFloatUniform("uGTouch", -1e6f, -1e6f)
            shader.setFloatUniform("uGTouchR", TOUCH_GLOW_RADIUS.toPx())
            scene.applyUniforms(shader)
            CohesionDebug.effectBuilds++
            val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val combined = if (blurPx < 0.5f || taps || ds > 1) {
                lens
            } else {
                RenderEffect.createChainEffect(lens, RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP))
            }
            if (ds > 1) {
                val bl = blurLayer ?: context.createGraphicsLayer().also {
                    it.clip = true
                    blurLayer = it
                }
                if (taps) {
                    // Plain low-resolution copy; the shader's taps do the blur.
                    bl.renderEffect = null
                    bl.compositingStrategy = CompositingStrategy.Offscreen
                } else {
                    bl.compositingStrategy = CompositingStrategy.Auto
                    bl.renderEffect = RenderEffect.createBlurEffect(blurPx / ds, blurPx / ds, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                }
            }
            layer.renderEffect = combined.asComposeRenderEffect()
            if (ds != effectDs || taps != effectTaps) recordedSize = IntSize.Zero // layer structure changed
            effectVersion = scene.version
            effectSize = intSize
            effectDensity = density
            effectStyle = style
            effectMode = mode
            effectDs = ds
            effectTaps = taps
            effectMargin = m
            effectTouch = touch
            effectSource = sourceSize
            effectOrigin = origin
        }

        if (origin != recordedOrigin || intSize != recordedSize || source !== recordedSource) {
            val bl = blurLayer
            if (ds > 1 && bl != null) {
                val small = IntSize((intSize.width + ds - 1) / ds, (intSize.height + ds - 1) / ds)
                val inv = 1f / ds
                bl.record(small) {
                    scale(inv, inv, pivot = Offset.Zero) {
                        translate(-origin.x, -origin.y) { drawLayer(source) }
                    }
                }
                layer.record(intSize) {
                    scale(ds.toFloat(), ds.toFloat(), pivot = Offset.Zero) { drawLayer(bl) }
                }
            } else {
                layer.record(intSize) {
                    translate(-origin.x, -origin.y) { drawLayer(source) }
                }
            }
            recordedOrigin = origin
            recordedSize = intSize
            recordedSource = source
            CohesionDebug.glassRecords++
        }
        if (m == 0) drawLayer(layer) else translate(-mf, -mf) { drawLayer(layer) }
    }

    /**
     * Draws the scene as a flat shape in [GlassStyle.solidColor] (the accent in [GlassStyle.accentSolidColor]):
     * no backdrop and no offscreen pass. The scene's merging and stretching still apply, so pieces with no glass
     * behind them can still flow into each other like liquid. [draw] uses it in [GlassMode.Solid].
     */
    public fun DrawScope.drawSolid(style: GlassStyle, margin: Dp = 0.dp) {
        val shader = Shaders.solid(scene)
        shader.setFloatUniform("uGShadow", style.shadow.coerceIn(0f, 1f))
        shader.setFloatUniform("uGShadowR", style.shadowRadius.toPx().coerceAtLeast(1f))
        shader.setFloatUniform("uGShadowDy", style.shadowOffset.toPx())
        shader.setColorUniform("uGSolid", style.solidColor.toArgb())
        val accent = if (style.accentSolidColor.isSpecified) style.accentSolidColor else style.solidColor
        shader.setColorUniform("uGSolid2", accent.toArgb())
        setEdgeUniforms(shader, style)
        scene.applyUniforms(shader)
        val brush = solidBrush?.takeIf { solidShader === shader } ?: ShaderBrush(shader).also {
            solidBrush = it
            solidShader = shader
        }
        // Room for the shadow past the shape.
        val m = maxOf(margin.toPx(), shadowReach(style))
        drawRect(brush, topLeft = Offset(-m, -m), size = Size(size.width + 2 * m, size.height + 2 * m))
    }

    /** The edge shading: its colour, width, and the light it turns with (a light edge faces it, a dark one turns away). */
    private fun DrawScope.setEdgeUniforms(shader: RuntimeShader, style: GlassStyle) {
        shader.setColorUniform("uGEdge", style.edge.copy(alpha = style.edgeAlpha.coerceIn(0f, 1f)).toArgb())
        shader.setFloatUniform("uGEdgeWidth", style.edgeWidth.toPx().coerceAtLeast(0.5f))
        val a = Math.toRadians(style.lightAngle.toDouble())
        val toward = if (style.edge.luminance() > 0.5f) 1f else -1f
        shader.setFloatUniform("uGEdgeLight", toward * cos(a).toFloat(), toward * sin(a).toFloat())
        shader.setFloatUniform("uGEven", style.evenLight.coerceIn(0f, 1f))
    }

    /**
     * How far past the shape the pass must reach (px): the edge reflection looks out about five edge widths,
     * the shadow [shadowReach], and the blur needs content beyond both. At most [MAX_REACH].
     */
    private fun DrawScope.reach(style: GlassStyle, blurPx: Float): Float {
        val look = maxOf(style.edgeWidth.toPx() * 5f, shadowReach(style))
        return minOf(look + blurPx + 1f, MAX_REACH.toPx())
    }

    /** How far the shadow reaches past the shape: half its penumbra past its outline, which sits [GlassStyle.shadowOffset] down. */
    private fun DrawScope.shadowReach(style: GlassStyle): Float =
        if (style.shadow > 0f) style.shadowRadius.toPx() / 2f + style.shadowOffset.toPx() + 1f else 0f

    private var solidBrush: ShaderBrush? = null
    private var solidShader: RuntimeShader? = null

    private fun DrawScope.applyGlassUniforms(shader: RuntimeShader, size: IntSize, origin: Float, style: GlassStyle, tapBlur: Float) {
        shader.setFloatUniform("uGSize", size.width.toFloat(), size.height.toFloat())
        shader.setFloatUniform("uGOrigin", origin, origin)
        shader.setFloatUniform("uGBand", style.refractionBand.toPx().coerceAtLeast(1f))
        shader.setFloatUniform("uGDepth", style.refractionDepth.toPx())
        shader.setFloatUniform("uGSaturation", style.saturation)
        shader.setColorUniform("uGTint", style.tint.copy(alpha = style.tintAlpha.coerceIn(0f, 1f)).toArgb())
        val accent: Color = if (style.accentTint.isSpecified) {
            style.accentTint.copy(alpha = style.accentTintAlpha.coerceIn(0f, 1f))
        } else {
            style.tint.copy(alpha = style.tintAlpha.coerceIn(0f, 1f))
        }
        shader.setColorUniform("uGTint2", accent.toArgb())
        shader.setFloatUniform("uGRim", style.rim)
        shader.setFloatUniform("uGRimWidth", style.rimWidth.toPx().coerceAtLeast(0.5f))
        val a = Math.toRadians(style.lightAngle.toDouble())
        shader.setFloatUniform("uGLight", cos(a).toFloat(), sin(a).toFloat())
        shader.setFloatUniform("uGDispersion", style.dispersion)
        setEdgeUniforms(shader, style)
        shader.setFloatUniform("uGReflect", style.reflection.coerceIn(0f, 1f))
        // Far lights spread the corner highlights along the whole edge; near ones keep them on the corners.
        val near = 1f - style.lightDistance.coerceIn(0f, 1f)
        shader.setFloatUniform("uGCornerK", 4f * near * near)
        shader.setFloatUniform("uGBlur", tapBlur)
        shader.setFloatUniform("uGTaps", CohesionDebug.blurTaps.coerceIn(4, 32).toFloat())
        shader.setFloatUniform("uGShadow", style.shadow.coerceIn(0f, 1f))
        shader.setFloatUniform("uGShadowR", style.shadowRadius.toPx().coerceAtLeast(1f))
        shader.setFloatUniform("uGShadowDy", style.shadowOffset.toPx())
    }

    private companion object {
        /** Size of the light under a pressing finger. */
        val TOUCH_GLOW_RADIUS = 48.dp

        /** Furthest the pass reaches past its shape. */
        val MAX_REACH = 48.dp
    }
}

/** Compiled shaders, one glass and one solid variant per scene source, shared by every pass. */
private object Shaders {
    private val glass = HashMap<String, RuntimeShader>()
    private val solid = HashMap<String, RuntimeShader>()

    fun glass(scene: GlassScene): RuntimeShader = glass.getOrPut(scene.agsl) {
        RuntimeShader(GLASS_HEADER + scene.agsl + accentDefault(scene) + liftDefault(scene) + GLASS_MAIN)
    }

    fun solid(scene: GlassScene): RuntimeShader = solid.getOrPut(scene.agsl) {
        RuntimeShader(SOLID_HEADER + scene.agsl + accentDefault(scene) + SOLID_MAIN)
    }

    private fun accentDefault(scene: GlassScene) =
        if (scene.hasAccent) "" else "\nhalf sceneAccent(float2 p) { return 0.0; }\n"

    private fun liftDefault(scene: GlassScene) =
        if (scene.hasLift) "" else "\nhalf sceneLift(float2 p) { return 0.0; }\n"
}

private const val GLASS_HEADER = """
uniform shader content;
uniform float2 uGSize;
uniform float2 uGOrigin;
uniform float uGBand;
uniform float uGDepth;
uniform float uGSaturation;
layout(color) uniform half4 uGTint;
layout(color) uniform half4 uGTint2;
uniform float uGRim;
uniform float uGRimWidth;
uniform float2 uGLight;
uniform float uGDispersion;
layout(color) uniform half4 uGEdge;
uniform float uGEdgeWidth;
uniform float2 uGEdgeLight;
uniform float uGEven;
uniform float uGReflect;
uniform float uGCornerK;
uniform float uGBlur;
uniform float uGTaps;
uniform float2 uGSrcMin;
uniform float2 uGSrcMax;
uniform float2 uGTouch;
uniform float uGTouchR;
uniform float uGShadow;
uniform float uGShadowR;
uniform float uGShadowDy;

// Inside the pass, and inside the content it shows (past the content's edge there is nothing to see).
float2 clampIn(float2 s) {
    return clamp(s, max(float2(0.5), uGSrcMin + 0.5), min(uGSize - 0.5, uGSrcMax - 0.5));
}

// The soft shadow the glass casts on the content below it. d: distance out from the shape (n its outward
// normal), xy for the dither.
// - Light from above shifts the shadow down by uGShadowDy: the shape moved down by dy is nearer below it and
//   further above it (to first order, d - dy * n.y).
// - A soft (area) light gives a penumbra centred on the shadow's outline: half strength there, rising under the
//   shape, falling to exactly nothing uGShadowR / 2 outside it, with zero slope at both ends (smoothstep, close
//   to the error function of a real penumbra), so the shadow has no visible end.
// - A dither of under one colour level per pixel keeps such a faint gradient from banding into rings.
half4 shadowAt(float d, float2 n, float2 xy) {
    if (uGShadow <= 0.0) {
        return half4(0.0);
    }
    float x = clamp((d - uGShadowDy * n.y) / uGShadowR + 0.5, 0.0, 1.0);
    float f = 1.0 - x * x * (3.0 - 2.0 * x);
    if (f <= 0.0) {
        return half4(0.0);
    }
    float h = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);
    float a = max(uGShadow * f + (h - 0.5) / 255.0, 0.0);
    return half4(0.0, 0.0, 0.0, half(a));
}

// Optional single-pass blur (CohesionDebug.blurMethod = 1): Vogel-disk taps with Gaussian weights
// over a small, already smooth copy. Off (uGBlur = 0) when the platform blur is used.
half4 blurAt(float2 s) {
    if (uGBlur <= 0.0) {
        return content.eval(s);
    }
    half4 acc = half4(0.0);
    float wsum = 0.0;
    float2 dir = float2(1.0, 0.0);
    float ca = -0.7373688;
    float sa = 0.6754903;
    for (int i = 0; i < 32; i++) {
        if (float(i) < uGTaps) {
            float f = sqrt((float(i) + 0.5) / uGTaps);
            float w = exp(-2.5 * f * f);
            acc += content.eval(clampIn(s + dir * (f * uGBlur))) * half(w);
            wsum += w;
            dir = float2(dir.x * ca - dir.y * sa, dir.x * sa + dir.y * ca);
        }
    }
    return acc / half(wsum);
}
"""

private const val GLASS_MAIN = """
// The surface's steepest tilt, at the rim: 75 degrees.
const float SIN_MAX = 0.9659258;
const float COS_MAX = 0.2588190;
// Where the highlight peaks: 71 degrees, just inside the rim (a ring of light at 142 degrees from overhead).
const float SIN_PEAK = 0.9455186;
const float COS_PEAK = 0.3255682;
// How glossy the surface is: half strength about 10 degrees of tilt either side of the peak, which keeps the
// highlight within about the outer 2 dp of the band.
const float SHININESS = 48.0;
// Brightness of the highlight per unit of uGRim, set so the locked edge light keeps its tuned brightness.
const float RIM_GAIN = 1.3;

half4 main(float2 xy) {
    float2 p = xy - uGOrigin;
    float3 sc = scene(p);
    float d = sc.x;
    float2 n = sc.yz;
    float cover = clamp(0.5 - d, 0.0, 1.0);
    if (cover <= 0.0) {
        return shadowAt(d, n, xy);
    }
    float inside = -d;

    // Pressed, the piece lifts (iOS's Liquid Lens): it bends more, its rim brightens, and the glass lights up
    // around the finger.
    float lift = float(sceneLift(p));
    float2 tv = p - uGTouch;
    float under = lift > 0.0 ? lift * exp(-dot(tv, tv) / (2.0 * uGTouchR * uGTouchR)) : 0.0;

    // Lens: 0 inside the band, rising to uGDepth at the edge along a circular profile. Light hitting the
    // curved top bends toward the middle (Snell's law for a dome resting on the content), so the rim shows
    // content from further in, magnified.
    float t = clamp(1.0 - inside / uGBand, 0.0, 1.0);
    float bend = uGDepth * (1.0 + 0.35 * lift) * (1.0 - sqrt(1.0 - t * t));
    float2 s = clampIn(xy - n * bend);

    half4 c = blurAt(s);
    if (uGDispersion > 0.0) {
        float2 o = n * bend * uGDispersion;
        c.r = blurAt(clampIn(s + o)).r;
        c.b = blurAt(clampIn(s - o)).b;
    }

    // Colour boost, then tint (blended toward the accent tint where the scene asks).
    half l = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
    c.rgb = clamp(mix(half3(l), c.rgb, half(uGSaturation)), 0.0, c.a);
    half4 tint = mix(uGTint, uGTint2, sceneAccent(p));
    c.rgb = mix(c.rgb, tint.rgb, tint.a);
    c.a = mix(c.a, 1.0, tint.a);

    // One surface. The lens's curve sets how far the glass tilts at every point: flat inside the band, steepening
    // to THETA_MAX at the rim (sin(tilt) = t * sin(THETA_MAX): a pebble whose edge is steep but not vertical).
    // Reflection, the highlight, the edge shading and the pebble shading all follow from that one tilt, so they
    // blend into each other the way they do on a real curved edge instead of reading as separate drawn bands.
    float sinT = t * SIN_MAX;
    float cosT = sqrt(1.0 - sinT * sinT);
    // How steep the surface is: 0 flat, 1 at the rim.
    float steep = (1.0 - cosT) / (1.0 - COS_MAX);

    // Fresnel (Schlick's approximation, glass F0 = 0.04): reflectance climbs from almost nothing where the glass is
    // flat to strong where it is steep, as (1 - cos(tilt))^5. uGReflect is the reflectance at the rim. What it
    // reflects is what lies just outside the glass, mirrored across its edge.
    float fr = pow(1.0 - cosT, 5.0) / pow(1.0 - COS_MAX, 5.0);
    if (uGReflect > 0.0 && fr > 0.002) {
        half4 r = blurAt(clampIn(xy + n * (2.0 * inside + uGEdgeWidth)));
        half rl = dot(r.rgb, half3(0.2126, 0.7152, 0.0722));
        r.rgb = clamp(mix(half3(rl), r.rgb, half(1.3)), 0.0, r.a);
        c = mix(c, r, half(uGReflect * fr));
    }

    // Highlight (Blinn-Phong): light low around the glass reaches the eye off the surface where it tilts half way
    // toward the light, so the highlight is a soft band just inside the rim (peak at the PEAK tilt) whose width
    // follows the curve (cos(tilt - THETA_PEAK)^SHININESS), not a band of fixed width.
    float spec = pow(max(cosT * COS_PEAK + sinT * SIN_PEAK, 0.0), SHININESS);
    // Where the light comes from: alike all round with even light (uGEven), else mostly the lit side. With even
    // light, uGCornerK (how close the corner lights are) can still favour the corners.
    float corner = pow((abs(n.x) + abs(n.y)) * 0.70710678, uGCornerK);
    float facing = mix(dot(n, uGLight), corner, uGEven);
    float lit = pow(max(facing, 0.0), 1.5) + (1.0 - uGEven) * 0.45 * pow(max(-facing, 0.0), 1.5);
    float glow = uGRim * RIM_GAIN * lit * spec * (1.0 + 0.3 * lift);
    // The light under the finger: a broad, soft glow, a little stronger where the surface curves.
    glow += 0.14 * under + 0.18 * under * steep;
    c.rgb = c.rgb + (c.a - c.rgb) * half(clamp(glow, 0.0, 1.0));

    // Edge shading, last so the highlight cannot wash it out on white: where the glass is steepest, light reaching
    // the eye has been bent from further away and crossed more glass, so the rim reads darker (lighter on a dark
    // theme). It follows the tilt (steep^k: strongest at the rim, fading in about an edge width), more on one side
    // of the light unless the light is even.
    float k = clamp(uGBand / uGEdgeWidth * 0.45, 2.0, 12.0);
    float side = mix(mix(0.5, 1.0, smoothstep(-0.6, 0.9, dot(n, uGEdgeLight))), 0.8, uGEven);
    half e = half(pow(steep, k) * side) * uGEdge.a;
    c = c * (1.0 - e) + half4(uGEdge.rgb, 1.0) * e;

    // Pebble shading (diffuse): the more the surface turns away from the light overhead, the more it takes on the
    // edge colour (grey on a light theme, a light sheen on a dark one).
    half pe = half(steep * mix(smoothstep(-0.2, 1.0, dot(n, uGEdgeLight)), 0.45, uGEven) * 0.45) * uGEdge.a;
    c = c * (1.0 - pe) + half4(uGEdge.rgb, 1.0) * pe;

    // Anti-aliased border: the part of the pixel outside the shape gets the shadow.
    return c * half(cover) + shadowAt(max(d, 0.0), n, xy) * half(1.0 - cover);
}
"""

private const val SOLID_HEADER = """
layout(color) uniform half4 uGSolid;
layout(color) uniform half4 uGSolid2;
layout(color) uniform half4 uGEdge;
uniform float uGEdgeWidth;
uniform float2 uGEdgeLight;
uniform float uGEven;
uniform float uGShadow;
uniform float uGShadowR;
uniform float uGShadowDy;

// The soft shadow the glass casts on the content below it. d: distance out from the shape (n its outward
// normal), xy for the dither.
// - Light from above shifts the shadow down by uGShadowDy: the shape moved down by dy is nearer below it and
//   further above it (to first order, d - dy * n.y).
// - A soft (area) light gives a penumbra centred on the shadow's outline: half strength there, rising under the
//   shape, falling to exactly nothing uGShadowR / 2 outside it, with zero slope at both ends (smoothstep, close
//   to the error function of a real penumbra), so the shadow has no visible end.
// - A dither of under one colour level per pixel keeps such a faint gradient from banding into rings.
half4 shadowAt(float d, float2 n, float2 xy) {
    if (uGShadow <= 0.0) {
        return half4(0.0);
    }
    float x = clamp((d - uGShadowDy * n.y) / uGShadowR + 0.5, 0.0, 1.0);
    float f = 1.0 - x * x * (3.0 - 2.0 * x);
    if (f <= 0.0) {
        return half4(0.0);
    }
    float h = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);
    float a = max(uGShadow * f + (h - 0.5) / 255.0, 0.0);
    return half4(0.0, 0.0, 0.0, half(a));
}
"""

private const val SOLID_MAIN = """
void edgeShade(inout half4 c, float inside, float2 n) {
    float q = clamp(1.0 - inside / uGEdgeWidth, 0.0, 1.0);
    // All the way round, strongest on one side of the light; concentrated at the very border.
    float side = mix(mix(0.5, 1.0, smoothstep(-0.6, 0.9, dot(n, uGEdgeLight))), 0.8, uGEven);
    half e = half(q * q * side) * uGEdge.a;
    c = c * (1.0 - e) + half4(uGEdge.rgb, 1.0) * e;
}

half4 main(float2 xy) {
    float3 sc = scene(xy);
    float cover = clamp(0.5 - sc.x, 0.0, 1.0);
    if (cover <= 0.0) {
        return shadowAt(sc.x, sc.yz, xy);
    }
    half4 c = mix(uGSolid, uGSolid2, sceneAccent(xy));
    edgeShade(c, -sc.x, sc.yz);
    return c * half(cover) + shadowAt(max(sc.x, 0.0), sc.yz, xy) * half(1.0 - cover);
}
"""
