package com.example.esmerui.launcher

import android.content.ComponentName

/**
 * One thing that can sit on the home grid.
 *
 * Apps, the launcher's own settings and hosted widgets share a single namespace so
 * they can be placed and moved interchangeably. The "@" prefix can never begin a
 * flattened ComponentName, so the built-in kinds cannot collide with an app;
 * [NavItem] uses the same trick for bar actions.
 */
sealed interface HomeItem {

    val key: String

    data class App(val component: ComponentName) : HomeItem {
        override val key: String get() = component.flattenToShortString()
    }

    /** The launcher's own settings screen, shown as an app among the apps. */
    data object Settings : HomeItem {
        override val key: String get() = SETTINGS_KEY
    }

    /**
     * A hosted Android widget, identified by the id the [AppWidgetHost] handed out.
     *
     * The provider is deliberately not stored: it is recoverable from the id at any
     * time, and an id whose provider has since been uninstalled must be detectable
     * as dead rather than resurrected from a stale component name.
     */
    data class Widget(val appWidgetId: Int) : HomeItem {
        override val key: String get() = WIDGET_PREFIX + appWidgetId
    }

    /**
     * A bar action placed on the grid instead: a live readout, a climate control, a
     * navigation button. Anything the bar can carry, the home screen can carry too -
     * which is the point, since a speed or a tyre readout is often wanted in the
     * middle of a page rather than squeezed onto an edge.
     */
    data class Action(val action: NavAction) : HomeItem {
        override val key: String get() = ACTION_PREFIX + action.name
    }

    companion object {
        const val SETTINGS_KEY = "@SETTINGS"
        const val WIDGET_PREFIX = "@W"
        const val ACTION_PREFIX = "@A"

        fun decode(key: String): HomeItem? = when {
            key.isBlank() -> null
            key == SETTINGS_KEY -> Settings
            key.startsWith(ACTION_PREFIX) ->
                NavAction.from(key.removePrefix(ACTION_PREFIX))?.let { Action(it) }
            key.startsWith(WIDGET_PREFIX) ->
                key.removePrefix(WIDGET_PREFIX).toIntOrNull()?.let { Widget(it) }
            // An unknown "@" entry belongs to a newer build. Dropping it beats
            // trying to unflatten it into a nonsense component.
            key.startsWith("@") -> null
            else -> ComponentName.unflattenFromString(key)?.let { App(it) }
        }
    }
}
