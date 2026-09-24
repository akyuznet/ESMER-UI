package com.example.esmerui.navbar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Supplies Back and Recents for the custom navigation bar.
 *
 * A normal app can send Home with an intent, but Back and Recents are global
 * system actions - [AccessibilityService.performGlobalAction] is the only route
 * open to an unprivileged app, which is why every third-party nav bar needs this.
 */
class NavAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * The bar drives this service for Back and Recents; the one thing it listens
     * for is which app came forward, because how much screen edge to hold back
     * depends on how that app lays itself out. See [OverscanController].
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (OverscanController.onForeground(event.packageName?.toString())) {
            NavBarService.reserveEdges(this)
        }
    }

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
