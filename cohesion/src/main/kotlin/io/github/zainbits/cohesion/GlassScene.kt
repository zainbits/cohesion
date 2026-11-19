package io.github.zainbits.cohesion

import android.graphics.RuntimeShader

/**
 * The outline a glass pass draws, as AGSL. The engine does everything else (backdrop, blur, lens,
 * tint, lit rim, solid fallback), so a library only describes its shape.
 *
 * [agsl] must declare the scene's own uniforms and define
 *
 *     float3 scene(float2 p)   // (signed distance, normal.x, normal.y); p in the element's pixels
 *
 * where the distance is negative inside and the normal points outward. When [hasAccent] is true it
 * must also define `half sceneAccent(float2 p)` (0..1), which blends the tint toward
 * [GlassStyle.accentTint] (for example a selected tab).
 *
 * Snippets for common outlines are in [CohesionAgsl].
 */
public abstract class GlassScene {
    public abstract val agsl: String
    public open val hasAccent: Boolean get() = false

    /**
     * Bump whenever the values [applyUniforms] writes change; a pass rebuilds its effect only then.
     */
    public var version: Int = 0
        protected set

    /** Writes the scene's uniforms (called only when [version] changed or the effect is rebuilt). */
    public abstract fun applyUniforms(shader: RuntimeShader)
}

/** AGSL building blocks shared by every scene. */
public object CohesionAgsl {
    /**
     * `float3 sdRoundRect(float2 p, float x, float y, float w, float h, float r, float band,
     * float ux, float uy, float st, float sq)`: distance to a rounded rectangle at (x, y) of size
     * (w, h) and corner radius r, and its outward normal. The normal comes from a rounder copy of the
     * shape (radius at least [band]) so it is smooth wherever a lens band reaches. (ux, uy, st, sq)
     * stretch the shape by st along the unit direction u and by sq across it (1, 0, 1, 1 = none).
     */
    public const val ROUND_RECT: String = """
float3 sdRoundRect(float2 xy, float x, float y, float w, float hgt, float rad, float band,
                   float ux, float uy, float st, float sq) {
    float2 h = float2(w, hgt) * 0.5;
    float2 p = xy - float2(x, y) - h;
    float2 u = float2(ux, uy);
    float2 v = float2(-uy, ux);
    p = (dot(p, u) / st) * u + (dot(p, v) / sq) * v;
    float m = min(h.x, h.y);
    float r = min(rad, m);
    float2 q = abs(p) - (h - r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float rb = min(max(r, band), m);
    float2 qb = abs(p) - (h - rb);
    float2 nb = (qb.x > 0.0 && qb.y > 0.0) ? normalize(qb) : (qb.x > qb.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    float2 sp = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n0 = nb * sp;
    float2 n = (dot(n0, u) / st) * u + (dot(n0, v) / sq) * v;
    n = n / max(length(n), 0.0001);
    return float3(d * min(st, sq), n);
}
"""

    /**
     * `float3 smoothUnion(float3 a, float3 b, float k)`: joins two (distance, normal) shapes so
     * that pieces closer than k flow into each other like liquid; k = 0 is a plain union.
     */
    public const val SMOOTH_UNION: String = """
float3 smoothUnion(float3 a, float3 b, float k) {
    if (k <= 0.0) {
        return b.x < a.x ? b : a;
    }
    float t = clamp(0.5 + 0.5 * (a.x - b.x) / k, 0.0, 1.0);
    float d = mix(a.x, b.x, t) - k * t * (1.0 - t);
    float2 n = mix(a.yz, b.yz, t);
    return float3(d, n / max(length(n), 0.0001));
}
"""
}
