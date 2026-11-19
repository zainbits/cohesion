package io.github.zainbits.cohesion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Shared pieces of adaptive text colour on glass: the decision and the kill switch. */
public object GlassTone {
    /** Off: no readings at all; adaptive glass keeps the colour its style starts with. */
    public var enabled: Boolean = true

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

    /** What the glass shows over content of [contentLuminance]: the content mixed with [style]'s tint. */
    public fun throughGlass(contentLuminance: Float, style: GlassStyle): Float {
        val a = style.tintAlpha.coerceIn(0f, 1f)
        return contentLuminance * (1f - a) + style.tint.luminance() * a
    }

    internal val worker: Handler by lazy { Handler(HandlerThread("Cohesion#tone").apply { start() }.looper) }
    internal val main: Handler by lazy { Handler(Looper.getMainLooper()) }
}

/**
 * Reads how bright the content next to a glass piece is, so its text can turn dark over bright content
 * (like iOS), as cheaply as possible:
 *
 * - **Only once things settle**: [watch] waits until scrolling has stopped for [SETTLE_MS], and while the
 *   content keeps changing (a spinner, streaming text) it reads at most once every [MAX_WAIT_MS]. Nothing
 *   runs while the screen is still.
 * - **Never waits**: [read] asks the system for a copy of a thin strip of the screen (PixelCopy) on a
 *   background thread and gets the answer later; the UI and render threads are never blocked.
 * - **Tiny**: the strip right beside the glass (the content about to slide under it, with no glass or
 *   text of its own) is scaled down to 48 x 4 pixels by the GPU.
 *
 * One probe per glass group; call [watch] when attached and [stop] when detached.
 */
public class GlassToneProbe(private val scope: CoroutineScope, private val onSettled: () -> Unit) {
    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(W * H)
    private val columns = FloatArray(W)
    private var busy = false
    private var job: Job? = null

    /** Calls the probe's `onSettled` once [backdrop] (or [moved], the glass itself) has settled. */
    public fun watch(backdrop: GlassBackdrop, moved: Flow<*>) {
        job?.cancel()
        if (!GlassTone.enabled) return
        job = scope.launch {
            var firstChange = 0L
            var pending: Job? = null
            merge(backdrop.scrolls.map { true }, backdrop.changes.map { false }, moved.map { false }).collect { scroll ->
                val now = SystemClock.uptimeMillis()
                if (scroll || pending?.isActive != true) firstChange = now
                pending?.cancel()
                // A scrolling finger postpones the reading until it stops; other changes wait at most MAX_WAIT_MS.
                val wait = if (scroll) SETTLE_MS else (firstChange + MAX_WAIT_MS - now).coerceIn(0L, SETTLE_MS)
                pending = launch {
                    delay(wait)
                    onSettled()
                }
            }
        }
    }

    /** Stops watching. */
    public fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Copies the strip [left]..[right] x [top]..[bottom] (window pixels) of [view]'s window and calls
     * [onResult] on the main thread with the content's relative luminance in [W] columns across it.
     * Returns false (and never calls back) when no copy was started: one is still running, or there is no
     * window or no on-screen strip.
     */
    public fun read(view: View, left: Int, top: Int, right: Int, bottom: Int, onResult: (FloatArray) -> Unit): Boolean {
        if (busy || !GlassTone.enabled) return false
        val window = view.context.findWindow() ?: return false
        val root = view.rootView
        val src = Rect(left, top, right, bottom)
        if (!src.intersect(0, 0, root.width, root.height) || src.width() < 2 || src.height() < 1) return false
        busy = true
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            PixelCopy.request(window, src, bitmap, { result ->
                // On the background thread: average each column, then hand the numbers to the main thread.
                val ok = result == PixelCopy.SUCCESS
                if (ok) {
                    bitmap.getPixels(pixels, 0, W, 0, 0, W, H)
                    for (x in 0 until W) {
                        var sum = 0f
                        for (y in 0 until H) sum += relativeLuminance(pixels[y * W + x])
                        columns[x] = sum / H
                    }
                }
                GlassTone.main.post {
                    busy = false
                    CohesionDebug.toneSamples++
                    CohesionDebug.toneNanos += SystemClock.elapsedRealtimeNanos() - start
                    if (ok) onResult(columns)
                }
            }, GlassTone.worker)
        } catch (e: RuntimeException) {
            busy = false
            return false
        }
        return true
    }

    public companion object {
        /** Columns across a strip. */
        public const val W: Int = 48
        private const val H = 4

        /** Quiet time after the last scroll before a reading. */
        public const val SETTLE_MS: Long = 150L

        /** Longest a reading waits while the content keeps changing without scrolling. */
        public const val MAX_WAIT_MS: Long = 1000L

        /** Average of [columns] for the part of a strip from [x0] to [x1] (0..1 of its width). */
        public fun average(columns: FloatArray, x0: Float, x1: Float): Float {
            val a = (x0 * W).toInt().coerceIn(0, W - 1)
            val b = (x1 * W).toInt().coerceIn(a + 1, W)
            var sum = 0f
            for (i in a until b) sum += columns[i]
            return sum / (b - a)
        }

        private fun relativeLuminance(argb: Int): Float {
            fun lin(c: Int): Float {
                val v = c / 255f
                return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
        }

        private tailrec fun Context.findWindow(): Window? = when (this) {
            is Activity -> window
            is ContextWrapper -> baseContext.findWindow()
            else -> null
        }
    }
}
