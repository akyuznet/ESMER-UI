package com.example.esmerui.launcher

import android.content.ComponentName

/**
 * One entry on a navigation bar.
 *
 * Built-in actions and app shortcuts share a single ordered list so they can be
 * interleaved freely - that is what makes "move Home after Chrome" expressible at
 * all. Actions encode with an "@" prefix, which a flattened ComponentName can
 * never start with, so the two namespaces cannot collide.
 */
sealed interface NavItem {

    val key: String

    data class Action(val action: NavAction) : NavItem {
        override val key: String get() = PREFIX + action.name
    }

    data class App(val component: ComponentName) : NavItem {
        override val key: String get() = component.flattenToShortString()
    }

    companion object {
        private const val PREFIX = "@"

        fun decode(key: String): NavItem? = when {
            key.isBlank() -> null
            key.startsWith(PREFIX) ->
                NavAction.from(key.removePrefix(PREFIX))?.let { Action(it) }
            else -> ComponentName.unflattenFromString(key)?.let { App(it) }
        }

        fun encode(items: List<NavItem>): String = items.joinToString(",") { it.key }

        fun decodeAll(encoded: String): List<NavItem> =
            encoded.split(',').mapNotNull { decode(it.trim()) }
    }
}

/** Equality that tolerates the long and short ComponentName spellings. */
fun NavItem.sameAs(other: NavItem): Boolean = when {
    this is NavItem.Action && other is NavItem.Action -> action == other.action
    this is NavItem.App && other is NavItem.App -> component == other.component
    else -> false
}
