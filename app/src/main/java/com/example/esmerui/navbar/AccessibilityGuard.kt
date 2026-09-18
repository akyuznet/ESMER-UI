package com.example.esmerui.navbar

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps our accessibility service in the enabled list.
 *
 * Another app on this head unit (OverDrive's KeepAliveAccessibilityService) rewrites
 * `enabled_accessibility_services` to just itself, which silently kills Back and
 * Recents on our bar. We hold WRITE_SECURE_SETTINGS, so rather than asking the user
 * to re-enable it by hand every time, we re-append ourselves whenever the setting
 * changes.
 *
 * We append and never replace: clobbering the other app's entry in turn would just
 * be the same bug pointed the other way.
 */
object AccessibilityGuard {

    private const val TAG = "AccessibilityGuard"
    private const val SEPARATOR = ':'

    fun component(context: Context): ComponentName =
        ComponentName(context, NavAccessibilityService::class.java)

    fun isListed(context: Context): Boolean {
        val entries = currentEntries(context)
        val us = component(context)
        return entries.any { it.equalsComponent(us) }
    }

    /**
     * Adds our service to the enabled list if missing, preserving every other entry.
     * Returns true if we are listed afterwards.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isListed(context)) return true

        val updated = (currentEntries(context) + component(context).flattenToString())
            .filter { it.isNotBlank() }
            .joinToString(SEPARATOR.toString())

        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated,
            )
            Settings.Secure.putInt(context.contentResolver, "accessibility_enabled", 1)
            Log.i(TAG, "Re-added our accessibility service")
            true
        }.onFailure {
            // No WRITE_SECURE_SETTINGS - the settings screen tells the user what to do.
            Log.w(TAG, "Cannot self-enable accessibility: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Forces the platform to rebind our service.
     *
     * Being listed is not the same as being connected. Killing the app while the
     * service is bound - or the enabled list being rewritten repeatedly, which is
     * what the other app on this unit does - can leave the platform stuck partway,
     * with our entry in "Binding services" and never reaching "Bound". The list
     * looks correct, so nothing notices, and Back is quietly dead.
     *
     * Toggling `accessibility_enabled` is what clears it: the platform drops every
     * binding and starts again. The other services on this unit come back with us.
     */
    fun rebind(context: Context, onDone: () -> Unit) {
        ensureEnabled(context)
        runCatching {
            Settings.Secure.putInt(context.contentResolver, "accessibility_enabled", 0)
            // Long enough for the platform to tear the bindings down; it ignores a
            // flip that arrives inside the same beat.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    Settings.Secure.putInt(context.contentResolver, "accessibility_enabled", 1)
                }
                onDone()
            }, REBIND_DELAY_MS)
            Log.i(TAG, "Rebinding the accessibility service")
        }.onFailure {
            Log.w(TAG, "Cannot rebind accessibility: ${it.message}")
            onDone()
        }
    }

    private const val REBIND_DELAY_MS = 600L

    private fun currentEntries(context: Context): List<String> =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    /** Tolerates both the long and short flattened spellings the platform accepts. */
    private fun String.equalsComponent(target: ComponentName): Boolean {
        val parsed = ComponentName.unflattenFromString(this) ?: return false
        return parsed.packageName == target.packageName &&
            parsed.className == target.className
    }
}
