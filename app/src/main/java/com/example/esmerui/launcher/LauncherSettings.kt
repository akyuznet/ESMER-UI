package com.example.esmerui.launcher

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Grid preferences, persisted to SharedPreferences and observable from Compose.
 *
 * Every setter clamps to the supported range, so a bad stored value (or a future
 * range change) can never produce a zero-column grid.
 */
class LauncherSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var columnsState by mutableIntStateOf(
        prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(COLUMNS_RANGE)
    )
    private var rowsState by mutableIntStateOf(
        prefs.getInt(KEY_ROWS, DEFAULT_ROWS).coerceIn(ROWS_RANGE)
    )
    private var iconSizeState by mutableIntStateOf(
        prefs.getInt(KEY_ICON_SIZE, DEFAULT_ICON_SIZE).coerceIn(ICON_SIZE_RANGE)
    )

    /** Apps per row. */
    var columns: Int
        get() = columnsState
        set(value) = put(KEY_COLUMNS, value.coerceIn(COLUMNS_RANGE)) { columnsState = it }

    /** Rows per page; together with [columns] this sets the page size. */
    var rows: Int
        get() = rowsState
        set(value) = put(KEY_ROWS, value.coerceIn(ROWS_RANGE)) { rowsState = it }

    /** Icon edge length in dp. */
    var iconSizeDp: Int
        get() = iconSizeState
        set(value) = put(KEY_ICON_SIZE, value.coerceIn(ICON_SIZE_RANGE)) { iconSizeState = it }

    val appsPerPage: Int get() = columns * rows

    // ---- Home grid ----------------------------------------------------------

    // Held decoded rather than as the stored string: the grid reads this on every
    // recomposition, and a fresh list each time would defeat every remember() keyed
    // on it - and re-parse the whole grid for each frame of a drag.
    private var homeCellsState by mutableStateOf(loadHomeCells())

    private var homePagesState by mutableIntStateOf(
        prefs.getInt(KEY_HOME_PAGES, 1).coerceIn(HOME_PAGES_RANGE)
    )

    /**
     * The arranged home grid, or null while it has never been arranged.
     *
     * Null is not an empty grid - it means "whatever is installed", which is what a
     * launcher should show before anyone has touched it. The first edit stores the
     * layout that was already on screen, and from then on the grid is exactly what
     * was arranged: an app installed later waits in the drawer instead of appearing
     * unbidden in the middle of someone's layout.
     */
    val homeCells: List<HomeCell>?
        get() = homeCellsState

    /** How many pages the grid has, whether or not they hold anything. */
    var homePages: Int
        get() = homePagesState
        set(value) = put(KEY_HOME_PAGES, value.coerceIn(HOME_PAGES_RANGE)) { homePagesState = it }

    fun setHomeCells(cells: List<HomeCell>) {
        homeCellsState = cells
        prefs.edit().putString(KEY_HOME_CELLS, HomeLayout.encode(cells)).apply()
    }

    /** Forgets the arrangement, so the grid lists what is installed again. */
    fun clearHomeCells() {
        homeCellsState = null
        prefs.edit().remove(KEY_HOME_CELLS).remove(KEY_HOME_ITEMS).apply()
    }

    /**
     * Reads the placed layout, folding forward the older flat list if that is all
     * there is - an arrangement made before positions were stored survives the
     * upgrade as the same left-to-right order it already had.
     */
    private fun loadHomeCells(): List<HomeCell>? {
        prefs.getString(KEY_HOME_CELLS, null)?.let { return HomeLayout.decode(it) }
        val legacy = prefs.getString(KEY_HOME_ITEMS, null) ?: return null
        val items = legacy.split(',').mapNotNull { HomeItem.decode(it.trim()) }
        val columns = prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(COLUMNS_RANGE)
        val rows = prefs.getInt(KEY_ROWS, DEFAULT_ROWS).coerceIn(ROWS_RANGE)
        return HomeLayout.fromList(items, columns, rows)
    }

    // ---- Floating app window ------------------------------------------------

    private var windowEnabledState by mutableStateOf(
        prefs.getBoolean(KEY_WINDOW_ENABLED, false)
    )
    private var windowComponentState by mutableStateOf(
        prefs.getString(KEY_WINDOW_COMPONENT, null)
    )
    private var windowModeState by mutableStateOf(
        WindowMode.from(prefs.getString(KEY_WINDOW_MODE, null))
    )
    private var windowWidthState by mutableIntStateOf(
        prefs.getInt(KEY_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH).coerceIn(WINDOW_SIZE_RANGE)
    )
    private var windowHeightState by mutableIntStateOf(
        prefs.getInt(KEY_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT).coerceIn(WINDOW_SIZE_RANGE)
    )
    private var windowXState by mutableIntStateOf(
        prefs.getInt(KEY_WINDOW_X, DEFAULT_WINDOW_X).coerceIn(WINDOW_POS_RANGE)
    )
    private var windowYState by mutableIntStateOf(
        prefs.getInt(KEY_WINDOW_Y, DEFAULT_WINDOW_Y).coerceIn(WINDOW_POS_RANGE)
    )

    /** Master switch for the floating window. */
    var windowEnabled: Boolean
        get() = windowEnabledState
        set(value) {
            windowEnabledState = value
            prefs.edit().putBoolean(KEY_WINDOW_ENABLED, value).apply()
        }

    /** Flattened ComponentName of the app to host, or null if none picked yet. */
    var windowComponent: String?
        get() = windowComponentState
        set(value) {
            windowComponentState = value
            prefs.edit().putString(KEY_WINDOW_COMPONENT, value).apply()
        }

    /** Which windowing technique to use. */
    var windowMode: WindowMode
        get() = windowModeState
        set(value) {
            windowModeState = value
            prefs.edit().putString(KEY_WINDOW_MODE, value.name).apply()
        }

    /** Window width as a percentage of the screen width. */
    var windowWidthPercent: Int
        get() = windowWidthState
        set(value) = put(KEY_WINDOW_WIDTH, value.coerceIn(WINDOW_SIZE_RANGE)) {
            windowWidthState = it
        }

    /** Window height as a percentage of the screen height. */
    var windowHeightPercent: Int
        get() = windowHeightState
        set(value) = put(KEY_WINDOW_HEIGHT, value.coerceIn(WINDOW_SIZE_RANGE)) {
            windowHeightState = it
        }

    /** Top-left corner as a percentage of the screen, so drags survive a restart. */
    var windowXPercent: Int
        get() = windowXState
        set(value) = put(KEY_WINDOW_X, value.coerceIn(WINDOW_POS_RANGE)) { windowXState = it }

    var windowYPercent: Int
        get() = windowYState
        set(value) = put(KEY_WINDOW_Y, value.coerceIn(WINDOW_POS_RANGE)) { windowYState = it }

    // ---- Custom navigation bar ---------------------------------------------

    private var navHideSystemState by mutableStateOf(
        prefs.getBoolean(KEY_NAV_HIDE_SYSTEM, false)
    )
    private var navHideStatusState by mutableStateOf(
        prefs.getBoolean(KEY_NAV_HIDE_STATUS, false)
    )
    private var navReserveFullState by mutableStateOf(
        prefs.getBoolean(KEY_NAV_RESERVE_FULL, true)
    )
    private var navBarsState by mutableStateOf(loadNavBars())

    /**
     * Hide the head unit's own navigation bar so ours can take that strip.
     * Note this also removes BYD's climate controls, which we cannot replicate.
     */
    var navHideSystemBar: Boolean
        get() = navHideSystemState
        set(value) {
            navHideSystemState = value
            prefs.edit().putBoolean(KEY_NAV_HIDE_SYSTEM, value).apply()
        }

    /**
     * Hide the head unit's status bar, so a top bar of our own is not drawn over.
     *
     * Separate from [navHideSystemBar]: the status bar carries the notification
     * shade and the clock, which is a different trade to giving up BYD's climate
     * strip at the bottom.
     */
    var navHideStatusBar: Boolean
        get() = navHideStatusState
        set(value) {
            navHideStatusState = value
            prefs.edit().putBoolean(KEY_NAV_HIDE_STATUS, value).apply()
        }

    /**
     * Reserve each bar's whole thickness from apps, rather than only the part the
     * head unit's own bars were not already holding back.
     *
     * The two cannot both be had. Hiding a system bar does not reclaim the strip it
     * would have occupied: apps that lay out against the *stable* insets - Android
     * Auto among them - go on avoiding it either way, and anything reserved here is
     * added to that, which costs them a dead band the width of the hidden bar.
     * Apps that lay out against the *content* insets, which is most of them, see
     * nothing reserved on a hidden edge at all and draw right under our bar.
     *
     * On by default, because a control hidden behind an opaque bar is worse than a
     * margin that is merely wasteful.
     */
    var navReserveFullBars: Boolean
        get() = navReserveFullState
        set(value) {
            navReserveFullState = value
            prefs.edit().putBoolean(KEY_NAV_RESERVE_FULL, value).apply()
        }

    private var navCollapsedState by mutableStateOf(loadCollapsed())

    private fun loadCollapsed(): Set<NavPosition> =
        NavPosition.entries.filterTo(mutableSetOf()) {
            prefs.getBoolean(navKey(it, SUFFIX_COLLAPSED), false)
        }

    /** Whether this bar is folded away to a restore tab in its corner. */
    fun navBarCollapsed(position: NavPosition): Boolean = position in navCollapsedState

    /**
     * Folds a bar away or brings it back.
     *
     * Remembered rather than reset each start, because the bar service now comes
     * up on every boot and every wake from standby - a fold that lasted only
     * until the next traffic light would not be worth having. The tab is always
     * on screen, so there is no way to lose a bar by remembering this.
     */
    fun setNavBarCollapsed(position: NavPosition, collapsed: Boolean) {
        navCollapsedState = if (collapsed) navCollapsedState + position else navCollapsedState - position
        prefs.edit().putBoolean(navKey(position, SUFFIX_COLLAPSED), collapsed).apply()
    }

    /**
     * Whether a bar is actually taking its edge right now: configured to draw,
     * and not folded away. The edge reservation, the launcher's own padding and
     * the side bars' extent all follow this rather than [NavBarConfig.isRenderable],
     * which stays true for a folded bar because its tab still has to be drawn.
     */
    fun navBarOccupiesEdge(position: NavPosition): Boolean =
        navBar(position).isRenderable && !navBarCollapsed(position)

    private var checkDefaultState by mutableStateOf(
        prefs.getBoolean(KEY_CHECK_DEFAULT, true)
    )

    /** Remind on the home screen when another launcher holds the home role. */
    var checkDefaultOnStart: Boolean
        get() = checkDefaultState
        set(value) {
            checkDefaultState = value
            prefs.edit().putBoolean(KEY_CHECK_DEFAULT, value).apply()
        }

    fun navBar(position: NavPosition): NavBarConfig = navBarsState.getValue(position)

    val navBars: List<NavBarConfig>
        get() = NavPosition.entries.map { navBarsState.getValue(it) }

    /** Bars that should actually be drawn - enabled and carrying at least one item. */
    val renderableNavBars: List<NavBarConfig> get() = navBars.filter { it.isRenderable }

    fun updateNavBar(position: NavPosition, transform: (NavBarConfig) -> NavBarConfig) {
        val updated = transform(navBar(position)).let {
            it.copy(
                thicknessDp = it.thicknessDp.coerceIn(NAV_THICKNESS_RANGE),
                iconSizeDp = it.iconSizeDp.coerceIn(NAV_ICON_SIZE_RANGE),
            )
        }
        prefs.edit()
            .putBoolean(navKey(position, SUFFIX_ENABLED), updated.enabled)
            .putString(navKey(position, SUFFIX_LAYOUT), updated.layout.name)
            .putInt(navKey(position, SUFFIX_THICKNESS), updated.thicknessDp)
            .putInt(navKey(position, SUFFIX_ICON), updated.iconSizeDp)
            .putString(navKey(position, SUFFIX_ITEMS), NavItem.encode(updated.items))
            .apply()
        navBarsState = navBarsState + (position to updated)
    }

    /** Adds the item to this bar if absent, removes it if already present. */
    fun toggleNavItem(position: NavPosition, item: NavItem) {
        updateNavBar(position) { config ->
            val present = config.items.any { it.sameAs(item) }
            config.copy(
                items = if (present) config.items.filterNot { it.sameAs(item) }
                else config.items + item
            )
        }
    }

    /** Moves an item [delta] places along the bar; a move past either end is a no-op. */
    fun moveNavItem(position: NavPosition, index: Int, delta: Int) =
        moveNavItemTo(position, index, index + delta)

    /** Drops the item at [from] into slot [to]; anything out of range is a no-op. */
    fun moveNavItemTo(position: NavPosition, from: Int, to: Int) {
        updateNavBar(position) { config ->
            if (from !in config.items.indices || to !in config.items.indices || from == to) {
                config
            } else {
                config.copy(
                    items = config.items.toMutableList().apply { add(to, removeAt(from)) }
                )
            }
        }
    }

    private fun loadNavBars(): Map<NavPosition, NavBarConfig> {
        migrateSingleBarIfNeeded()
        return NavPosition.entries.associateWith { position ->
            NavBarConfig(
                position = position,
                enabled = prefs.getBoolean(navKey(position, SUFFIX_ENABLED), false),
                layout = NavLayout.from(prefs.getString(navKey(position, SUFFIX_LAYOUT), null)),
                thicknessDp = prefs
                    .getInt(navKey(position, SUFFIX_THICKNESS), DEFAULT_NAV_THICKNESS)
                    .coerceIn(NAV_THICKNESS_RANGE),
                iconSizeDp = prefs
                    .getInt(navKey(position, SUFFIX_ICON), DEFAULT_NAV_ICON_SIZE)
                    .coerceIn(NAV_ICON_SIZE_RANGE),
                items = NavItem.decodeAll(
                    prefs.getString(navKey(position, SUFFIX_ITEMS), null)
                        ?: defaultItemsFor(position)
                ),
            )
        }
    }

    /**
     * Folds the old single-bar settings onto whichever edge they were using, so an
     * existing setup survives the upgrade instead of silently resetting.
     */
    private fun migrateSingleBarIfNeeded() {
        if (prefs.getBoolean(KEY_NAV_MIGRATED, false)) return

        val edge = NavPosition.from(prefs.getString(KEY_NAV_POSITION, null))
        val actions = (prefs.getString(KEY_NAV_ACTIONS, null) ?: DEFAULT_NAV_ACTIONS)
            .split(',')
            .mapNotNull { NavAction.from(it) }
            .map { NavItem.Action(it) }
        val apps = prefs.getString(KEY_NAV_SHORTCUTS, "").orEmpty()
            .split(',')
            .mapNotNull { NavItem.decode(it.trim()) }

        prefs.edit()
            .putBoolean(navKey(edge, SUFFIX_ENABLED), prefs.getBoolean(KEY_NAV_ENABLED, false))
            .putString(
                navKey(edge, SUFFIX_LAYOUT),
                prefs.getString(KEY_NAV_LAYOUT, null) ?: NavLayout.FULL_WIDTH.name,
            )
            .putInt(
                navKey(edge, SUFFIX_THICKNESS),
                prefs.getInt(KEY_NAV_THICKNESS, DEFAULT_NAV_THICKNESS),
            )
            .putString(navKey(edge, SUFFIX_ITEMS), NavItem.encode(actions + apps))
            .putBoolean(KEY_NAV_MIGRATED, true)
            .apply()
    }

    private fun navKey(position: NavPosition, suffix: String): String =
        "nav_${position.name}_$suffix"

    /**
     * Only the bottom edge ships with buttons. Seeding every edge would put a
     * duplicate Back/Home/Recents on each bar the moment it is switched on.
     */
    private fun defaultItemsFor(position: NavPosition): String =
        if (position == NavPosition.BOTTOM) DEFAULT_NAV_ITEMS else ""

    /**
     * Re-reads every value from storage.
     *
     * Needed after an import, which rewrites the preference file underneath the
     * cached state. Without this the UI would keep showing the pre-import values
     * until the process restarted.
     */
    fun reload() {
        columnsState = prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(COLUMNS_RANGE)
        rowsState = prefs.getInt(KEY_ROWS, DEFAULT_ROWS).coerceIn(ROWS_RANGE)
        iconSizeState = prefs.getInt(KEY_ICON_SIZE, DEFAULT_ICON_SIZE).coerceIn(ICON_SIZE_RANGE)
        homeCellsState = loadHomeCells()
        homePagesState = prefs.getInt(KEY_HOME_PAGES, 1).coerceIn(HOME_PAGES_RANGE)

        windowEnabledState = prefs.getBoolean(KEY_WINDOW_ENABLED, false)
        windowComponentState = prefs.getString(KEY_WINDOW_COMPONENT, null)
        windowModeState = WindowMode.from(prefs.getString(KEY_WINDOW_MODE, null))
        windowWidthState = prefs.getInt(KEY_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH)
            .coerceIn(WINDOW_SIZE_RANGE)
        windowHeightState = prefs.getInt(KEY_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT)
            .coerceIn(WINDOW_SIZE_RANGE)
        windowXState = prefs.getInt(KEY_WINDOW_X, DEFAULT_WINDOW_X).coerceIn(WINDOW_POS_RANGE)
        windowYState = prefs.getInt(KEY_WINDOW_Y, DEFAULT_WINDOW_Y).coerceIn(WINDOW_POS_RANGE)

        navHideSystemState = prefs.getBoolean(KEY_NAV_HIDE_SYSTEM, false)
        navHideStatusState = prefs.getBoolean(KEY_NAV_HIDE_STATUS, false)
        navReserveFullState = prefs.getBoolean(KEY_NAV_RESERVE_FULL, true)
        navCollapsedState = loadCollapsed()
        checkDefaultState = prefs.getBoolean(KEY_CHECK_DEFAULT, true)
        navBarsState = loadNavBars()
    }

    fun resetToDefaults() {
        columns = DEFAULT_COLUMNS
        rows = DEFAULT_ROWS
        iconSizeDp = DEFAULT_ICON_SIZE
        windowWidthPercent = DEFAULT_WINDOW_WIDTH
        windowHeightPercent = DEFAULT_WINDOW_HEIGHT
        windowXPercent = DEFAULT_WINDOW_X
        windowYPercent = DEFAULT_WINDOW_Y
    }

    private inline fun put(key: String, value: Int, apply: (Int) -> Unit) {
        apply(value)
        prefs.edit().putInt(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "launcher_settings"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_ROWS = "rows"
        private const val KEY_ICON_SIZE = "icon_size_dp"
        private const val KEY_HOME_ITEMS = "home_items"
        private const val KEY_HOME_CELLS = "home_cells"
        private const val KEY_HOME_PAGES = "home_pages"
        val HOME_PAGES_RANGE = 1..9
        private const val KEY_WINDOW_ENABLED = "window_enabled"
        private const val KEY_WINDOW_COMPONENT = "window_component"
        private const val KEY_WINDOW_MODE = "window_mode"
        private const val KEY_WINDOW_WIDTH = "window_width_pct"
        private const val KEY_WINDOW_HEIGHT = "window_height_pct"
        private const val KEY_WINDOW_X = "window_x_pct"
        private const val KEY_WINDOW_Y = "window_y_pct"
        private const val KEY_NAV_ENABLED = "nav_enabled"
        private const val KEY_NAV_POSITION = "nav_position"
        private const val KEY_NAV_LAYOUT = "nav_layout"
        private const val KEY_NAV_THICKNESS = "nav_thickness_dp"
        private const val KEY_NAV_HIDE_SYSTEM = "nav_hide_system"
        private const val KEY_NAV_HIDE_STATUS = "nav_hide_status"
        private const val KEY_NAV_RESERVE_FULL = "nav_reserve_full"
        private const val SUFFIX_COLLAPSED = "collapsed"
        private const val KEY_NAV_SHORTCUTS = "nav_shortcuts"
        private const val KEY_NAV_ACTIONS = "nav_actions"
        private const val DEFAULT_NAV_ACTIONS = "BACK,HOME,RECENTS,APPS"

        // Per-edge bar storage: nav_<POSITION>_<suffix>
        private const val KEY_CHECK_DEFAULT = "check_default_launcher"
        private const val KEY_NAV_MIGRATED = "nav_migrated_multibar"
        private const val SUFFIX_ENABLED = "enabled"
        private const val SUFFIX_LAYOUT = "layout"
        private const val SUFFIX_THICKNESS = "thickness"
        private const val SUFFIX_ICON = "icon_size"
        private const val SUFFIX_ITEMS = "items"
        private const val DEFAULT_NAV_ITEMS = "@BACK,@HOME,@RECENTS,@APPS"

        const val DEFAULT_NAV_ICON_SIZE = 44
        val NAV_ICON_SIZE_RANGE = 20..96

        const val DEFAULT_COLUMNS = 6
        const val DEFAULT_ROWS = 3
        const val DEFAULT_ICON_SIZE = 72
        const val DEFAULT_WINDOW_WIDTH = 45
        const val DEFAULT_WINDOW_HEIGHT = 55
        const val DEFAULT_WINDOW_X = 50
        const val DEFAULT_WINDOW_Y = 25

        val COLUMNS_RANGE = 3..10
        val ROWS_RANGE = 1..6
        val ICON_SIZE_RANGE = 40..128
        const val DEFAULT_NAV_THICKNESS = 64

        val WINDOW_SIZE_RANGE = 20..100
        val WINDOW_POS_RANGE = 0..100
        val NAV_THICKNESS_RANGE = 40..120

        @Volatile
        private var instance: LauncherSettings? = null

        fun get(context: Context): LauncherSettings =
            instance ?: synchronized(this) {
                instance ?: LauncherSettings(context).also { instance = it }
            }
    }
}
