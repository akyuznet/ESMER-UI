package com.example.esmerui.navbar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
     * The platform rebinds on one signal only: a change to the enabled list. Taking
     * our entry out and putting it back is that signal twice over - the removal
     * drops whatever half-open connection it holds for us, the re-add binds afresh.
     *
     * This used to flip `accessibility_enabled` instead, on the belief that the
     * platform tears every binding down when that goes to 0 and rebuilds them at 1.
     * It does not. On Android 10 that setting is one the accessibility manager
     * *writes*, to report whether anything is on; nothing observes it, so the flip
     * changed nothing at all. Read out of this ROM's own services.jar on
     * 26 Sep 2026, after a day of "rebinds" that had left Back dead every time.
     *
     * [NavBarService] watches the same list and re-adds us the moment we are gone,
     * so the removal may well be undone before the delay here elapses. That is
     * fine: the platform handles the two notifications in order, and the re-add is
     * the same re-add whoever makes it.
     */
    fun rebind(context: Context, onDone: () -> Unit) {
        val us = component(context)
        val remaining = currentEntries(context)
            .filterNot { it.equalsComponent(us) }
            .joinToString(SEPARATOR.toString())
        runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                remaining,
            )
            Log.i(TAG, "Rebinding the accessibility service")
            Handler(Looper.getMainLooper()).postDelayed({
                ensureEnabled(context)
                onDone()
            }, REBIND_DELAY_MS)
        }.onFailure {
            Log.w(TAG, "Cannot rebind accessibility: ${it.message}")
            onDone()
        }
    }

    private const val REBIND_DELAY_MS = 600L

    /** The head unit's own list of apps the system may start; see [openAutostartManager]. */
    const val ACTION_AUTOSTART_MANAGER = "android.intent.action.BYD_APPSTARTMANAGEMENT"

    /**
     * Opens the head unit's "Disable Autostart" list, where this launcher has to be
     * switched *off* - that is, allowed - before the platform will start or bind our
     * services while we are not already running.
     *
     * This ROM's ActivityManager refuses any bind or start into a third-party app
     * from another uid unless the app is running, or its entry in that list allows
     * it. The accessibility service is bound by the system, so with the entry at its
     * default a fresh install or a boot leaves Back and Recents dead until something
     * else brings our process up first - and then nothing retries. Only that list
     * fixes it: the value sits behind a signature permission, so we cannot set it
     * ourselves. Returns false where the dialog does not exist.
     */
    fun openAutostartManager(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(ACTION_AUTOSTART_MANAGER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

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
