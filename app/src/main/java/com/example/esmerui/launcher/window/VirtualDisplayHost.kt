package com.example.esmerui.launcher.window

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.example.esmerui.launcher.WindowMode

/**
 * Renders another app into a VirtualDisplay whose output goes to a Surface we own,
 * so the app composites inside the launcher's own layout.
 *
 * Caveats, both enforced by the platform rather than by us:
 *  - Touch forwarding needs INJECT_EVENTS (signature-level). [forwardTouch] tries
 *    and reports honestly when it cannot, leaving the window view-only.
 *  - An activity that sets FLAG_SECURE renders black on a non-secure display,
 *    and creating a secure display needs CAPTURE_SECURE_VIDEO_OUTPUT.
 */
class VirtualDisplayHost(private val context: Context) {

    private var virtualDisplay: VirtualDisplay? = null
    private var inputWorks: Boolean? = null

    val displayId: Int? get() = virtualDisplay?.display?.displayId

    fun start(
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        component: ComponentName,
    ): WindowResult {
        release()

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        val display = runCatching {
            dm.createVirtualDisplay(
                DISPLAY_NAME,
                widthPx.coerceAtLeast(1),
                heightPx.coerceAtLeast(1),
                densityDpi,
                surface,
                flags,
            )
        }.getOrElse { error ->
            Log.w(TAG, "createVirtualDisplay failed", error)
            return WindowResult.Failure("Could not create a virtual display: ${error.message}")
        } ?: return WindowResult.Failure("Could not create a virtual display.")

        virtualDisplay = display

        val targetDisplayId = display.display.displayId
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId)

        return runCatching {
            context.startActivity(intent, options.toBundle())
            WindowResult.Success(WindowMode.VIRTUAL_DISPLAY, interactive = probeInput())
        }.getOrElse { error ->
            Log.w(TAG, "Launch onto virtual display failed", error)
            release()
            WindowResult.Failure(
                reason = "This build refuses to launch other apps on an app-owned " +
                    "display (${error.javaClass.simpleName}).",
                remedy = "Try the Freeform window mode instead.",
            )
        }
    }

    fun resize(widthPx: Int, heightPx: Int, densityDpi: Int) {
        virtualDisplay?.resize(
            widthPx.coerceAtLeast(1),
            heightPx.coerceAtLeast(1),
            densityDpi,
        )
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /**
     * Re-targets a touch at our SurfaceView onto the virtual display and injects it.
     * Returns false once the platform has told us we are not allowed to.
     */
    fun forwardTouch(event: MotionEvent): Boolean {
        val id = displayId ?: return false
        if (inputWorks == false) return false

        val copy = MotionEvent.obtain(event)
        return try {
            HiddenApi.exempted
            MotionEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(copy, id)

            val inputManager = context.getSystemService(Context.INPUT_SERVICE)
            inputManager.javaClass
                .getMethod(
                    "injectInputEvent",
                    Class.forName("android.view.InputEvent"),
                    Int::class.javaPrimitiveType,
                )
                .invoke(inputManager, copy, INJECT_ASYNC)
            inputWorks = true
            true
        } catch (t: Throwable) {
            if (inputWorks == null) {
                Log.w(TAG, "Touch injection unavailable, window is view-only: ${t.message}")
            }
            inputWorks = false
            false
        } finally {
            copy.recycle()
        }
    }

    /** Cheap probe so the UI can say up front whether the window will be interactive. */
    private fun probeInput(): Boolean {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        return try {
            forwardTouch(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "VirtualDisplayHost"
        const val DISPLAY_NAME = "EsmerAppWindow"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_ASYNC = 0
    }
}
