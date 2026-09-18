package com.example.esmerui.launcher

/** Everything that defines one edge's navigation bar. */
data class NavBarConfig(
    val position: NavPosition,
    val enabled: Boolean,
    val layout: NavLayout,
    val thicknessDp: Int,
    /** Icon edge length in dp, independent of [thicknessDp]. */
    val iconSizeDp: Int,
    val items: List<NavItem>,
) {
    /** An enabled bar with nothing on it would be an invisible dead strip. */
    val isRenderable: Boolean get() = enabled && items.isNotEmpty()
}
