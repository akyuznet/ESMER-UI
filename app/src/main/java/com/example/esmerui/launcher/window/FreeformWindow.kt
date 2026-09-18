package com.example.esmerui.launcher.window

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import com.example.esmerui.launcher.WindowMode

/**
 * Asks the system to draw another app in a freeform window at given bounds.
 *
 * This is the only route where touch, IME and rotation work without a
 * signature-level permission, because the system - not us - owns the window.
 */
object FreeformWindow {

    private const val TAG = "FreeformWindow"

    /** WindowConfiguration.WINDOWING_MODE_FREEFORM, which is @hide. */
    private const val WINDOWING_MODE_FREEFORM = 5

    private const val SETTING_FREEFORM = "enable_freeform_support"

    const val ENABLE_COMMAND =
        "adb shell settings put global enable_freeform_support 1  (then reboot)"

    /**
     * Freeform needs either the platform feature or the developer setting. Some
     * OEM builds report neither yet still honour the launch, so this is advisory:
     * we attempt the launch regardless and report what actually happened.
     */
    fun isSupported(context: Context): Boolean {
        val pm = context.packageManager
        val hasFeature =
            pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        val devSetting = runCatching {
            Settings.Global.getInt(context.contentResolver, SETTING_FREEFORM, 0)
        }.getOrDefault(0)
        return hasFeature || devSetting == 1
    }

    fun launch(context: Context, component: ComponentName, bounds: Rect): WindowResult {
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)

        // setLaunchWindowingMode is @hide; without it the system falls back to
        // fullscreen and the launch "succeeds" while ignoring our bounds.
        val modeSet = runCatching {
            HiddenApi.exempted
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
            true
        }.onFailure { Log.w(TAG, "setLaunchWindowingMode unavailable: ${it.message}") }
            .getOrDefault(false)

        if (!modeSet) {
            return WindowResult.Failure(
                reason = "This build blocks setLaunchWindowingMode, so the app cannot " +
                    "be confined to a window.",
                remedy = "Switch the window mode to Virtual display in settings.",
            )
        }

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        return runCatching {
            context.startActivity(intent, options.toBundle())
            WindowResult.Success(WindowMode.FREEFORM, interactive = true)
        }.getOrElse { error ->
            Log.w(TAG, "Freeform launch failed", error)
            WindowResult.Failure(
                reason = "Freeform launch refused: ${error.javaClass.simpleName}. " +
                    "Freeform is ${if (isSupported(context)) "enabled" else "NOT enabled"} " +
                    "on this device.",
                remedy = ENABLE_COMMAND,
            )
        }
    }
}
