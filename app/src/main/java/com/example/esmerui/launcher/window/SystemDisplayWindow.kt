package com.example.esmerui.launcher.window

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.example.esmerui.launcher.WindowMode

/**
 * Launches an app onto a secondary display that the *system* owns.
 *
 * Android 10 blocks launching another app's activity onto a virtual display owned
 * by our own uid unless the target declares FLAG_ALLOW_EMBEDDED and we hold the
 * signature-level ACTIVITY_EMBEDDING permission. A display owned by SYSTEM_UID is
 * explicitly exempt from that check, which is why this route can host apps that
 * [VirtualDisplayHost] cannot - Android Auto among them.
 *
 * The trade-off is that the head unit, not us, decides where that window sits.
 */
object SystemDisplayWindow {

    private const val TAG = "SystemDisplayWindow"

    /** A non-default display we did not create ourselves. */
    fun findSecondaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    fun launch(context: Context, component: ComponentName): WindowResult {
        val display = findSecondaryDisplay(context)
            ?: return WindowResult.Failure(
                reason = "This head unit is not exposing a secondary display right now.",
                remedy = "Open BYD's own split-screen window once, then try again.",
            )

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)

        return runCatching {
            context.startActivity(intent, options.toBundle())
            WindowResult.Success(WindowMode.SYSTEM_DISPLAY, interactive = true)
        }.getOrElse { error ->
            Log.w(TAG, "Launch onto display ${display.displayId} failed", error)
            WindowResult.Failure(
                reason = "The system refused to launch this app on display " +
                    "${display.displayId} (${error.javaClass.simpleName}).",
                remedy = "This needs a system-signed build or root on this ROM.",
            )
        }
    }
}
