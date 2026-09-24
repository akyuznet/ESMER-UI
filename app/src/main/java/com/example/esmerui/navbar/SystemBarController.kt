package com.example.esmerui.navbar

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Hides or restores the head unit's own system bars.
 *
 * The two bars are controlled independently - a top bar of readouts needs the status
 * bar gone, a bottom bar of buttons needs the navigation bar gone, and wanting one
 * does not imply the other.
 *
 * Uses the platform's `policy_control` global setting, which needs
 * WRITE_SECURE_SETTINGS. That permission is not grantable from the UI, so it has
 * to be granted once over adb:
 *
 *     adb shell pm grant com.example.esmerui android.permission.WRITE_SECURE_SETTINGS
 *
 * [canControl] reports whether we actually have it, so settings can say so plainly
 * instead of silently doing nothing.
 */
object SystemBarController {

    private const val TAG = "SystemBarController"

    /** The global setting both bars live in; the bar service watches it for clears. */
    const val KEY = "policy_control"

    private const val HIDE_NAV = "immersive.navigation=*"
    private const val HIDE_STATUS = "immersive.status=*"
    private const val HIDE_BOTH = "immersive.full=*"

    const val GRANT_COMMAND =
        "adb shell pm grant com.example.esmerui android.permission.WRITE_SECURE_SETTINGS"

    fun canControl(context: Context): Boolean = runCatching {
        // A no-op write is the only honest test: the permission is signature level
        // and checkSelfPermission can disagree with what the ROM actually enforces.
        val current = Settings.Global.getString(context.contentResolver, KEY)
        Settings.Global.putString(context.contentResolver, KEY, current)
        true
    }.getOrDefault(false)

    private fun policy(context: Context): String =
        Settings.Global.getString(context.contentResolver, KEY).orEmpty()

    /** True when the head unit's navigation bar is currently suppressed. */
    fun isHidden(context: Context): Boolean = policy(context).let {
        it.contains("immersive.navigation") || it.contains("immersive.full")
    }

    fun isStatusHidden(context: Context): Boolean = policy(context).let {
        it.contains("immersive.status") || it.contains("immersive.full")
    }

    /**
     * Applies both wishes at once.
     *
     * One setting carries both bars, so they cannot be written separately without
     * one clobbering the other - the caller passes what it wants for each and this
     * composes the single value the platform expects.
     */
    fun apply(context: Context, hideNavigation: Boolean, hideStatus: Boolean): Boolean =
        runCatching {
            Settings.Global.putString(
                context.contentResolver,
                KEY,
                when {
                    hideNavigation && hideStatus -> HIDE_BOTH
                    hideNavigation -> HIDE_NAV
                    hideStatus -> HIDE_STATUS
                    else -> null
                },
            )
            true
        }.onFailure {
            Log.w(TAG, "policy_control write refused: ${it.message}")
        }.getOrDefault(false)
}
