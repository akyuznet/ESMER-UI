package com.example.esmerui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.example.esmerui.MainActivity
import com.example.esmerui.launcher.window.HiddenApi

/**
 * Reports and requests the home-app (default launcher) role.
 *
 * There is no public API to become the default launcher - by design, or any app
 * could hijack the home button. `IPackageManager.setHomeActivity` can do it but is
 * gated on SET_PREFERRED_APPLICATIONS (privileged), so it is attempted first and
 * the system's own picker is opened when it is refused.
 */
object HomeRoleController {

    private const val TAG = "HomeRoleController"

    fun isDefault(context: Context): Boolean {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val packageName = resolved?.activityInfo?.packageName ?: return false
        // With no default chosen the resolver itself answers; that is not "us".
        return packageName == context.packageName
    }

    /** Name of whatever currently holds the role, for display. */
    fun currentHomeLabel(context: Context): String? = runCatching {
        val pm = context.packageManager
        val resolved = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return@runCatching null
        resolved.loadLabel(pm).toString()
    }.getOrNull()

    /**
     * Returns true if we became the default outright; false means the system
     * picker was opened instead and the user has to confirm.
     */
    fun requestDefault(context: Context): Boolean {
        if (setHomeActivityDirectly(context)) return true
        openPicker(context)
        return false
    }

    private fun setHomeActivityDirectly(context: Context): Boolean = runCatching {
        HiddenApi.exempted

        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "package")

        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val packageManager = stub
            .getMethod("asInterface", Class.forName("android.os.IBinder"))
            .invoke(null, binder)

        val component = ComponentName(context, MainActivity::class.java)
        packageManager!!.javaClass
            .getMethod(
                "setHomeActivity",
                ComponentName::class.java,
                Int::class.javaPrimitiveType,
            )
            .invoke(packageManager, component, android.os.Process.myUserHandle().hashCode())

        isDefault(context)
    }.onFailure {
        Log.i(TAG, "setHomeActivity unavailable, falling back to picker: ${it.message}")
    }.getOrDefault(false)

    /** Tries the dedicated home-app screen, then the general default-apps screen. */
    private fun openPicker(context: Context) {
        val candidates = listOf(
            Intent("android.settings.HOME_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }
}
