package com.example.esmerui.launcher.window

import com.example.esmerui.launcher.WindowMode

/** Outcome of trying to put an app into the floating window. */
sealed interface WindowResult {

    data class Success(val mode: WindowMode, val interactive: Boolean) : WindowResult

    /**
     * [reason] is what went wrong, [remedy] is the concrete next step - usually an
     * adb command. Both are shown in the window itself, so a failure is never a
     * silent black rectangle.
     */
    data class Failure(val reason: String, val remedy: String? = null) : WindowResult
}
