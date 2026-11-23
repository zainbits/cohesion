package io.github.zainbits.cohesion

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * True while the device should not spend power on glass: battery saver is on, or the phone is hot
 * enough to throttle (thermal status SEVERE or worse). Updates live.
 */
@Composable
public fun rememberGlassThrottled(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saver by remember { mutableStateOf(power.isPowerSaveMode) }
    var hot by remember { mutableStateOf(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) }
    DisposableEffect(power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                saver = power.isPowerSaveMode
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        val thermal = PowerManager.OnThermalStatusChangedListener { status ->
            hot = status >= PowerManager.THERMAL_STATUS_SEVERE
        }
        power.addThermalStatusListener(context.mainExecutor, thermal)
        saver = power.isPowerSaveMode
        onDispose {
            context.unregisterReceiver(receiver)
            power.removeThermalStatusListener(thermal)
        }
    }
    return saver || hot
}

/**
 * The mode the device can afford right now: [preferred] normally, [GlassMode.Solid] while
 * [rememberGlassThrottled] is true. Updates live.
 */
@Composable
public fun rememberAdaptiveGlassMode(preferred: GlassMode = GlassMode.Full): GlassMode =
    if (rememberGlassThrottled()) GlassMode.Solid else preferred

/**
 * The phone's accessibility contrast for glass, 0 (normal) to 1 (high), live: 1 with High contrast text on
 * (Accessibility settings), else the system contrast level (Android 14+, Display: Contrast) above standard
 * (medium about 0.5, high 1). [GlassStyle.forClarity] then tints the glass more and strengthens its outline.
 */
@Composable
public fun rememberGlassContrast(): Float {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun highText() = Settings.Secure.getInt(resolver, HIGH_TEXT_CONTRAST, 0) == 1
    fun system(): Float = if (Build.VERSION.SDK_INT >= 34) {
        context.getSystemService(UiModeManager::class.java)?.contrast?.coerceIn(0f, 1f) ?: 0f
    } else {
        0f
    }
    var text by remember { mutableStateOf(highText()) }
    var level by remember { mutableStateOf(system()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                text = highText()
            }
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer)
        val ui = if (Build.VERSION.SDK_INT >= 34) context.getSystemService(UiModeManager::class.java) else null
        val listener: Any? = if (Build.VERSION.SDK_INT >= 34 && ui != null) {
            UiModeManager.ContrastChangeListener { level = it.coerceIn(0f, 1f) }.also { ui.addContrastChangeListener(context.mainExecutor, it) }
        } else {
            null
        }
        text = highText()
        level = system()
        onDispose {
            resolver.unregisterContentObserver(observer)
            if (Build.VERSION.SDK_INT >= 34 && ui != null && listener is UiModeManager.ContrastChangeListener) {
                ui.removeContrastChangeListener(listener)
            }
        }
    }
    return if (text) 1f else level
}

/** The accessibility contrast [ProvideGlass] applied (0..1), for an app's own variant of the style. */
public val LocalGlassContrast: ProvidableCompositionLocal<Float> = compositionLocalOf { 0f }

// Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, readable but not in the public SDK.
private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

/**
 * Sets the look and mode for every glass surface inside [content].
 *
 * @param clarity the iOS 27-style slider: 0 = ultra-clear, 1 = fully tinted.
 * @param contrast the phone's accessibility contrast (live by default): more tint and a stronger outline.
 */
@Composable
public fun ProvideGlass(
    clarity: Float,
    dark: Boolean,
    mode: GlassMode = rememberAdaptiveGlassMode(),
    contrast: Float = rememberGlassContrast(),
    content: @Composable () -> Unit,
) {
    val style = remember(clarity, dark, contrast) { GlassStyle.forClarity(clarity, dark, contrast) }
    CompositionLocalProvider(
        LocalGlassStyle provides style,
        LocalGlassMode provides mode,
        LocalGlassContrast provides contrast,
        content = content,
    )
}
