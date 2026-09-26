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
 *
 * Only the *excess* of a top or bottom bar over the head unit's own bar is ever
 * reserved here. Measured on the unit: overscan is *added* to the stable insets
 * rather than merged with them, and the stable insets keep the system bars' strips
 * whether those bars are shown or hidden. So an app that lays out against the
 * stable insets - Android Auto, Waze, every BYD app - already holds the strip
 * clear, and reserving it again cost each of them a dead band the height of the
 * bar. The strip itself is handled by the bar service instead: where it can, it
 * leaves the system bar *visible* under ours, so every app is inset by a real bar
 * and lays out the same way whichever insets it reads. Where it cannot, the system
 * bar is hidden and apps reading the content insets draw under ours - the state
 * this unit is in until the accessibility service connects.
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

    /** The system bar strip under our bar on each horizontal edge, when it is visible. */
    private var coveredTop = 0
    private var coveredBottom = 0

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

    /**
     * How much of [position]'s edge an ordinary window is kept off altogether - the
     * overscan plus, where our bar sits over a *visible* system bar, that bar's own
     * inset, which the window already honours. What is left of the bar beyond this
     * is the window's own business to avoid.
     */
    fun coveredPx(position: NavPosition): Int = reservedPx(position) + when (position) {
        NavPosition.TOP -> coveredTop
        NavPosition.BOTTOM -> coveredBottom
        else -> 0
    }

    /** Hands the whole screen back to apps. */
    fun clear(): Boolean = setOverscan(0, 0, 0, 0).also {
        active = false
        appliedLeft = 0
        appliedTop = 0
        appliedRight = 0
        appliedBottom = 0
        coveredTop = 0
        coveredBottom = 0
    }

    /**
     * Reserves the excess of each full-width bar over the strip apps already keep
     * clear, and frees the rest.
     *
     * [covering] names the horizontal edges whose system bar is being left visible
     * under ours; on those, as on a hidden one, the bar's strip is already off
     * limits to apps and only what our bar adds beyond it is asked for.
     */
    fun sync(context: Context, covering: Set<NavPosition>): Boolean {
        val settings = LauncherSettings.get(context)
        val density = context.resources.displayMetrics.density

        val navHeight = systemBarHeight(context, "navigation_bar_height")
        val statusHeight = systemBarHeight(context, "status_bar_height")
        val stripBottom =
            if (SystemBarController.isHidden(context) || NavPosition.BOTTOM in covering) navHeight else 0
        val stripTop =
            if (SystemBarController.isStatusHidden(context) || NavPosition.TOP in covering) statusHeight else 0

        fun reserved(position: NavPosition): Int {
            val bar = settings.navBar(position)
            // A floating pill does not span its edge, and a folded bar has given
            // its edge back - its restore tab overlays one corner rather than
            // reserving a strip.
            if (!(settings.navBarOccupiesEdge(position) && bar.layout == NavLayout.FULL_WIDTH)) return 0
            val thickness = (bar.thicknessDp * density).toInt()
            // Matches the bar, which grows to cover at least the strip apps hold
            // back - the two have to agree or the app's edge and the bar's edge
            // land in different places.
            val minimum = when (position) {
                NavPosition.BOTTOM -> stripBottom
                NavPosition.TOP -> stripTop
                else -> 0
            }
            return maxOf(thickness, minimum)
        }

        val left = reserved(NavPosition.LEFT)
        val right = reserved(NavPosition.RIGHT)
        val top = (reserved(NavPosition.TOP) - stripTop).coerceAtLeast(0)
        val bottom = (reserved(NavPosition.BOTTOM) - stripBottom).coerceAtLeast(0)

        val applied = setOverscan(left, top, right, bottom)
        active = applied && (left or top or right or bottom) != 0
        appliedLeft = if (applied) left else 0
        appliedTop = if (applied) top else 0
        appliedRight = if (applied) right else 0
        appliedBottom = if (applied) bottom else 0
        coveredTop = if (NavPosition.TOP in covering) statusHeight else 0
        coveredBottom = if (NavPosition.BOTTOM in covering) navHeight else 0
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
