package com.example.esmerui.launcher

/** Which technique is used to put another app into the floating window. */
enum class WindowMode(val label: String, val description: String) {

    /** Try freeform first, fall back to a virtual display. */
    AUTO(
        label = "Automatic",
        description = "Try freeform, fall back to virtual display",
    ),

    /**
     * Ask the system to draw the app in a freeform window at our chosen bounds.
     * The system owns the window, so touch, IME and rotation all behave normally.
     * Needs freeform support enabled on the device.
     */
    FREEFORM(
        label = "Freeform window",
        description = "System-drawn window. Touch works. Needs freeform enabled.",
    ),

    /**
     * Render the app into a VirtualDisplay shown inside our own SurfaceView.
     * Always composites, but forwarding touch needs a signature-level permission,
     * so on a stock build this is usually view-only.
     */
    VIRTUAL_DISPLAY(
        label = "Virtual display",
        description = "Renders inside the launcher. Touch needs extra permission.",
    ),

    /**
     * Hand the app to a secondary display the system already owns - on DiLink that
     * is BYD's own container display, which it presents as a floating window.
     * The only route that works for unresizeable apps like Android Auto, at the
     * cost of the head unit owning the window's size and position.
     */
    SYSTEM_DISPLAY(
        label = "Head unit window",
        description = "Use BYD's own floating display. Works for Android Auto.",
    );

    companion object {
        fun from(name: String?): WindowMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
