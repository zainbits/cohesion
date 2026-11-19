package io.github.zainbits.cohesion

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.os.Trace
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * How bright the content behind glass is, so the glass's own text and icons can flip between light
 * and dark to stay readable (like iOS).
 *
 * Reading the content back from the GPU is the only way to know, so it is kept tiny and rare: the
 * region behind the glass is drawn into a 64 x 16 image by its own renderer, read on the CPU, and
 * averaged per piece. Call [luminance] only when the content or the glass moved ([GlassBackdrop.changes]),
 * at most a few times a second; nothing runs while the screen is still.
 */
public object GlassTone {
    private const val W = 64
    private const val H = 16

    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private val node = RenderNode("Cohesion#tone").apply { setPosition(0, 0, W, H) }
    private val scope = CanvasDrawScope()
    private val pixels = IntArray(W * H)

    /**
     * Average relative luminance (0 black .. 1 white) of [backdrop]'s content behind each region, as the
     * glass shows it: the content mixed with [style]'s tint. Writes [count] values into [out].
     *
     * @param bounds the glass's box in backdrop coordinates (see [GlassBackdrop.positionOf]).
     * @param regions [count] boxes as left, top, right, bottom quadruples, relative to [bounds].
     * @return false when nothing could be read (try again on the next change).
     */
    public fun luminance(
        backdrop: GlassBackdrop,
        density: Density,
        bounds: Rect,
        style: GlassStyle,
        regions: FloatArray,
        count: Int,
        out: FloatArray,
    ): Boolean {
        if (bounds.width < 1f || bounds.height < 1f || count == 0) return false
        Trace.beginSection("Cohesion#tone")
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val r = ensureRenderer()
            val canvas = node.beginRecording()
            try {
                scope.draw(density, LayoutDirection.Ltr, Canvas(canvas), Size(W.toFloat(), H.toFloat())) {
                    scale(W / bounds.width, H / bounds.height, pivot = Offset.Zero) {
                        translate(-bounds.left, -bounds.top) { drawLayer(backdrop.layer) }
                    }
                }
            } finally {
                node.endRecording()
            }
            r.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader?.acquireLatestImage() ?: return false
            image.use {
                val plane = it.planes[0]
                val buf = plane.buffer
                val rowPx = plane.rowStride / 4
                for (y in 0 until H) {
                    for (x in 0 until W) {
                        // RGBA_8888 in memory: R, G, B, A.
                        val i = (y * rowPx + x) * 4
                        pixels[y * W + x] = ((buf.get(i + 3).toInt() and 0xFF) shl 24) or
                            ((buf.get(i).toInt() and 0xFF) shl 16) or
                            ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                            (buf.get(i + 2).toInt() and 0xFF)
                    }
                }
            }
            val tintL = style.tint.luminance()
            val a = style.tintAlpha.coerceIn(0f, 1f)
            val sx = W / bounds.width
            val sy = H / bounds.height
            var ok = false
            for (k in 0 until count) {
                val x0 = (regions[k * 4] * sx).toInt().coerceIn(0, W - 1)
                val y0 = (regions[k * 4 + 1] * sy).toInt().coerceIn(0, H - 1)
                val x1 = (regions[k * 4 + 2] * sx).toInt().coerceIn(x0 + 1, W)
                val y1 = (regions[k * 4 + 3] * sy).toInt().coerceIn(y0 + 1, H)
                var sum = 0f
                var weight = 0f
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val p = pixels[y * W + x]
                        val alpha = (p ushr 24) / 255f
                        if (alpha <= 0f) continue
                        sum += alpha * relativeLuminance(p)
                        weight += alpha
                    }
                }
                if (weight <= 0f) {
                    out[k] = Float.NaN
                } else {
                    out[k] = (sum / weight) * (1f - a) + tintL * a
                    ok = true
                }
            }
            return ok
        } catch (e: RuntimeException) {
            return false
        } finally {
            CohesionDebug.toneSamples++
            CohesionDebug.toneNanos += SystemClock.elapsedRealtimeNanos() - start
            Trace.endSection()
        }
    }

    private fun ensureRenderer(): HardwareRenderer {
        renderer?.let { return it }
        val rd = ImageReader.newInstance(
            W, H, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val hr = HardwareRenderer().apply {
            setSurface(rd.surface)
            setContentRoot(node)
            isOpaque = false
        }
        reader = rd
        renderer = hr
        return hr
    }

    private fun relativeLuminance(argb: Int): Float {
        fun lin(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
    }

    /**
     * Light content (white text) or dark content (black text) for a [luminance], with a band in between
     * that keeps [wasDark] so the colour does not flicker on mid-grey content.
     */
    public fun wantsDarkContent(luminance: Float, wasDark: Boolean): Boolean = when {
        luminance.isNaN() -> wasDark
        // White and black text read equally well at a relative luminance of about 0.18.
        luminance > 0.26f -> true
        luminance < 0.14f -> false
        else -> wasDark
    }
}
