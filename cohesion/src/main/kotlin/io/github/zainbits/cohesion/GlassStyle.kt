package io.github.zainbits.cohesion

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/**
 * How a glass surface looks. All lengths are in dp so a style works on any screen density.
 *
 * @property blurRadius frost of the content behind; 0 turns the blur pass off entirely.
 * @property refractionBand how far in from the edge the content bends.
 * @property refractionDepth how far (at the very edge) content is pulled in from.
 * @property saturation colour boost of what shows through; 1 = unchanged.
 * @property tint colour laid over the glass.
 * @property tintAlpha strength of [tint]; 0 = clear glass, 1 = solid.
 * @property rim strength of the light catching the edge; 0 = none.
 * @property rimWidth width of the lit edge.
 * @property lightAngle direction the light comes from, in degrees (0 = from the right,
 *   -90 = from the top).
 * @property edge colour of the thin outline around the glass, like the hairline on Apple's glass: dark on a
 *   light theme, light on a dark theme. It reads as the glass bending at its border.
 * @property edgeAlpha strength of [edge]; 0 = no outline.
 * @property edgeWidth width of the outline.
 * @property reflection how much the border mirrors what lies just outside the glass; 0 = none.
 * @property evenLight 0 = one light from [lightAngle]; 1 = four equal lights from the corners, so the
 *   highlight and edge shading are the same all round.
 * @property dispersion colour fringing at the edge (costs two extra samples per pixel when > 0).
 * @property solidColor what is drawn instead of glass in [GlassMode.Solid].
 * @property accentTint tint where a scene marks an accent (for example a selected tab);
 *   unspecified = same as [tint].
 * @property accentTintAlpha strength of [accentTint].
 * @property accentSolidColor [solidColor] for the accent; unspecified = same as [solidColor].
 */
@Immutable
public data class GlassStyle(
    val blurRadius: Dp = 10.dp,
    val refractionBand: Dp = 16.dp,
    val refractionDepth: Dp = 12.dp,
    val saturation: Float = 1.5f,
    val tint: Color = Color.White,
    val tintAlpha: Float = 0.10f,
    val rim: Float = 0.55f,
    val rimWidth: Dp = 1.2.dp,
    val lightAngle: Float = -120f,
    val dispersion: Float = 0f,
    val edge: Color = Color.Black,
    val edgeAlpha: Float = 0f,
    val edgeWidth: Dp = 0.8.dp,
    val reflection: Float = 0f,
    val evenLight: Float = 0f,
    val solidColor: Color = Color(0xF2F4F4F6),
    val accentTint: Color = Color.Unspecified,
    val accentTintAlpha: Float = 0.35f,
    val accentSolidColor: Color = Color.Unspecified,
) {
    public companion object {
        /** The default look for a light theme. */
        public val Light: GlassStyle = forClarity(0.35f, dark = false)

        /** The default look for a dark theme. */
        public val Dark: GlassStyle = forClarity(0.35f, dark = true)

        /**
         * A look on one slider, like the iOS 27 Liquid Glass setting.
         *
         * @param clarity 0 = clear (no frost and no blur pass: only a light veil of the theme's tint, the bent
         *   edge, its lit rim and the outline), 1 = fully tinted (also no blur pass).
         */
        public fun forClarity(clarity: Float, dark: Boolean): GlassStyle {
            val c = clarity.coerceIn(0f, 1f)
            val tint = if (dark) Color(0xFF101012) else Color(0xFFFFFFFF)
            // The first part of the slider fades in the colour boost from nothing.
            val r = (c / 0.15f).coerceAtMost(1f)
            // Even clear glass keeps a light veil of the theme's tint (whitish on light, dark on dark),
            // so it reads as glass and text on it stays legible.
            val veil = if (dark) 0.22f else 0.14f
            // Blur rises with tint until the glass is nearly opaque, then switches off:
            // a fully tinted surface hides the content anyway, so the pass would be wasted.
            val blur = if (c >= 0.97f) 0.dp else lerp(0.dp, 18.dp, (c / 0.8f).coerceAtMost(1f))
            return GlassStyle(
                blurRadius = blur,
                // A wide band with a deep bend, so the glass reads a little like a pebble: rounded all the
                // way in from its edge, not just at the rim.
                refractionBand = 20.dp,
                refractionDepth = lerp(18.dp, 8.dp, c),
                saturation = 1f + 0.6f * r - 0.5f * c,
                tint = tint,
                tintAlpha = if (c >= 0.97f) 1f else (veil + 0.80f * c * c).coerceAtMost(1f),
                rim = lerp(0.9f, 0.45f, c),
                rimWidth = 1.5.dp,
                lightAngle = -120f,
                dispersion = 0f,
                edge = if (dark) Color.White else Color.Black,
                edgeAlpha = if (dark) 0.24f else 0.22f,
                edgeWidth = 1.5.dp,
                reflection = lerp(0.55f, 0.15f, c),
                evenLight = 1f,
                solidColor = lerp(tint, if (dark) Color.Black else Color.White, 0.04f).copy(alpha = 0.95f),
            )
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

/** Where glass surfaces get their look when none is passed explicitly. */
public val LocalGlassStyle: androidx.compose.runtime.ProvidableCompositionLocal<GlassStyle> =
    compositionLocalOf { GlassStyle.Light }

/** How much rendering work glass surfaces do. */
public enum class GlassMode {
    /** Blur, refraction, tint and lit edge. */
    Full,

    /** Refraction, tint and lit edge without the blur pass. */
    NoBlur,

    /** A plain tinted shape: no offscreen pass at all. Used for battery saver and heat. */
    Solid,
}

/** The mode glass surfaces use when none is passed explicitly. */
public val LocalGlassMode: androidx.compose.runtime.ProvidableCompositionLocal<GlassMode> =
    compositionLocalOf { GlassMode.Full }
