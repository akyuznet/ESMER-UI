package com.example.esmerui.launcher

import android.content.ComponentName
import androidx.compose.ui.graphics.ImageBitmap

/** A single launchable application shown on the launcher grid. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap?,
    /**
     * A MAIN activity with no LAUNCHER category - BYD's climate panel, radio and
     * camera screens among them. Kept out of the app drawer (no launcher shows
     * these) but offered as a shortcut, since they are genuinely useful targets.
     */
    val isHiddenScreen: Boolean = false,
) {
    val key: String get() = component.flattenToShortString()
}

/**
 * Resolves a stored key to an installed app.
 *
 * Keys are compared as ComponentNames rather than strings: the platform accepts
 * both the long spelling ("pkg/pkg.Class") and the short one ("pkg/.Class"), and a
 * raw string compare would report a perfectly valid shortcut as missing.
 */
fun List<AppEntry>.findByKey(key: String?): AppEntry? {
    val wanted = key?.let { ComponentName.unflattenFromString(it) } ?: return null
    return firstOrNull { it.component == wanted }
}
