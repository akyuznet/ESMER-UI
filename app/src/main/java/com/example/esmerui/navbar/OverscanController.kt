package com.example.esmerui.navbar

import android.content.Context
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import android.view.Display
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.window.HiddenApi
import com.example.esmerui.launcher.NavLayout
import com.example.esmerui.launcher.NavPosition

/**
 * Shrinks the area ordinary apps may use, so the bars sit beside app content
 * instead of on top of it.
 *
 * An app-owned overlay cannot publish window insets, so there is no way to ask
 * other apps to make room. Overscan is the one mechanism on Android 10 that does
 * it globally, via `IWindowManager.setOverscan`, which is gated on
 * WRITE_SECURE_SETTINGS - the same permission the bar already needs to hide the
 * head unit's own bar. (Overscan was removed in Android 11; this unit is 10.)
 *
 * The bars themselves opt back into the reserved region with
 * FLAG_LAYOUT_IN_OVERSCAN, otherwise they would be inset along with everything else.
 */
object OverscanController {

    private const val TAG = "OverscanController"

    /**
     * True while the system is reserving edges for us. The launcher is an ordinary
     * app as far as overscan is concerned, so when this is on it must NOT add its
     * own padding as well or the grid ends up inset twice.
     */
    var active by mutableStateOf(false)
        private set

    /** The overscan currently in force, so bars can convert screen to frame coords. */
    var appliedLeft = 0
        private set
    var appliedTop = 0
        private set
    var appliedRight = 0
        private set
    var appliedBottom = 0
        private set

    /**
     * How much of [position]'s edge the system is holding clear for us, in pixels.
     *
     * Not always the bar's thickness: where an app would reserve the system bar's own
     * strip anyway, we ask only for the excess, and the rest of the bar has to be
     * avoided by the window itself.
     */
    fun reservedPx(position: NavPosition): Int = when (position) {
        NavPosition.LEFT -> appliedLeft
        NavPosition.TOP -> appliedTop
        NavPosition.RIGHT -> appliedRight
        NavPosition.BOTTOM -> appliedBottom
    }

    /** Hands the whole screen back to apps. */
    fun clear(): Boolean = setOverscan(0, 0, 0, 0).also {
        active = false
        appliedLeft = 0
        appliedTop = 0
        appliedRight = 0
        appliedBottom = 0
    }

    /** Reserves each edge occupied by a full-width bar and frees the rest. */
    fun sync(context: Context): Boolean {
        val settings = LauncherSettings.get(context)
        val density = context.resources.displayMetrics.density

        // Apps that lay out against stable insets - Android Auto among them - always
        // reserve the system bars' heights, whether or not those bars are actually
        // shown. Hiding them with immersive mode does not reclaim those strips, so
        // reserving the full bar thickness on top of that would book the edge twice
        // and leave a dead band inside the app. Only ask for the excess.
        val reservedBottom = if (SystemBarController.isHidden(context)) {
            systemBarHeight(context, "navigation_bar_height")
        } else {
            0
        }
        val reservedTop = if (SystemBarController.isStatusHidden(context)) {
            systemBarHeight(context, "status_bar_height")
        } else {
            0
        }

        fun reserved(position: NavPosition): Int {
            val bar = settings.navBar(position)
            // A floating pill does not span its edge, so nothing needs reserving.
            if (!(bar.isRenderable && bar.layout == NavLayout.FULL_WIDTH)) return 0
            val thickness = (bar.thicknessDp * density).toInt()
            // Matches the bar, which grows to cover at least the strip apps hold
            // back - the two have to agree or the app's edge and the bar's edge
            // land in different places.
            val minimum = when (position) {
                NavPosition.BOTTOM -> reservedBottom
                NavPosition.TOP -> reservedTop
                else -> 0
            }
            return maxOf(thickness, minimum)
        }

        val left = reserved(NavPosition.LEFT)
        val right = reserved(NavPosition.RIGHT)
        val top = (reserved(NavPosition.TOP) - reservedTop).coerceAtLeast(0)
        val bottom = (reserved(NavPosition.BOTTOM) - reservedBottom).coerceAtLeast(0)

        val applied = setOverscan(left, top, right, bottom)
        active = applied && (left or top or right or bottom) != 0
        appliedLeft = if (applied) left else 0
        appliedTop = if (applied) top else 0
        appliedRight = if (applied) right else 0
        appliedBottom = if (applied) bottom else 0
        return applied
    }

    private fun systemBarHeight(context: Context, dimen: String): Int {
        val id = context.resources.getIdentifier(dimen, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun setOverscan(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        runCatching {
            HiddenApi.exempted

            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as IBinder

            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val windowManager = stub
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)

            windowManager!!.javaClass
                .getMethod(
                    "setOverscan",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(windowManager, Display.DEFAULT_DISPLAY, left, top, right, bottom)
            true
        }.onFailure {
            Log.w(TAG, "setOverscan refused: ${it.message}")
        }.getOrDefault(false)
}
