package nl.tippie.pixeltransfer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * What the sender could and could not do to the display, so the playback screen can be honest
 * about it rather than silently failing.
 */
data class DisplayState(
    val brightnessForced: Boolean,
    val wideColorDisabled: Boolean,
    val refreshRateHz: Float,
    val refreshRateLocked: Boolean,
    /** True when the display advertises several refresh rates and might switch mid-stream. */
    val variableRefreshRate: Boolean,
    /** Best-effort: a night-light or blue-light filter appears to be on. */
    val nightModeSuspected: Boolean,
)

/**
 * Puts the phone into the state a sender needs: maximum brightness, screen kept on, system UI
 * out of the way, no vendor wide-gamut or HDR tone mapping, and a fixed refresh rate.
 *
 * Two things genuinely cannot be turned off by a normal app: the system night-light / blue-light
 * filter, and vendor "eye comfort" modes. Both shift the palette. [apply] detects the night light
 * where the platform exposes it and reports it so the UI can ask the user to switch it off.
 */
object DisplayControl {

    fun apply(activity: Activity): DisplayState {
        val window = activity.window

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = attributes

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Vendor colour management and HDR tone mapping both move palette colours around; the
        // default (sRGB) colour mode is the only one whose primaries the receiver can assume.
        var wideColorDisabled = false
        runCatching {
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            wideColorDisabled = true
        }

        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

        var refreshRate = display?.refreshRate ?: 60f
        var locked = false
        var variable = false
        if (display != null) {
            val modes = display.supportedModes
            val current = display.mode
            variable = modes.count { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight } > 1
            // Pin the highest refresh rate at the current resolution: a fixed, known rate means
            // each displayed frame is on screen for a predictable time.
            val best = modes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                runCatching {
                    val params = window.attributes
                    params.preferredDisplayModeId = best.modeId
                    window.attributes = params
                    refreshRate = best.refreshRate
                    locked = true
                }
            }
        }

        return DisplayState(
            brightnessForced = true,
            wideColorDisabled = wideColorDisabled,
            refreshRateHz = refreshRate,
            refreshRateLocked = locked,
            variableRefreshRate = variable,
            nightModeSuspected = nightLightSuspected(activity),
        )
    }

    /** Hands the display back to the system. */
    fun release(activity: Activity) {
        val window = activity.window
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        attributes.preferredDisplayModeId = 0
        window.attributes = attributes
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * The night-light state is not public API. It is readable as a secure setting on AOSP and
     * most vendor builds; where it is not, this returns false and the UI simply does not warn.
     */
    private fun nightLightSuspected(activity: Activity): Boolean = runCatching {
        Settings.Secure.getInt(activity.contentResolver, "night_display_activated", 0) == 1
    }.getOrDefault(false)
}
