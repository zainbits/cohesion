package io.github.zainbits.cohesion

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassStyleTest {
    @Test
    fun clarityRaisesTint() {
        var last = -1f
        for (i in 0..10) {
            val s = GlassStyle.forClarity(i / 10f, dark = false)
            assertTrue("tint must not fall at ${i / 10f}", s.tintAlpha >= last)
            last = s.tintAlpha
        }
    }

    @Test
    fun fullyTintedSkipsBlur() {
        val s = GlassStyle.forClarity(1f, dark = true)
        assertEquals(0.dp, s.blurRadius)
        assertEquals(1f, s.tintAlpha)
    }

    @Test
    fun zeroIsClearWithAVeilAndOutline() {
        for (dark in listOf(false, true)) {
            val s = GlassStyle.forClarity(0f, dark)
            assertEquals(0.dp, s.blurRadius)
            assertEquals(1f, s.saturation)
            // A light veil of the theme's tint, not frost.
            assertEquals(GlassStyle.TINT, s.tintAlpha)
            assertEquals(if (dark) GlassStyle.EDGE_LIGHT_DARK else GlassStyle.EDGE_LIGHT_LIGHT, s.rim)
            assertTrue(s.edgeAlpha > 0f)
            // Dark outline and whitish veil on light; light outline and dark veil on dark.
            assertEquals(dark, s.edge.luminance() > 0.5f)
            assertEquals(dark, s.tint.luminance() < 0.5f)
        }
    }

    @Test
    fun highContrastTintsMoreAndOutlinesStronger() {
        for (dark in listOf(false, true)) {
            val normal = GlassStyle.forClarity(0.2f, dark)
            val high = GlassStyle.forClarity(0.2f, dark, contrast = 1f)
            assertTrue(high.tintAlpha > normal.tintAlpha)
            assertTrue(high.edgeAlpha >= GlassStyle.CONTRAST_EDGE)
            assertTrue(high.edgeWidth > normal.edgeWidth)
            // Normal contrast changes nothing.
            assertEquals(normal, GlassStyle.forClarity(0.2f, dark, contrast = 0f))
        }
        // Fully tinted stays fully tinted.
        assertEquals(1f, GlassStyle.forClarity(1f, false, contrast = 1f).tintAlpha)
    }

    @Test
    fun glassCastsASoftShadow() {
        assertTrue(GlassStyle.forClarity(0.3f, false).shadow > 0f)
        assertTrue(GlassStyle.forClarity(0.3f, true).shadow > 0f)
        // A plain style (a flat button) has none.
        assertEquals(0f, GlassStyle().shadow)
    }

    @Test
    fun clarityIsClamped() {
        assertEquals(GlassStyle.forClarity(0f, false), GlassStyle.forClarity(-3f, false))
        assertEquals(GlassStyle.forClarity(1f, false), GlassStyle.forClarity(9f, false))
    }
}
