package com.example.esmerui.navbar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Supplies Back and Recents for the custom navigation bar, and hosts the bars.
 *
 * A normal app can send Home with an intent, but Back and Recents are global
 * system actions - [AccessibilityService.performGlobalAction] is the only route
 * open to an unprivileged app, which is why every third-party nav bar needs this.
 *
 * It also lends the bar service its window manager: a window added through an
 * accessibility service may be TYPE_ACCESSIBILITY_OVERLAY, which the platform
 * layers *above* the status and navigation bars, where an app overlay sits below
 * them. That is what lets our bars cover the head unit's own instead of needing
 * them hidden - and a visible system bar is what insets every app the same way.
 * Windows hosted here die with the service, so the bar service is told either way
 * and rebuilds on whichever host it has.
 */
class NavAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NavBarService.sync(applicationContext)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            NavBarService.sync(applicationContext)
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: NavAccessibilityService? = null

        /**
         * Whether the platform has actually connected us, which is not the same as
         * being enabled: the service can sit in the platform's "Binding" list
         * indefinitely with the enabled list still naming it, and in that state
         * every global action silently does nothing.
         */
        val isConnected: Boolean get() = instance != null

        /**
         * A window manager whose windows may sit above the system bars, or null
         * while the platform has not connected us.
         */
        fun overlayWindowManager(): WindowManager? =
            instance?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        fun back(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun recents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /** True once the user (or adb) has enabled the service in settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.contains(context.packageName + "/", ignoreCase = true)
        }
    }
}
