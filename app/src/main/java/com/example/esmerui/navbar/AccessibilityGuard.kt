package com.example.esmerui.navbar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        // A rebind has taken us out on purpose and puts us back itself. Re-adding
        // here would undo the removal before the platform has seen it; see [rebind].
        // A stand-down has taken us out to stay out until the car is back on.
        if (isRebinding || isStandingDown) return true

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
     * The removal has to *stay* removed until the platform has acted on it. A
     * content observer is told that the setting changed, not what it changed to:
     * the accessibility manager wakes on its own thread and reads the list as it
     * is then. [NavBarService] watches the same list and used to re-add us the
     * moment we were gone, so by the time the platform looked, the list named us
     * again, nothing had changed, and the half-open connection stayed exactly where
     * it was. That was the "rebind" that never rebound anything on 25 Sep 2026, and
     * again on 1 Oct 2026, when the same removal done by hand with the re-add held
     * back a few seconds emptied the platform's binding queue and the re-add bound
     * first time. So [ensureEnabled] stands down while [isRebinding], and the
     * delay is long enough for the platform to have gone round.
     */
    fun rebind(context: Context, onDone: () -> Unit) {
        if (isStandingDown) {
            onDone()
            return
        }
        val us = component(context)
        val remaining = currentEntries(context)
            .filterNot { it.equalsComponent(us) }
            .joinToString(SEPARATOR.toString())
        rebindUntil = SystemClock.elapsedRealtime() + REBIND_HOLD_MS
        runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                remaining,
            )
            Log.i(TAG, "Rebinding the accessibility service: removed, re-adding in ${REBIND_HOLD_MS}ms")
            Handler(Looper.getMainLooper()).postDelayed({
                rebindUntil = 0L
                ensureEnabled(context)
                onDone()
            }, REBIND_HOLD_MS)
        }.onFailure {
            rebindUntil = 0L
            Log.w(TAG, "Cannot rebind accessibility: ${it.message}")
            onDone()
        }
    }

    /**
     * Takes our entry out of the list ahead of the head unit killing us, and holds
     * it out until [standUp].
     *
     * On ACC off this ROM runs a "quickboot": every third-party package is
     * force-stopped about a second after the car's power level reads off, and
     * without the package-restarted broadcast an ordinary force-stop sends. The
     * accessibility manager therefore sees only our service's binder die, and its
     * handling of that (`serviceDisconnectedLocked`, read from this ROM's
     * services.jar on 1 Oct 2026) is to put the service into its *binding* set, on
     * the assumption that the activity manager is about to restart it. Nothing
     * restarts a force-stopped service, only a force-stop or a package update ever
     * takes the entry out again, and every later bind is skipped because the entry
     * is there. That is the state the unit was found in after every ignition
     * cycle: bars on the fallback host, apps drawing under them, Back dead, until
     * a force-stop from a shell.
     *
     * A service that is unbound *cleanly* before the kill never dies on the
     * platform, so it is never marked binding. Removing our entry is that clean
     * unbind; the platform processes it in milliseconds, well inside the second
     * the quickboot leaves. The next start of our process re-adds the entry, and
     * that bind lands because by then the process is running.
     */
    fun standDown(context: Context, reason: String): Boolean {
        if (standingDown) return true
        standingDown = true
        val us = component(context)
        val remaining = currentEntries(context)
            .filterNot { it.equalsComponent(us) }
            .joinToString(SEPARATOR.toString())
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                remaining,
            )
            Log.i(TAG, "Standing down the accessibility service: $reason")
            true
        }.onFailure {
            standingDown = false
            Log.w(TAG, "Cannot stand down accessibility: ${it.message}")
        }.getOrDefault(false)
    }

    /** Ends a [standDown] and puts our entry back, which binds afresh. */
    fun standUp(context: Context, reason: String): Boolean {
        if (!standingDown) return true
        standingDown = false
        Log.i(TAG, "Standing the accessibility service back up: $reason")
        return ensureEnabled(context)
    }

    /** True while [standDown] is holding our entry out of the list. */
    val isStandingDown: Boolean get() = standingDown

    @Volatile
    private var standingDown = false

    /**
     * True while [rebind] is holding our entry out of the list. Deadline-based
     * rather than a flag, so a rebind whose callback never ran cannot leave the
     * guard standing down for ever.
     */
    val isRebinding: Boolean get() = SystemClock.elapsedRealtime() < rebindUntil

    @Volatile
    private var rebindUntil = 0L

    /**
     * How long the removal is left standing. The platform's observer runs within
     * milliseconds on an idle unit, but this list is rewritten by other apps and
     * read on a busy system thread; three seconds is what was measured to work by
     * hand on 1 Oct 2026, and a bar without Back for three seconds is nothing
     * next to a bar without Back until the next reboot.
     */
    private const val REBIND_HOLD_MS = 3_000L

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
