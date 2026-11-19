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

    private var recordedOrigin = Offset.Unspecified
    private var recordedSize = IntSize.Zero
    private var recordedSource: GraphicsLayer? = null

    public fun attach(context: GraphicsContext) {
        layer = context.createGraphicsLayer().apply { clip = true }
    }

    public fun detach(context: GraphicsContext) {
        layer?.let { context.releaseGraphicsLayer(it) }
        blurLayer?.let { context.releaseGraphicsLayer(it) }
        layer = null
        blurLayer = null
        effectVersion = -1
        effectStyle = null
        recordedOrigin = Offset.Unspecified
        recordedSource = null
    }

    /**
     * Draws the scene as glass over [backdrop], whose content sits at [offset] relative to this
     * element ([GlassBackdrop.positionOf]). [margin] lets the pass extend past the element so a
     * stretched shape is not cut off.
     */
    public fun DrawScope.draw(
        context: GraphicsContext,
        backdrop: GlassBackdrop,
        offset: Offset,
        style: GlassStyle,
        mode: GlassMode,
        margin: Dp = 0.dp,
    ) {
        val layer = layer
        if (mode == GlassMode.Solid || layer == null || offset == Offset.Unspecified) {
            drawSolid(style, margin)
            return
        }
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection("Cohesion#glass")
        try {
            drawPass(context, backdrop.layer, offset, style, mode, margin, layer)
        } finally {
            if (tracing) Trace.endSection()
        }
    }

    private fun DrawScope.drawPass(
        context: GraphicsContext,
        source: GraphicsLayer,
        offset: Offset,
        style: GlassStyle,
        mode: GlassMode,
        margin: Dp,
        layer: GraphicsLayer,
    ) {
        val m = ceil(margin.toPx()).toInt()
        val mf = m.toFloat()
        val intSize = IntSize(size.width.toInt() + 2 * m, size.height.toInt() + 2 * m)
        val blurPx = if (mode == GlassMode.NoBlur) 0f else style.blurRadius.toPx()
        val taps = blurPx >= 0.5f && CohesionDebug.blurMethod == 1
        val ds = when {
            blurPx < 0.5f -> 1
            CohesionDebug.blurDownsample > 0 -> CohesionDebug.blurDownsample
            taps -> ceil(blurPx / CohesionDebug.blurSpanPx).toInt().coerceIn(1, 8)
            else -> 1
        }
        if (scene.version != effectVersion || intSize != effectSize || density != effectDensity ||
            style != effectStyle || mode != effectMode || ds != effectDs || taps != effectTaps || m != effectMargin
        ) {
            val shader = Shaders.glass(scene)
            applyGlassUniforms(shader, intSize, mf, style, if (taps) blurPx else 0f)
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
        }

        val origin = Offset(offset.x - mf, offset.y - mf)
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

    private fun DrawScope.drawSolid(style: GlassStyle, margin: Dp) {
        val shader = Shaders.solid(scene)
        shader.setColorUniform("uGSolid", style.solidColor.toArgb())
        val accent = if (style.accentSolidColor.isSpecified) style.accentSolidColor else style.solidColor
        shader.setColorUniform("uGSolid2", accent.toArgb())
        scene.applyUniforms(shader)
        val m = margin.toPx()
        drawRect(ShaderBrush(shader), topLeft = Offset(-m, -m), size = Size(size.width + 2 * m, size.height + 2 * m))
    }

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
        shader.setFloatUniform("uGBlur", tapBlur)
        shader.setFloatUniform("uGTaps", CohesionDebug.blurTaps.coerceIn(4, 32).toFloat())
    }
}

/** Compiled shaders, one glass and one solid variant per scene source, shared by every pass. */
private object Shaders {
    private val glass = HashMap<String, RuntimeShader>()
    private val solid = HashMap<String, RuntimeShader>()

    fun glass(scene: GlassScene): RuntimeShader = glass.getOrPut(scene.agsl) {
        RuntimeShader(GLASS_HEADER + scene.agsl + accentDefault(scene) + GLASS_MAIN)
    }

    fun solid(scene: GlassScene): RuntimeShader = solid.getOrPut(scene.agsl) {
        RuntimeShader(SOLID_HEADER + scene.agsl + accentDefault(scene) + SOLID_MAIN)
    }

    private fun accentDefault(scene: GlassScene) =
        if (scene.hasAccent) "" else "\nhalf sceneAccent(float2 p) { return 0.0; }\n"
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
uniform float uGBlur;
uniform float uGTaps;

float2 clampIn(float2 s) {
    return clamp(s, float2(0.5), uGSize - 0.5);
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
half4 main(float2 xy) {
    float2 p = xy - uGOrigin;
    float3 sc = scene(p);
    float d = sc.x;
    float2 n = sc.yz;
    float cover = clamp(0.5 - d, 0.0, 1.0);
    if (cover <= 0.0) {
        return half4(0.0);
    }
    float inside = -d;

    // Lens: 0 inside the band, rising to uGDepth at the edge along a circular profile.
    float t = clamp(1.0 - inside / uGBand, 0.0, 1.0);
    float bend = uGDepth * (1.0 - sqrt(1.0 - t * t));
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

    // Light catching the rim: strongest where the edge faces the light, softer opposite.
    float facing = dot(n, uGLight);
    float lit = pow(max(facing, 0.0), 1.5) + 0.45 * pow(max(-facing, 0.0), 1.5);
    float rim = clamp(1.0 - inside / uGRimWidth, 0.0, 1.0);
    float glow = uGRim * (rim * lit + 0.10 * t * t);
    c.rgb = c.rgb + (c.a - c.rgb) * half(glow);

    return c * half(cover);
}
"""

private const val SOLID_HEADER = """
layout(color) uniform half4 uGSolid;
layout(color) uniform half4 uGSolid2;
"""

private const val SOLID_MAIN = """
half4 main(float2 xy) {
    float3 sc = scene(xy);
    float cover = clamp(0.5 - sc.x, 0.0, 1.0);
    return mix(uGSolid, uGSolid2, sceneAccent(xy)) * half(cover);
}
"""
