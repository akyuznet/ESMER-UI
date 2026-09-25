package com.example.esmerui.navbar

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Point
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.esmerui.MainActivity
import com.example.esmerui.R
import com.example.esmerui.car.AudioControl
import com.example.esmerui.car.Vent
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.example.esmerui.car.BusWatch
import com.example.esmerui.car.BydFeatures
import com.example.esmerui.car.CarBus
import com.example.esmerui.car.CarControl
import com.example.esmerui.car.CarInfo
import com.example.esmerui.car.CarStatus
import android.widget.Toast
import com.example.esmerui.launcher.HomeItem
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.NavAction
import com.example.esmerui.launcher.NavBarConfig
import com.example.esmerui.launcher.NavItem
import com.example.esmerui.launcher.NavLayout
import com.example.esmerui.launcher.NavPosition

/**
 * Hosts the custom navigation bar in a TYPE_APPLICATION_OVERLAY window.
 *
 * Overlay windows sit at a lower window layer than TYPE_NAVIGATION_BAR, so this
 * bar cannot be drawn over the head unit's own bar - it is instead placed just
 * clear of it, and only takes that strip once the system bar is hidden.
 */
class NavBarService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: LauncherSettings
    private val barViews = mutableMapOf<NavPosition, View>()
    private var accessibilityObserver: ContentObserver? = null

    /** Whether the system is reserving edges for us; drives the launcher's own inset. */
    private var overscanApplied = false

    /** Live readouts inside stepper groups, refreshed by [statusPoll]. */
    /**
     * Lists rather than single views, because the same item can sit on more than one
     * bar - the clock on the top edge and again down the left, say. Keyed by action,
     * every copy has to be repainted or one of them silently freezes.
     */
    private val groupReadouts = mutableMapOf<NavAction, MutableList<TextView>>()

    /** The captions under each stepper value, keyed like the readouts above. */
    private val groupCaptions = mutableMapOf<NavAction, MutableList<TextView>>()

    /**
     * Buttons whose tint reflects car state, e.g. A/C on, with what was last drawn.
     *
     * The last values matter: setImageResource and setColorFilter invalidate the view
     * whether or not anything changed, and the poll runs every second across four
     * overlay windows. Writing them unconditionally had the bars relayout and redraw
     * once a second forever, which is most of what the launcher was burning.
     */
    private class StateButton(val view: ImageView) {
        var lastIcon = 0
        var lastColour = 0
    }

    private val stateButtons = mutableMapOf<NavAction, MutableList<StateButton>>()

    /** The parts of one readout cell, kept together so the poll can update them. */
    private class InfoCell(
        val action: NavAction,
        /** Null for the readouts that carry no icon, like the clock. */
        val icon: ImageView?,
        val value: TextView,
        val caption: TextView,
        /** The green "lit" layer of a strength symbol, over its white outline. */
        val lit: ImageView? = null,
    ) {
        var lastValue: String? = null
        var lastCaption: String? = null
        var lastColour = 0

        /** Deliberately not -1, which is a real state here: the radio being off. */
        var lastLevel = Int.MIN_VALUE
    }

    private val infoCells = mutableListOf<InfoCell>()

    private fun <T> MutableMap<NavAction, MutableList<T>>.track(action: NavAction, view: T) {
        getOrPut(action) { mutableListOf() }.add(view)
    }
    private val pollHandler = Handler(Looper.getMainLooper())

    /**
     * The vehicle API pushes nothing, so the readouts have to be polled. Only runs
     * while a group is actually on a bar, and stops as soon as the bars come down.
     */
    /** Guards against stacking polls if one request stalls on a slow reply. */
    private var pollInFlight = false

    /**
     * Every action anyone is looking at, across the bars and the home grid.
     *
     * Each signal is a round trip to the car, so reading ones nobody shows is pure
     * waste - but "nobody" now has to include the home screen, which can carry the
     * same readouts. Recomputed per poll rather than cached: it is a list walk over
     * a few dozen entries, against a setting the user can change at any moment.
     */
    private fun shownActions(): Set<NavAction> {
        val onBars = infoCells.map { it.action }
        val onHome = settings.homeCells.orEmpty().mapNotNull {
            (it.item as? HomeItem.Action)?.action
        }
        return (onBars + onHome).toSet()
    }

    /**
     * Whether any HUD control is on screen, which is what makes the HUD's six
     * settings worth reading each poll. Bar buttons and steppers count as well as
     * the grid, which is why this is not simply a look at [shownActions].
     */
    private fun hudShown(shown: Set<NavAction>): Boolean =
        shown.any { it.isHud } || stateButtons.keys.any { it.isHud } ||
            groupReadouts.keys.any { it.isHud }

    /** The same gate for the roof panels, whose percentages cost two reads a poll. */
    private fun roofShown(shown: Set<NavAction>): Boolean =
        shown.any { it.isRoof } || stateButtons.keys.any { it.isRoof } ||
            groupReadouts.keys.any { it.isRoof }

    private val statusPoll = object : Runnable {
        override fun run() {
            // The home grid can be showing readouts with no bar up at all, so a
            // poll is worth running whenever anything anywhere wants a value.
            val wanted = shownActions()
            CarInfo.shown = wanted
            CarStatus.hudWanted = hudShown(wanted)
            CarStatus.roofWanted = roofShown(wanted)
            if (groupReadouts.isEmpty() && stateButtons.isEmpty() &&
                infoCells.isEmpty() && wanted.isEmpty()
            ) {
                return
            }

            // The clock has to tick even while a car read is still outstanding, so
            // it is repainted every pass rather than only on a reply.
            refreshInfoCells()

            if (!pollInFlight) {
                pollInFlight = true
                CarControl.run(
                    onResult = {
                        pollInFlight = false
                        refreshReadouts()
                        refreshButtonStates()
                        // The home grid can carry the same readouts, and it is
                        // Compose - it needs telling that the values moved.
                        CarInfo.bumpRevision()
                    },
                ) {
                    // The radios go here too: a dozen binder round trips into the
                    // telephony, Wi-Fi and Bluetooth services every second, which
                    // used to run on the main thread ahead of every frame.
                    CarInfo.refreshSignal(this@NavBarService)
                    CarStatus.refresh()
                    CarInfo.refresh()
                    ""
                }
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = LauncherSettings.get(this)
        // The bar is the main consumer of car state and can outlive the launcher
        // activity, so it opens its own connection rather than relying on the one
        // MainActivity makes. Opening it twice is a no-op.
        BydFeatures.init(this)
        // Also carries the on-demand bus sweep, which used to run itself at every
        // start: fifty seconds and near three thousand binder calls each boot, for a
        // file nobody reads until they ask for it.
        BusWatch.register(this)
        startInForeground()
        watchAccessibilitySetting()
        watchSystemBarPolicy()
    }

    /**
     * Re-hides the head unit's bars whenever something else brings them back.
     *
     * The hide is one global setting, and it does not last: the ROM leaves it
     * empty after a boot, and another launcher on this unit clears it outright
     * whenever it comes forward. Until now it was only re-asserted on a rebuild,
     * so BYD's bars could sit over ours for as long as the configuration stayed
     * still. Watching the setting itself puts them back within a moment.
     *
     * No loop: [applySystemBars] writes only when the platform disagrees with
     * what is wanted, so our own write leaves nothing for the observer to do.
     */
    private fun watchSystemBarPolicy() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Other launchers write, then write again; settle before answering,
                // or we trade writes with them.
                pollHandler.removeCallbacks(reassertSystemBars)
                pollHandler.postDelayed(reassertSystemBars, POLICY_SETTLE_MS)
            }
        }
        policyObserver = observer
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(SystemBarController.KEY),
            false,
            observer,
        )
    }

    private var policyObserver: ContentObserver? = null

    private val reassertSystemBars = Runnable {
        if (barViews.isEmpty()) return@Runnable
        applySystemBars()
        // Hidden bars change what the system holds back for apps, and the bars
        // sit in that frame, so the reservation and their placement move with it.
        reserveEdges()
    }

    /**
     * Another app on this unit rewrites the enabled-services list to just itself,
     * which kills Back and Recents. Re-assert ourselves whenever it changes rather
     * than leaving the buttons quietly dead.
     */
    private fun watchAccessibilitySetting() {
        AccessibilityGuard.ensureEnabled(this)
        verifyAccessibility(attempt = 0)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                AccessibilityGuard.ensureEnabled(this@NavBarService)
                // Being re-added to the list is exactly when the platform tends to
                // leave us half-bound, so re-check rather than assume it took.
                verifyAccessibility(attempt = 0)
            }
        }
        accessibilityObserver = observer
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A press on a home-grid control routes here rather than duplicating the
        // action logic in the launcher: climate writes, the accessibility repair
        // and the optimistic redraws all already live in this service.
        if (intent?.action == ACTION_PERFORM) {
            NavAction.from(intent.getStringExtra(EXTRA_ACTION))?.let { perform(it) }
            return START_STICKY
        }
        // A different app came forward and wants a different edge reserved. Only the
        // reservation changes; rebuilding the bars for an app switch would throw away
        // every view and every readout several times a minute.
        if (intent?.action == ACTION_RESERVE) {
            reserveEdges()
            return START_STICKY
        }
        // Debug only, from BusWatch: run the last rung of the accessibility repair
        // on demand. A path that only executes when something is already broken is
        // otherwise impossible to see working.
        if (intent?.action == ACTION_REPAIR) {
            restartToClearBinding()
            return START_STICKY
        }
        rebuild()
        return START_STICKY
    }

    override fun onDestroy() {
        removeBars()
        // Leaving overscan set with no bars drawn would permanently shrink every
        // app for a service that is no longer running.
        OverscanController.clear()
        accessibilityObserver?.let { contentResolver.unregisterContentObserver(it) }
        accessibilityObserver = null
        policyObserver?.let { contentResolver.unregisterContentObserver(it) }
        policyObserver = null
        pollHandler.removeCallbacks(reassertSystemBars)
        BusWatch.unregister(this)
        super.onDestroy()
    }

    /** Tears every bar down and re-adds it; simplest way to apply any setting change. */
    private fun rebuild() {
        removeBars()

        // Re-assert which system bars should be hidden. The platform keeps this in a
        // single global setting that anything can overwrite - another launcher on
        // this unit clears it outright, which leaves BYD's bars drawn over ours with
        // our own setting still saying they are hidden.
        applySystemBars()

        // Keep the reserved screen edges matching the bars that are actually up,
        // so apps are never asked to make room for a bar that is not there.
        overscanApplied = OverscanController.sync(this)

        val configs = settings.renderableNavBars
        // No bars is no longer the same as nothing to do: the home grid can carry
        // readouts on its own, and they still need polling.
        if (configs.isEmpty()) {
            startPollingIfWanted()
            return
        }

        configs.forEach { config ->
            // A folded bar is drawn as its restore tab, nothing else.
            val folded = settings.navBarCollapsed(config.position)
            val view = if (folded) restoreTab(config) else buildBar(config)
            val params = if (folded) tabParams(config.position) else layoutParams(config)
            runCatching { windowManager.addView(view, params) }
                .onSuccess { barViews[config.position] = view }
                .onFailure {
                    Toast.makeText(
                        this,
                        "Nav bar needs the \"Draw over other apps\" permission",
                        Toast.LENGTH_LONG,
                    ).show()
                    stopSelf()
                    return
                }
        }

        refreshButtonStates()
        startPollingIfWanted()
    }

    private fun startPollingIfWanted() {
        // Tell the reader which signals anyone is actually looking at.
        val wanted = shownActions()
        CarInfo.shown = wanted
        CarStatus.hudWanted = hudShown(wanted)
        CarStatus.roofWanted = roofShown(wanted)
        if (groupReadouts.isNotEmpty() || stateButtons.isNotEmpty() ||
            infoCells.isNotEmpty() || wanted.isNotEmpty()
        ) {
            pollHandler.post(statusPoll)
        }
    }

    /**
     * Blinks the indicator arrows while a side is lit.
     *
     * Driven on its own beat rather than from the car's signal: that one follows the
     * lamp, and the poll runs at about the flash rate, so letting it through directly
     * produced an arrow that came and went at random. The reading is held steady and
     * the blink is drawn here, which is why it looks like a dashboard tell-tale.
     *
     * The beat only runs while something is lit. It used to tick on regardless, near
     * three wake-ups a second for the whole time the car was driven straight, doing
     * nothing on each one; now the poll starts it when a side comes on and it stops
     * itself when the side goes off, leaving the arrow drawn steady.
     */
    private val blink = object : Runnable {
        override fun run() {
            blinkScheduled = false
            val cells = infoCells.filter { it.action == NavAction.INFO_TURN }
            if (cells.isEmpty()) return

            val lit = (CarInfo.turnSignals ?: 0) != 0
            blinkOn = if (lit) !blinkOn else true
            cells.forEach { cell ->
                if (cell.lit?.isVisible != blinkOn) cell.lit?.isVisible = blinkOn
            }
            if (lit) scheduleBlink()
        }
    }

    private var blinkOn = true
    private var blinkScheduled = false

    private fun scheduleBlink() {
        if (blinkScheduled) return
        blinkScheduled = true
        pollHandler.postDelayed(blink, BLINK_INTERVAL_MS)
    }

    /** Only writes when the platform disagrees, so an unchanged setting is untouched. */
    private fun applySystemBars() {
        val wantNav = settings.navHideSystemBar
        val wantStatus = settings.navHideStatusBar
        if (SystemBarController.isHidden(this) == wantNav &&
            SystemBarController.isStatusHidden(this) == wantStatus
        ) {
            return
        }
        SystemBarController.apply(this, wantNav, wantStatus)
    }

    /**
     * Re-reserves the screen edges and moves the bars to match.
     *
     * A docked bar is placed in the overscan frame rather than the screen, so its
     * own coordinates shift whenever the reservation does - leave them and the bar
     * lands inside the app area it just made room for.
     */
    private fun reserveEdges() {
        if (barViews.isEmpty()) return
        val before = intArrayOf(
            OverscanController.appliedLeft,
            OverscanController.appliedTop,
            OverscanController.appliedRight,
            OverscanController.appliedBottom,
        )
        overscanApplied = OverscanController.sync(this)
        val after = intArrayOf(
            OverscanController.appliedLeft,
            OverscanController.appliedTop,
            OverscanController.appliedRight,
            OverscanController.appliedBottom,
        )
        if (before.contentEquals(after)) return

        settings.renderableNavBars.forEach { config ->
            val view = barViews[config.position] ?: return@forEach
            val folded = settings.navBarCollapsed(config.position)
            val params = if (folded) tabParams(config.position) else layoutParams(config)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun removeBars() {
        barViews.values.forEach { runCatching { windowManager.removeView(it) } }
        barViews.clear()
        pollHandler.removeCallbacks(statusPoll)
        pollHandler.removeCallbacks(blink)
        blinkScheduled = false
        blinkOn = true
        pollInFlight = false
        groupReadouts.clear()
        groupCaptions.clear()
        stateButtons.clear()
        infoCells.clear()
        tyreSets.clear()
        // The "what was last drawn" caches belong to views that no longer exist; kept,
        // they would tell the next refresh that fresh, blank views were already right.
        lastReadoutText.clear()
        lastReadoutColour.clear()
        lastCaptionColour.clear()
    }

    /**
     * Steps a stepper's value and moves the readout on the spot.
     *
     * The target is computed from the cached value and shown immediately, so the
     * number tracks the press rather than the network. The command then goes out
     * with that absolute target, and the poller reconciles against the car.
     */
    private fun step(action: NavAction, delta: Int) {
        val target = when (action) {
            NavAction.TEMP_GROUP -> CarStatus.previewTemperature(delta, CarControl.temperatureRange)
            NavAction.FAN_GROUP -> CarStatus.previewFan(delta, CarControl.fanRange)
            NavAction.HUD_BRIGHTNESS_GROUP -> CarStatus.previewHudBrightness(delta)
            NavAction.HUD_HEIGHT_GROUP -> CarStatus.previewHudHeight(delta)
            else -> return
        }

        if (target == null) {
            // Nothing cached yet - fall back to the slower read-then-write path.
            car { nudge(action, delta) }
            return
        }

        refreshReadouts()
        groupReadouts[action]?.forEach { flash(it) }
        // Quiet on success: the readout already shows the result, and stepping
        // quickly would otherwise queue a toast per press.
        carQuietly {
            when (action) {
                NavAction.TEMP_GROUP -> CarControl.setTemperature(target)
                NavAction.FAN_GROUP -> CarControl.setFan(target)
                NavAction.HUD_BRIGHTNESS_GROUP -> CarControl.setHudBrightness(target)
                else -> CarControl.setHudHeight(target)
            }
        }
    }

    /** The read-then-write path for a stepper whose value has never been polled. */
    private fun nudge(action: NavAction, delta: Int): String = when (action) {
        NavAction.TEMP_GROUP -> CarControl.nudgeTemperature(delta)
        NavAction.FAN_GROUP -> CarControl.nudgeFan(delta)
        NavAction.HUD_BRIGHTNESS_GROUP -> CarControl.nudgeHudBrightness(delta)
        else -> CarControl.nudgeHudHeight(delta)
    }

    /**
     * Long press on A/C: stops the whole system, blower and all.
     *
     * Everything goes white at once rather than on the next poll, since the press
     * already knows the answer - and the fan readout drops to 0, which is what the
     * car reports a moment later.
     */
    private fun powerDownClimate() {
        CarStatus.previewClimateStop()
        refreshButtonStates()
        refreshReadouts()
        car { CarControl.stopClimate() }
    }

    private fun carQuietly(work: () -> String) {
        CarControl.run(
            onResult = { message ->
                if (CarControl.isFailure(message)) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                refreshReadouts()
            },
            work = work,
        )
    }

    /**
     * Updates the stepper readouts, tinting them green while the climate system is
     * running so the numbers read as live values rather than inert labels.
     *
     * Keyed on climate power, not the compressor: both numbers describe the blower,
     * which goes on working with the cooling switched off.
     */
    private fun refreshReadouts() {
        val climate = CarStatus.climateOn
        val colour = if (climate) ICON_ACTIVE_COLOR else ICON_COLOR
        setReadout(NavAction.TEMP_GROUP, CarStatus.temperatureLabel(), colour)
        setReadout(NavAction.FAN_GROUP, CarStatus.fanLabel(), colour)

        // The HUD steppers follow the display's own switch instead: a brightness
        // shown green while the glass is dark would be a live number for nothing.
        val hud = CarStatus.hudOn
        val hudColour = if (hud) ICON_ACTIVE_COLOR else ICON_COLOR
        setReadout(NavAction.HUD_BRIGHTNESS_GROUP, CarStatus.hudBrightnessLabel(), hudColour)
        setReadout(NavAction.HUD_HEIGHT_GROUP, CarStatus.hudHeightLabel(), hudColour)

        // Captions follow the value's state but stay dimmer, so the number leads.
        setCaption(NavAction.TEMP_GROUP, climate)
        setCaption(NavAction.FAN_GROUP, climate)
        setCaption(NavAction.HUD_BRIGHTNESS_GROUP, hud)
        setCaption(NavAction.HUD_HEIGHT_GROUP, hud)

        // Each roof panel follows itself rather than a shared switch: one can be
        // open with the other shut, so they are coloured one at a time. No caption
        // to repaint - the compact cell carries the glyph and the number only.
        PERCENT_ACTIONS.forEach { panel ->
            val open = NavActionState.isActive(panel)
            setReadout(panel, CarStatus.roofLabel(panel), if (open) ICON_ACTIVE_COLOR else ICON_COLOR)
        }

        refreshInfoCells()
    }

    /** Writes only on a change, for the same reason as [setReadout]. */
    private fun setCaption(action: NavAction, active: Boolean) {
        val colour = if (active) CAPTION_ACTIVE_COLOR else CAPTION_COLOR
        if (lastCaptionColour[action] == colour) return
        lastCaptionColour[action] = colour
        groupCaptions[action]?.forEach { it.setTextColor(colour) }
    }

    /**
     * Green up to [level], white for the rest of the symbol.
     *
     * A null level means the radio is switched off rather than merely quiet, so the
     * white layer becomes the struck-through outline and nothing is lit. Level 0 is
     * the in-between: running, but with nothing to show.
     */
    private fun refreshStrength(cell: InfoCell, level: Int?, symbol: RadioSymbol) {
        // -1 stands for off, which is not the same state as zero bars.
        val lit = level?.coerceIn(0, symbol.lit.lastIndex) ?: -1
        if (cell.lastLevel == lit) return
        cell.lastLevel = lit

        cell.icon?.setImageResource(if (lit < 0) symbol.off else symbol.outline)
        cell.lit?.setImageResource(symbol.lit[lit.coerceAtLeast(0)])
    }

    /** The three drawables a radio readout draws from. */
    private class RadioSymbol(
        @DrawableRes val outline: Int,
        @DrawableRes val off: Int,
        val lit: IntArray,
    )

    /**
     * What colour a readout draws in.
     *
     * Headlights are the one item with three states rather than two, and they follow
     * the dashboard's own convention: nothing lit is white, dipped beam green, main
     * beam blue - the colour every car uses for it, so it reads without a legend.
     */
    private fun infoColour(action: NavAction): Int = when (action) {
        NavAction.INFO_LIGHTS -> when (CarInfo.headlights) {
            CarBus.Beam.HIGH -> ICON_BEAM_COLOR
            CarBus.Beam.LOW -> ICON_ACTIVE_COLOR
            else -> ICON_COLOR
        }

        // Reverse is the one shift position worth flagging on its own - the car is
        // about to move the way the driver is not facing. Everything else out of park
        // is simply "ready to go", which is what green already says.
        NavAction.INFO_GEAR -> when (CarInfo.gear) {
            "R" -> ICON_LOW_COLOR
            null, "P" -> ICON_COLOR
            else -> ICON_ACTIVE_COLOR
        }

        NavAction.INFO_RANGE_EV -> levelColour(CarInfo.batteryPercent)
        NavAction.INFO_RANGE_FUEL -> levelColour(CarInfo.fuelPercent)

        else -> if (CarInfo.isActive(action)) ICON_ACTIVE_COLOR else ICON_COLOR
    }

    /**
     * How full reads as a colour, the way a fuel gauge does: comfortable, getting on,
     * and find a pump. White until the car has told us anything, so an unknown level
     * never looks like a warning.
     */
    private fun levelColour(percent: Int?): Int = when {
        percent == null -> ICON_COLOR
        percent < LEVEL_LOW_PERCENT -> ICON_LOW_COLOR
        percent < LEVEL_WARN_PERCENT -> ICON_WARN_COLOR
        else -> ICON_ACTIVE_COLOR
    }

    /** Writes only on a change, for the same reason [StateButton] remembers its own. */
    private fun setReadout(action: NavAction, text: String, colour: Int) {
        if (lastReadoutText[action] == text && lastReadoutColour[action] == colour) return
        lastReadoutText[action] = text
        lastReadoutColour[action] = colour
        groupReadouts[action]?.forEach {
            it.text = text
            it.setTextColor(colour)
        }
    }

    private val lastReadoutText = mutableMapOf<NavAction, String>()
    private val lastReadoutColour = mutableMapOf<NavAction, Int>()
    private val lastCaptionColour = mutableMapOf<NavAction, Int>()

    /** Drops the remembered tint for one button, so the next refresh repaints it. */
    private fun forgetTint(view: ImageView) {
        stateButtons.values.forEach { buttons ->
            buttons.forEach { if (it.view === view) it.lastColour = 0 }
        }
    }

    /**
     * Repaints the read-only items. The clock and date come straight from the system
     * clock rather than the poll's snapshot, so they are right even on a car that
     * answers nothing.
     */
    private fun refreshInfoCells() {
        refreshTyres()
        infoCells.forEach { cell ->
            when (cell.action) {
                // Strength lives in the shape, not in text: bars light up one by one,
                // and the Wi-Fi glyph gains an arc per level.
                NavAction.INFO_SIGNAL -> refreshStrength(cell, CarInfo.signalBars, SIGNAL_SYMBOL)
                NavAction.INFO_WIFI -> refreshStrength(cell, CarInfo.wifiBars, WIFI_SYMBOL)
                NavAction.INFO_BLUETOOTH ->
                    refreshStrength(cell, CarInfo.bluetoothBars, BLUETOOTH_SYMBOL)

                NavAction.INFO_TURN -> {
                    refreshStrength(cell, CarInfo.turnSignals, TURN_SYMBOL)
                    if ((CarInfo.turnSignals ?: 0) != 0) scheduleBlink()
                }
                else -> Unit
            }

            val value = CarInfo.valueFor(cell.action)
            if (cell.lastValue != value) {
                cell.lastValue = value
                cell.value.text = value
                // An empty readout should take no room, or an icon-only cell would
                // sit off-centre against a line of text it is not showing.
                cell.value.isVisible = value.isNotEmpty()
            }

            val colour = infoColour(cell.action)
            if (cell.lastColour != colour) {
                cell.lastColour = colour
                cell.value.setTextColor(colour)
                // The second line is part of the same reading, so it takes the same
                // colour - a range going red with its percentage still white would
                // read as two unrelated numbers.
                cell.caption.setTextColor(colour)
                cell.icon?.setColorFilter(colour)
            }

            val caption = CarInfo.captionFor(cell.action)
            if (cell.lastCaption != caption) {
                cell.lastCaption = caption
                cell.caption.text = caption.orEmpty()
                cell.caption.isVisible = !caption.isNullOrEmpty()
            }
        }
    }

    private fun layoutParams(config: NavBarConfig): WindowManager.LayoutParams {
        val position = config.position
        val fullWidth = config.layout == NavLayout.FULL_WIDTH
        // A pill floats clear of the edge, so it has no band to close and keeps the
        // thickness exactly as set.
        val thickness = if (fullWidth) dockedThickness(position) else dp(config.thicknessDp)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN

        return if (fullWidth) {
            dockedParams(position, thickness, flags)
        } else {
            floatingParams(position, thickness, flags)
        }
    }

    /**
     * Places a full-width bar by absolute screen coordinates.
     *
     * Overscan shrinks the frame that gravity is measured against, so a gravity-
     * placed bar lands *inside* the app area - on top of the very app it just made
     * room for. Subtracting the overscan converts a screen coordinate back into
     * that frame, which puts the bar in the reserved margin where it belongs.
     */
    private fun dockedParams(
        position: NavPosition,
        thickness: Int,
        flags: Int,
    ): WindowManager.LayoutParams {
        val screen = realScreenSize()
        val statusBar = systemTopInset()

        // Side bars stop short of the horizontal bars so no button is swallowed.
        val top = statusBar + edgeReserved(NavPosition.TOP)
        val bottom = systemBottomInset() + edgeReserved(NavPosition.BOTTOM)
        val sideSpan = (screen.y - top - bottom).coerceAtLeast(thickness)

        val width = if (position.isHorizontal) screen.x else thickness
        val height = if (position.isHorizontal) thickness else sideSpan

        val screenX = when (position) {
            NavPosition.RIGHT -> screen.x - thickness
            else -> 0
        }
        val screenY = when (position) {
            NavPosition.BOTTOM -> screen.y - systemBottomInset() - thickness
            NavPosition.TOP -> statusBar
            else -> top
        }

        return WindowManager.LayoutParams(width, height, 0, 0, 0).apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            this.flags = flags
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = screenX - OverscanController.appliedLeft
            y = screenY - OverscanController.appliedTop
        }
    }

    /** A pill claims no edge, so gravity placement is still the simplest thing. */
    private fun floatingParams(
        position: NavPosition,
        thickness: Int,
        flags: Int,
    ): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            if (position.isHorizontal) WindowManager.LayoutParams.WRAP_CONTENT else thickness,
            if (position.isHorizontal) thickness else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )

        params.gravity = when (position) {
            NavPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            NavPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            NavPosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            NavPosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        }

        val margin = dp(8)
        params.y = when (position) {
            NavPosition.BOTTOM -> systemBottomInset() + margin
            NavPosition.TOP -> systemBar(STATUS_BAR_DIMEN) + margin
            else -> 0
        }
        params.x = if (position.isHorizontal) 0 else margin

        return params
    }

    /** How much of an edge a full-width bar there claims; a pill claims nothing. */
    private fun edgeReserved(position: NavPosition): Int {
        val bar = settings.navBar(position)
        return when {
            !(bar.isRenderable && bar.layout == NavLayout.FULL_WIDTH) -> 0
            // Folded: only the restore tab is left in the corner, and the side bars
            // stop short of it so the tab is never buried under a last button.
            settings.navBarCollapsed(position) -> dp(TAB_HEIGHT_DP)
            else -> dockedThickness(position)
        }
    }

    /**
     * Folds a bar away or brings it back. A rebuild is the cheap answer here:
     * this happens on a deliberate press, not on a timer, and it is the one path
     * that already re-reserves the edges, re-places the other bars and repaints
     * the launcher's own padding together.
     */
    private fun setCollapsed(position: NavPosition, collapsed: Boolean) {
        if (settings.navBarCollapsed(position) == collapsed) return
        settings.setNavBarCollapsed(position, collapsed)
        rebuild()
    }

    /**
     * The chevrons. A handle points into the edge its bar folds towards; the tab
     * that replaces it points back out, the way the bar will come.
     */
    @DrawableRes
    private fun handleIcon(position: NavPosition): Int = when (position) {
        NavPosition.BOTTOM -> R.drawable.ic_bar_collapse
        NavPosition.TOP -> R.drawable.ic_bar_expand
        NavPosition.RIGHT -> R.drawable.ic_chevron_right
        NavPosition.LEFT -> R.drawable.ic_chevron_left
    }

    @DrawableRes
    private fun tabIcon(position: NavPosition): Int = when (position) {
        NavPosition.BOTTOM -> R.drawable.ic_bar_expand
        NavPosition.TOP -> R.drawable.ic_bar_collapse
        NavPosition.RIGHT -> R.drawable.ic_chevron_left
        NavPosition.LEFT -> R.drawable.ic_chevron_right
    }

    /**
     * The restore tab: what is left of a bar once folded. A single small chevron
     * hugging the corner at the end the handle was, as thin as a finger still
     * finds. A horizontal bar's tab lies along its edge; a side bar's stands on
     * end, and only the corners facing away from the edge are rounded, so the tab
     * reads as something that grew out of it.
     */
    private fun restoreTab(config: NavBarConfig): View {
        val position = config.position
        val handle = ImageView(this).apply {
            setImageResource(tabIcon(position))
            setColorFilter(CAPTION_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Show bar"
            isClickable = true
            setOnClickListener { setCollapsed(position, false) }
        }
        val r = dp(8).toFloat()
        // Radii run top-left, top-right, bottom-right, bottom-left, two values each.
        val radii = when (position) {
            NavPosition.BOTTOM -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            NavPosition.TOP -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            NavPosition.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            NavPosition.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        val (w, h) = tabSize(position)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF000000"))
                cornerRadii = radii
            }
            addView(handle, LinearLayout.LayoutParams(w, h))
        }
    }

    /** Along the edge for a horizontal bar, on end for a side bar. */
    private fun tabSize(position: NavPosition): Pair<Int, Int> =
        if (position.isHorizontal) dp(TAB_WIDTH_DP) to dp(TAB_HEIGHT_DP)
        else dp(TAB_HEIGHT_DP) to dp(TAB_WIDTH_DP)

    /**
     * Places a restore tab at the end of its bar's edge, in the overscan frame
     * like a bar. The two horizontal bars own the right-hand corners; a side bar's
     * tab sits just above whatever the bottom edge is holding, bar or tab, so two
     * folded bars never stack their tabs on the same corner.
     */
    private fun tabParams(position: NavPosition): WindowManager.LayoutParams {
        val screen = realScreenSize()
        val (width, height) = tabSize(position)
        val screenX = if (position == NavPosition.LEFT) 0 else screen.x - width
        val screenY = when (position) {
            NavPosition.TOP -> systemTopInset()
            NavPosition.BOTTOM -> screen.y - systemBottomInset() - height
            else -> screen.y - systemBottomInset() - edgeReserved(NavPosition.BOTTOM) - height
        }
        return WindowManager.LayoutParams(width, height, 0, 0, 0).apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = screenX - OverscanController.appliedLeft
            y = screenY - OverscanController.appliedTop
        }
    }

    /**
     * The strip a docked bar actually fills, which is at least what apps hold back
     * for the system bar it stands in for.
     *
     * Apps laying out against stable insets reserve status_bar_height and
     * navigation_bar_height whether or not those bars are shown. A thinner bar than
     * that leaves the difference as a dead band between the app and the bar - visible
     * in Android Auto as a strip of its own background. Growing the window closes it;
     * the buttons keep their configured size and simply sit centred in it, so on a
     * black bar nothing looks any different.
     */
    private fun dockedThickness(position: NavPosition): Int {
        val thickness = dp(settings.navBar(position).thicknessDp)
        val reserved = when (position) {
            NavPosition.BOTTOM ->
                if (SystemBarController.isHidden(this)) systemBar(NAV_BAR_DIMEN) else 0

            NavPosition.TOP ->
                if (SystemBarController.isStatusHidden(this)) systemBar(STATUS_BAR_DIMEN) else 0

            else -> 0
        }
        return maxOf(thickness, reserved)
    }

    private fun systemBottomInset(): Int =
        if (SystemBarController.isHidden(this)) 0 else systemBar(NAV_BAR_DIMEN)

    /**
     * The strip the head unit's status bar occupies, or nothing once it is hidden.
     *
     * Reserving it unconditionally left a dead band above a top bar with no status
     * bar left to fill it.
     */
    private fun systemTopInset(): Int =
        if (SystemBarController.isStatusHidden(this)) 0 else systemBar(STATUS_BAR_DIMEN)

    @Suppress("DEPRECATION")
    private fun realScreenSize(): Point =
        Point().also { windowManager.defaultDisplay.getRealSize(it) }

    private fun buildBar(config: NavBarConfig): View {
        val position = config.position
        val fullWidth = config.layout == NavLayout.FULL_WIDTH

        val bar = LinearLayout(this).apply {
            orientation = if (position.isHorizontal) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                // Pure black when docked so the bar disappears into the bezel on an
                // OLED-style panel; a floating pill stays slightly translucent so
                // the wallpaper reads behind it.
                setColor(Color.parseColor(if (fullWidth) "#FF000000" else "#E6000000"))
                cornerRadius = if (fullWidth) 0f else dp(18).toFloat()
            }
            val pad = dp(BAR_PADDING_DP)
            setPadding(pad, pad, pad, pad)
        }

        // One ordered list, so actions and apps can be interleaved in any order.
        // Stepper groups carry a weight of 3: they render three cells, not one.
        val buttons = config.items.mapNotNull { item ->
            when (item) {
                is NavItem.Action -> when {
                    // Two cells, not three: the cluster is compact now, so a
                    // wider span would just pad it with empty space.
                    item.action.isGroup -> stepperGroup(item.action, config) to 2f
                    // Readouts carry text beside the icon, so they need the room.
                    item.action.isReadout -> infoCell(item.action, config) to 2f
                    // One slot like any other button, with the reading stacked
                    // under the glyph rather than set beside it.
                    item.action.isPercent -> percentButton(item.action, config) to 1f
                    else -> actionButton(item.action, config) to 1f
                }

                is NavItem.App -> shortcutButton(item.component, config) to 1f
            }
        }

        buttons.forEach { (button, weight) ->
            if (fullWidth) {
                // Spread evenly so every target is as wide as the bar allows - the
                // thing that actually matters when reaching for it while driving.
                button.layoutParams = LinearLayout.LayoutParams(
                    if (position.isHorizontal) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                    if (position.isHorizontal) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                    weight,
                )
            }
            bar.addView(button)
        }

        // Every docked bar carries a way to fold itself away, at its far end - the
        // right end of a horizontal bar, the bottom of a side bar. Fixed size,
        // outside the even spread above, and drawn quietly: a handle, not another
        // control.
        if (fullWidth) {
            val along = dp(3)
            val across = dp(12)
            val handle = ImageView(this).apply {
                setImageResource(handleIcon(position))
                setColorFilter(CAPTION_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (position.isHorizontal) setPadding(along, across, along, across)
                else setPadding(across, along, across, along)
                contentDescription = "Hide bar"
                isClickable = true
                setOnClickListener { setCollapsed(position, true) }
            }
            bar.addView(
                handle,
                if (position.isHorizontal) {
                    LinearLayout.LayoutParams(dp(HANDLE_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT)
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HANDLE_WIDTH_DP))
                },
            )
        }

        return bar
    }

    private fun actionButton(action: NavAction, config: NavBarConfig): View {
        // A/C is the only action with a second meaning: the tap switches cooling, the
        // long press stops the whole system - the thing the tap gave up doing.
        val longPress = if (action == NavAction.AC_TOGGLE) ::powerDownClimate else null
        val button = iconButton(NavActionIcons.current(action), config, longPress) { perform(action) }
        // Only stateful actions are tracked; the rest keep the plain idle tint.
        if (NavActionState.isStateful(action)) {
            stateButtons.track(action, StateButton(button as ImageView))
        }
        return button
    }

    /**
     * A roof panel: its glyph with how far it is open tucked under it.
     *
     * Occupies one slot, like a plain button rather than like a readout. Set
     * side by side with its name under the number it took two, which on a bar
     * already carrying twenty items cost a fifth of the width for two of them.
     * Stacking drops the name - the two glyphs are a sun through an opening and a
     * drawn blind, which tells them apart without a caption - and spends the
     * height on the number instead.
     *
     * The icon takes whatever the reading leaves, so a thinner bar shrinks the
     * glyph rather than clipping the percentage off the bottom edge.
     */
    private fun percentButton(action: NavAction, config: NavBarConfig): View {
        val room = (config.thicknessDp - BAR_PADDING_DP * 2).coerceAtLeast(20)
        val textSp = (config.iconSizeDp * 0.26f).coerceIn(9f, 15f)
        val iconDp = (room - textSp * LINE_HEIGHT_FACTOR)
            .coerceIn(12f, config.iconSizeDp * 0.62f)

        val icon = ImageView(this).apply {
            setImageResource(NavActionIcons.current(action))
            setColorFilter(ICON_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(iconDp.toInt()), dp(iconDp.toInt()))
        }
        stateButtons.track(action, StateButton(icon))

        val readout = TextView(this).apply {
            text = CarStatus.roofLabel(action)
            setTextColor(ICON_COLOR)
            textSize = textSp
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
        }
        groupReadouts.track(action, readout)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(2)
            setPadding(pad, 0, pad, 0)
            isClickable = true
            setOnClickListener { perform(action) }
            addView(icon)
            addView(readout)
        }
    }

    /**
     * Brings every stateful button in line with the car: green when that function is
     * on, and - where the icon set ships level-specific artwork - the glyph that
     * matches the current level.
     */
    private fun refreshButtonStates() {
        stateButtons.forEach { (action, buttons) ->
            val icon = NavActionIcons.current(action)
            val lit = NavActionState.showsActiveColour(action) && NavActionState.isActive(action)
            val colour = if (lit) ICON_ACTIVE_COLOR else ICON_COLOR
            buttons.forEach { button ->
                if (button.lastIcon != icon) {
                    button.lastIcon = icon
                    button.view.setImageResource(icon)
                }
                if (button.lastColour != colour) {
                    button.lastColour = colour
                    button.view.setColorFilter(colour)
                }
            }
        }
    }

    /**
     * A [-] [live value] [+] stepper, the shape the head unit's own climate bar
     * uses. On a vertical bar the plus goes on top, so "up" means "more".
     */
    private fun stepperGroup(action: NavAction, config: NavBarConfig): View {
        val horizontal = config.position.isHorizontal

        val readout = TextView(this).apply {
            text = CarStatus.stepperLabel(action)
            setTextColor(ICON_COLOR)
            textSize = (config.iconSizeDp * 0.50f).coerceIn(13f, 30f)
            // Drops the font's built-in ascent and descent padding. The value and its
            // caption have to share the bar's height, and that padding was enough to
            // push "TEMP" off the bottom edge.
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            // Reserve room for the widest value ("33°"), so the +/- do not shuffle
            // sideways as the number changes width.
            minWidth = dp(config.iconSizeDp)
        }
        groupReadouts.track(action, readout)

        // Caption under the value, so a bare number is not ambiguous at a glance.
        val caption = TextView(this).apply {
            text = action.stepperCaption
            setTextColor(CAPTION_COLOR)
            textSize = (config.iconSizeDp * 0.30f).coerceIn(9f, 17f)
            gravity = Gravity.CENTER
            maxLines = 1
            letterSpacing = 0.08f
            // Same reason as the value above: without it the pair sits a line apart
            // rather than as one reading with its label tucked underneath.
            includeFontPadding = false
        }
        groupCaptions.track(action, caption)

        // Stacked so the pair reads as one unit and still centres in its cell.
        val valueColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                readout,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                caption,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val decrease = iconButton(R.drawable.ic_step_remove, config, alongPadDp = 0) {
            step(action, -1)
        }
        val increase = iconButton(R.drawable.ic_step_add, config, alongPadDp = 0) {
            step(action, 1)
        }

        return LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            // Children keep their natural size and sit centred as a tight cluster.
            // Weighting them to fill the span pushed the -, value and + to opposite
            // ends of the group, which read as three unrelated buttons rather than
            // one control.
            val first = if (horizontal) decrease else increase
            val last = if (horizontal) increase else decrease

            addView(first)
            addView(
                valueColumn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    // No gap: the +/- carry their own touch padding, and the three
                    // want to read as one control rather than three near each other.
                ),
            )
            addView(last)
        }
    }

    /**
     * A read-only item: icon, then the value, with the percentage or unit beneath.
     *
     * Laid out like the stepper group minus its buttons, so a row of readouts and a
     * row of controls line up. Deliberately not clickable - there is nothing to press,
     * and a pressable-looking readout invites a tap that does nothing.
     */
    private fun infoCell(action: NavAction, config: NavBarConfig): View = when (action) {
        NavAction.INFO_SIGNAL -> strengthCell(action, config, SIGNAL_SYMBOL, SETTINGS_CELLULAR)
        NavAction.INFO_WIFI -> strengthCell(action, config, WIFI_SYMBOL, SETTINGS_WIFI)
        NavAction.INFO_BLUETOOTH -> strengthCell(action, config, BLUETOOTH_SYMBOL, SETTINGS_BLUETOOTH)
        NavAction.INFO_TYRES, NavAction.INFO_TYRES_PSI,
        NavAction.INFO_TYRES_KPA -> tyreCell(config, action)
        // Nothing to open: the indicators are the car's business, not a setting.
        // Drawn half again as large - it is a tell-tale, meant to be caught in
        // peripheral vision rather than read.
        NavAction.INFO_TURN -> strengthCell(action, config, TURN_SYMBOL, emptyList(), scale = 1.5f)

        else -> valueCell(action, config)
    }

    /**
     * A radio's strength: the whole symbol in white with the lit part in green on
     * top, both the same drawable size so the two layers line up exactly.
     *
     * Two layers because the bar tints an icon to one colour - a single file holding
     * lit and unlit parts would come out solid. Using one fixed-size box for both
     * radios is also what keeps them level with each other and with the icons of the
     * other readouts, which the hand-built bars were not.
     */
    private fun strengthCell(
        action: NavAction,
        config: NavBarConfig,
        symbol: RadioSymbol,
        settings: List<String>,
        scale: Float = 1f,
    ): View {
        val outline = symbol.outline
        // Capped to the room the bar actually has, so a scaled-up symbol on a thin
        // bar grows to the edge rather than being clipped by it.
        val room = (dp(config.thicknessDp) - dp(BAR_PADDING_DP) * 2).coerceAtLeast(dp(14))
        val size = dp((config.iconSizeDp * 0.72f * scale).toInt().coerceAtLeast(14))
            .coerceAtMost(room)
        fun layer(res: Int, colour: Int) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(colour)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        val empty = layer(outline, ICON_COLOR)
        // Starts blank, not full: until the first reading lands, claiming perfect
        // signal is worse than claiming none.
        val lit = layer(symbol.lit.first(), ICON_ACTIVE_COLOR)

        val value = TextView(this).apply { isVisible = false }
        val caption = TextView(this).apply { isVisible = false }
        infoCells += InfoCell(action, empty, value, caption, lit)

        val stack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(empty)
            addView(lit)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(stack)
            if (settings.isNotEmpty()) openOnTap(settings)
        }
    }

    /**
     * All four tyres as one item: the pressures sit where the wheels are, around a
     * tyre in the middle.
     *
     * Four separate readouts would take four times the bar and lose the thing that
     * matters about them - which corner is low. Each number carries its own colour,
     * so a soft tyre shows up without reading any of the others.
     */
    private fun tyreCell(config: NavBarConfig, action: NavAction): View {
        // Two lines have to share the bar's height between them, so the ceiling comes
        // from the bar rather than a fixed cap - ask for more than fits and the lower
        // pair gets clipped off the bottom edge.
        val perLine = (config.thicknessDp - BAR_PADDING_DP * 2) / 2f / 1.2f
        val corners = (0 until 4).map {
            TextView(this).apply {
                text = CarInfo.NO_VALUE
                setTextColor(ICON_COLOR)
                textSize = (config.iconSizeDp * 0.39f).coerceIn(9f, perLine)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                // Every reading is three digits, so a fixed advance keeps the four
                // lined up as one block instead of drifting with the values.
                typeface = Typeface.MONOSPACE
            }
        }
        tyreSets += TyreSet(action, corners)

        // Registered like any other readout even though it draws its own values: that
        // is what puts it on the list of signals worth reading and keeps the poll alive
        // on a bar whose only car item is this one.
        infoCells += InfoCell(
            action,
            icon = null,
            value = TextView(this).apply { isVisible = false },
            caption = TextView(this).apply { isVisible = false },
        )

        fun column(top: TextView, bottom: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(top)
            addView(bottom)
        }

        val size = dp((config.iconSizeDp * 0.62f).toInt().coerceAtLeast(12))
        val tyre = ImageView(this).apply {
            setImageResource(R.drawable.ic_tyre)
            setColorFilter(ICON_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(column(corners[0], corners[2]))
            addView(tyre)
            addView(column(corners[1], corners[3]))
        }
    }

    private class TyreSet(val action: NavAction, val corners: List<TextView>)

    private val tyreSets = mutableListOf<TyreSet>()

    /**
     * Repaints the four corners, each coloured against the car's own warning bands.
     *
     * The reading is held in kPa whatever the item displays, so the thresholds are
     * compared once and only the formatting differs between the bar and psi copies.
     */
    private fun refreshTyres() {
        if (tyreSets.isEmpty()) return
        val kpa = CarInfo.tyrePressures

        tyreSets.forEach { set ->
            set.corners.forEachIndexed { index, view ->
                val value = kpa?.getOrNull(index)?.takeIf { it > 0 }
                val text = when {
                    value == null -> CarInfo.NO_VALUE
                    set.action == NavAction.INFO_TYRES_PSI ->
                        Math.round(value / KPA_PER_PSI).toString()

                    set.action == NavAction.INFO_TYRES_KPA -> value.toString()
                    else -> String.format("%.1f", value / KPA_PER_BAR)
                }
                if (view.text != text) view.text = text
                view.setTextColor(
                    when {
                        value == null -> ICON_COLOR
                        value < CarBus.TYRE_CRITICAL_KPA -> ICON_LOW_COLOR
                        value < CarBus.TYRE_LOW_KPA -> ICON_WARN_COLOR
                        else -> ICON_COLOR
                    }
                )
            }
        }
    }

    private fun valueCell(action: NavAction, config: NavBarConfig): View {
        val iconRes = NavActionIcons.onBar(action)
        val icon = if (iconRes == 0) null else ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(ICON_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val size = dp((config.iconSizeDp * 0.72f).toInt().coerceAtLeast(14))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        // The clock, date, gear and speed are what the eye goes to first on a status
        // bar, and none of them carry an icon to anchor them, so they are set larger
        // and bold. The gear and the speed have to be readable at a glance from a
        // driving position, which is the same requirement by another name.
        val emphasised = action == NavAction.INFO_CLOCK || action == NavAction.INFO_DATE ||
            action == NavAction.INFO_GEAR || action == NavAction.INFO_SPEED
        val base = if (emphasised) {
            (config.iconSizeDp * 0.50f).coerceIn(13f, 30f)
        } else {
            (config.iconSizeDp * 0.36f).coerceIn(11f, 22f)
        }
        // The two the driver reads at speed are larger again than the clock.
        val scale = when (action) {
            NavAction.INFO_GEAR -> 1.73f
            NavAction.INFO_SPEED -> 1.56f
            else -> 1f
        }

        // An enlarged figure with a unit stacked under it has two lines to fit into
        // the bar's height, so the ceiling comes from the bar rather than a fixed cap
        // - the tyre block sizes itself the same way. Ask for more than fits and the
        // lower line is clipped off the bottom edge, which is what an enlarged speed
        // did to its own "km/h". The unit takes its small share first and the figure
        // gets the rest, since a label too small to read helps nobody.
        val hasUnit = scale > 1f && CarInfo.captionFor(action) != null
        val stackBudget = (config.thicknessDp - BAR_PADDING_DP * 2) / LINE_HEIGHT_FACTOR
        val unitSize = (base * UNIT_CAPTION_SCALE).coerceAtLeast(MIN_UNIT_SP)
        val size = if (hasUnit) {
            (base * scale).coerceAtMost(stackBudget - unitSize)
        } else {
            base * scale
        }

        val value = TextView(this).apply {
            text = CarInfo.valueFor(action)
            setTextColor(ICON_COLOR)
            textSize = size
            if (emphasised) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            // The stack has to fit a fixed-height bar, and the font's own padding is
            // several dp of nothing above and below every line.
            includeFontPadding = false
        }

        // The second line matches the first throughout - a percentage under a range,
        // and IN/OUT under a temperature, all carry the same weight. The exception is
        // a unit against an enlarged number: "km/h" labels the speed rather than being
        // a second reading, so it stays back and lets the figure carry the cell.
        val caption = TextView(this).apply {
            text = CarInfo.captionFor(action).orEmpty()
            setTextColor(ICON_COLOR)
            textSize = if (hasUnit) unitSize else size
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            isVisible = text.isNotEmpty()
        }
        infoCells += InfoCell(action, icon, value, caption)

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(value)
            addView(caption)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            icon?.let { addView(it) }
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    // No icon means no gap to leave beside one. Tight where there is
                    // one, so the symbol reads as part of the value it labels rather
                    // than as a separate thing next to it.
                ).apply { if (icon != null) marginStart = dp(2) },
            )
        }
    }

    /**
     * Makes a readout open a settings screen, taking the first intent the unit
     * actually has - BYD's Settings does not carry every AOSP screen.
     */
    private fun LinearLayout.openOnTap(actions: List<String>) {
        isClickable = true
        setBackgroundResource(R.drawable.nav_button_background)
        setOnClickListener {
            // A broadcast target opens a panel that belongs to no activity - BYD's
            // Bluetooth pairing sheet is raised that way, by a receiver.
            val broadcast = actions.firstOrNull { it.startsWith(BROADCAST_PREFIX) }
            if (broadcast != null) {
                runCatching {
                    sendBroadcast(Intent(broadcast.removePrefix(BROADCAST_PREFIX)))
                }
                return@setOnClickListener
            }

            val intent = actions.firstNotNullOfOrNull { settingsIntent(it) }
            if (intent != null) {
                runCatching { startActivity(intent) }
            } else {
                Toast.makeText(this@NavBarService, "No settings screen for that", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /**
     * Names the activity outright instead of letting the platform ask which one.
     *
     * Two apps answer the mobile-network intent on this unit - AOSP's dialer settings
     * and Qualcomm's - so a bare intent put a chooser in front of what should be a
     * single tap. BYD's own screens win where it has one, since those are the ones
     * the rest of the car uses.
     */
    private fun settingsIntent(target: String): Intent? {
        // A "package/class" entry names a screen outright, for the ones this ROM has
        // but publishes under no standard action - Bluetooth pairing among them.
        if ('/' in target) {
            val component = ComponentName.unflattenFromString(target) ?: return null
            val exists = runCatching {
                packageManager.getActivityInfo(component, 0) != null
            }.getOrDefault(false)
            return if (exists) {
                Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                null
            }
        }

        val matches = runCatching {
            packageManager.queryIntentActivities(Intent(target), 0)
        }.getOrDefault(emptyList())

        val chosen = matches.firstOrNull { it.activityInfo.packageName.startsWith("com.byd") }
            ?: matches.firstOrNull { it.activityInfo.packageName.startsWith("com.android") }
            ?: matches.firstOrNull()
            ?: return null

        return Intent(target)
            .setClassName(chosen.activityInfo.packageName, chosen.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun shortcutButton(component: ComponentName, config: NavBarConfig): View {
        val view = iconButton(0, config) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } as ImageView
        runCatching {
            view.setImageDrawable(packageManager.getActivityIcon(component))
            view.clearColorFilter()
        }
        return view
    }

    /**
     * Icon size is its own setting rather than a function of bar thickness, so a
     * thick bar with small icons (or the reverse) is expressible.
     *
     * FIT_CENTER scales the drawable into the space left after padding, so padding
     * the across-the-bar axis down to exactly the requested size is what pins the
     * icon at that size - this works whether the cell is weighted (full width) or
     * sized explicitly (pill).
     */
    private fun iconButton(
        iconRes: Int,
        config: NavBarConfig,
        onLongClick: (() -> Unit)? = null,
        /** Padding along the bar. Trimmed for a stepper, to sit close to its value. */
        alongPadDp: Int = 6,
        onClick: () -> Unit,
    ): View {
        val cell = (dp(config.thicknessDp) - dp(BAR_PADDING_DP) * 2).coerceAtLeast(dp(24))
        val icon = dp(config.iconSizeDp).coerceAtMost(cell)
        val crossPad = ((cell - icon) / 2).coerceAtLeast(0)
        val alongPad = dp(alongPadDp)

        return ImageView(this).apply {
            val tinted = iconRes != 0
            if (tinted) {
                setImageResource(iconRes)
                setColorFilter(ICON_COLOR)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (config.position.isHorizontal) {
                setPadding(alongPad, crossPad, alongPad, crossPad)
            } else {
                setPadding(crossPad, alongPad, crossPad, alongPad)
            }
            layoutParams = LinearLayout.LayoutParams(cell, cell).apply {
                val m = dp(2)
                setMargins(m, m, m, m)
            }
            isClickable = true
            setBackgroundResource(R.drawable.nav_button_background)
            setOnClickListener { onClick() }
            // Consumed, so the tap that led into the long press does not also fire.
            onLongClick?.let { handler -> setOnLongClickListener { handler(); true } }

            // App icons keep their own colours; only monochrome glyphs get tinted,
            // and the accent tint is what makes a press obvious on a small glyph.
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        if (tinted) {
                            (view as ImageView).setColorFilter(ICON_PRESSED_COLOR)
                            // The press paints round the back of the state cache, so
                            // forget what was drawn or the release would decide
                            // nothing had changed and leave the button lit blue.
                            forgetTint(view)
                        }

                    // Restore the state colour, not the idle one: releasing the A/C
                    // button while the A/C is on must leave it green.
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        if (tinted) refreshButtonStates().also {
                            val tracked = stateButtons.values
                                .any { buttons -> buttons.any { it.view === view } }
                            if (!tracked) (view as ImageView).setColorFilter(ICON_COLOR)
                        }
                }
                // Never consume: the click listener above still has to fire.
                false
            }
        }
    }

    private fun perform(action: NavAction) {
        when (action) {
            NavAction.BACK ->
                if (!NavAccessibilityService.back()) repairAccessibility { NavAccessibilityService.back() }

            NavAction.RECENTS ->
                if (!NavAccessibilityService.recents()) repairAccessibility { NavAccessibilityService.recents() }

            NavAction.HOME -> startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            // Straight to the drawer, which is the one place everything on the unit
            // is listed - the home grid only holds what was put on it.
            NavAction.APPS -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_ALL_APPS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            NavAction.VOLUME_UP -> AudioControl.volumeUp(this)
            NavAction.VOLUME_DOWN -> AudioControl.volumeDown(this)
            NavAction.MUTE -> AudioControl.toggleMute(this)

            NavAction.TEMP_UP -> car { CarControl.nudgeTemperature(1) }
            NavAction.TEMP_DOWN -> car { CarControl.nudgeTemperature(-1) }
            NavAction.FAN_UP -> car { CarControl.nudgeFan(1) }
            NavAction.FAN_DOWN -> car { CarControl.nudgeFan(-1) }
            // Recolour on the press, not on the reply: a round trip plus the car's
            // own settling delay made the green linger after switching off.
            NavAction.AC_TOGGLE -> {
                val systemOff = !CarStatus.climateOn
                // With the system down the button can only mean "start cooling",
                // whatever the car still remembers about the compressor - it may go
                // on reporting the last setting from before the shutdown.
                val target = if (systemOff) true else !CarStatus.compressorOn
                CarStatus.previewCompressor(target)
                // Asking for cooling on a dead system starts it, so the readouts and
                // vents light up with the button rather than a poll later.
                if (systemOff) CarStatus.previewClimateStart()
                refreshButtonStates()
                refreshReadouts()
                car { CarControl.setAc(target, startSystem = systemOff) }
            }
            NavAction.RECIRCULATE -> car { CarControl.toggleRecirculation() }
            NavAction.DEFROST -> car { CarControl.toggleDefrost() }
            NavAction.DEFROST_REAR -> car { CarControl.toggleRearDefrost() }

            NavAction.AIRFLOW_FACE -> car { CarControl.toggleVent(Vent.FACE) }
            NavAction.AIRFLOW_FEET -> car { CarControl.toggleVent(Vent.FOOT) }
            NavAction.AIRFLOW_SCREEN -> car { CarControl.toggleVent(Vent.SCREEN) }
            NavAction.STEERING_HEAT -> car { CarControl.toggleSteeringHeat() }

            NavAction.HUD_POWER -> car { CarControl.toggleHud() }
            NavAction.HUD_NAV -> car { CarControl.toggleHudNavigation() }
            NavAction.HUD_ADAS -> car { CarControl.toggleHudAdas() }
            NavAction.HUD_THEME -> car { CarControl.toggleHudTheme() }
            NavAction.HUD_BRIGHTNESS_UP -> car { CarControl.nudgeHudBrightness(1) }
            NavAction.HUD_BRIGHTNESS_DOWN -> car { CarControl.nudgeHudBrightness(-1) }
            NavAction.HUD_HEIGHT_UP -> car { CarControl.nudgeHudHeight(1) }
            NavAction.HUD_HEIGHT_DOWN -> car { CarControl.nudgeHudHeight(-1) }

            NavAction.SUNROOF -> car { CarControl.toggleSunroof() }
            NavAction.SUNSHADE -> car { CarControl.toggleSunshade() }

            // Groups build their own +/- buttons; they are never "pressed" directly,
            // and a readout has nothing to press at all.
            NavAction.TEMP_GROUP, NavAction.FAN_GROUP,
            NavAction.HUD_BRIGHTNESS_GROUP, NavAction.HUD_HEIGHT_GROUP -> Unit
            NavAction.INFO_CLOCK, NavAction.INFO_DATE, NavAction.INFO_SIGNAL,
            NavAction.INFO_WIFI, NavAction.INFO_BLUETOOTH, NavAction.INFO_GEAR,
            NavAction.INFO_LIGHTS, NavAction.INFO_TURN, NavAction.INFO_RANGE_EV,
            NavAction.INFO_RANGE_FUEL, NavAction.INFO_TEMP_IN,
            NavAction.INFO_TEMP_OUT, NavAction.INFO_TYRES,
            NavAction.INFO_TYRES_PSI, NavAction.INFO_TYRES_KPA,
            NavAction.INFO_PM25, NavAction.INFO_SPEED -> Unit
            NavAction.SEAT_HEAT -> car { CarControl.cycleSeatHeat() }
            NavAction.SEAT_VENT -> car { CarControl.cycleSeatVent() }
            NavAction.SEAT_HEAT_PASSENGER -> car { CarControl.cycleSeatHeatPassenger() }
            NavAction.SEAT_VENT_PASSENGER -> car { CarControl.cycleSeatVentPassenger() }
        }
    }

    /**
     * Runs a car command off the main thread and reports the outcome - the new
     * setpoint, or why it did not take. A climate button that silently does nothing
     * is worse than useless while driving.
     */
    private fun car(work: () -> String) {
        CarControl.run(
            onResult = {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                // The command already updated state, so recolour without waiting
                // for the next poll.
                refreshReadouts()
                refreshButtonStates()
            },
            work = work,
        )
    }

    /**
     * A dead Back button means the service is listed but not connected. Rebind and
     * retry the press, so the button works on the second tap instead of needing adb.
     *
     * Rate limited: if the rebind does not take, a bar full of presses must not turn
     * into a stream of accessibility restarts.
     */
    private fun repairAccessibility(retry: () -> Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepairAt < REPAIR_COOLDOWN_MS) {
            warnAccessibility()
            return
        }
        lastRepairAt = now

        AccessibilityGuard.rebind(this) {
            // The bind takes a moment past the setting write, so give it one before
            // deciding it failed.
            pollHandler.postDelayed({
                if (!retry()) warnAccessibility()
            }, REBIND_SETTLE_MS)
        }
    }

    private var lastRepairAt = 0L

    /**
     * Repairs a half-bound accessibility service before anyone presses anything.
     *
     * Updating the app tears the binding down, and on this ROM it does not reliably
     * come back: the service stays in the platform's "Binding services" list and
     * never reaches "Bound". Nothing surfaces that - the enabled list still names
     * us - so the first sign is a dead Back button, which is a poor way to find out
     * while driving.
     *
     * The first check waits [ACCESSIBILITY_GRACE_MS], because connecting legitimately
     * takes a moment after a boot or an install; only then is a rebind forced, and
     * the result is checked again rather than assumed.
     *
     * The ladder deliberately does *not* honour [REPAIR_COOLDOWN_MS]. That cooldown
     * is there so a bar full of presses cannot become a stream of rebinds, and it
     * belongs to the press path in [repairAccessibility]. Applied here it stopped
     * the ladder dead: each rung follows the last by [REBIND_SETTLE_MS], which is
     * well inside the cooldown, so attempt 1 always bailed and the process restart
     * on attempt 2 was unreachable. Found on the car, 26 Sep 2026, with a binding
     * stuck overnight and the repair that should have cleared it never having run.
     * The ladder is bounded by [ACCESSIBILITY_REPAIR_ATTEMPTS], and the restart by
     * its own wall-clock cooldown, so neither needs this one.
     */
    private fun verifyAccessibility(attempt: Int) {
        // One ladder at a time. Both onCreate and the enabled-list observer start
        // one, and that list is rewritten by other apps on this unit, so without
        // this they stack and race each other to the restart.
        if (attempt == 0) {
            if (verifyingAccessibility) return
            verifyingAccessibility = true
        }
        pollHandler.postDelayed({
            if (NavAccessibilityService.isConnected) {
                verifyingAccessibility = false
                return@postDelayed
            }

            // Not being listed at all is a different problem: the user has to grant
            // it, and no amount of rebinding substitutes for that.
            if (!AccessibilityGuard.ensureEnabled(this)) {
                verifyingAccessibility = false
                warnAccessibility()
                return@postDelayed
            }

            // The toggle has had its chances. What is left is not an absent
            // binding but a stuck one, and only dropping our process shifts that.
            if (attempt >= ACCESSIBILITY_REPAIR_ATTEMPTS) {
                verifyingAccessibility = false
                Log.w(TAG, "accessibility still unbound after $attempt rebinds")
                restartToClearBinding()
                return@postDelayed
            }

            Log.i(TAG, "accessibility unbound; rebind attempt ${attempt + 1}")
            lastRepairAt = SystemClock.elapsedRealtime()
            AccessibilityGuard.rebind(this) { verifyAccessibility(attempt + 1) }
        }, if (attempt == 0) ACCESSIBILITY_GRACE_MS else REBIND_SETTLE_MS)
    }

    /** Guards against overlapping repair ladders; see [verifyAccessibility]. */
    private var verifyingAccessibility = false

    /**
     * Drops our own process, which is the only thing that shifts an accessibility
     * binding the platform has left half-open.
     *
     * Measured on this unit, 25 Sep 2026, with our entry sitting in the platform's
     * "Binding" queue and never reaching "Bound": toggling the accessibility master
     * switch did nothing whatever - the other services stayed bound straight
     * through it and no bind was even attempted - and taking our entry out of the
     * enabled list did not dislodge it either. Killing the process made the
     * half-open connection die, and the next start bound on the first try.
     *
     * The alarm is set before the kill, and committed, not applied: a process
     * about to die finishes nothing it has merely queued. A sticky service is
     * usually restarted on its own, but "usually" is not good enough for the thing
     * that draws the bar.
     *
     * Rate limited on wall-clock time, which survives the restart that elapsed
     * time would not. A setup that is broken for some other reason would otherwise
     * have the launcher kill itself every few seconds for ever.
     */
    private fun restartToClearBinding() {
        val prefs = getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong(KEY_LAST_SELF_RESTART, 0L)
        if (since in 0 until SELF_RESTART_COOLDOWN_MS) {
            // We already did this, came back, and are still not bound. The binding
            // is not what is wrong, so say so rather than loop.
            Log.w(TAG, "still unbound ${since}ms after a restart; not killing again")
            warnAccessibility()
            return
        }
        prefs.edit().putLong(KEY_LAST_SELF_RESTART, now).commit()

        val restart = PendingIntent.getForegroundService(
            this,
            0,
            Intent(this, NavBarService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                now + SELF_RESTART_DELAY_MS,
                restart,
            )
        }.onFailure { Log.w(TAG, "could not schedule the restart: ${it.message}") }

        Log.i(TAG, "accessibility stuck binding; restarting our process to clear it")
        Process.killProcess(Process.myPid())
    }

    private fun warnAccessibility() {
        Toast.makeText(
            this,
            getString(R.string.accessibility_needed, getString(R.string.app_name)),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Navigation bar",
                NotificationManager.IMPORTANCE_MIN,
            )
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigation bar active")
            .setSmallIcon(R.drawable.ic_home)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Briefly accents a readout so a stepper press is acknowledged like a button,
     * then hands the colour back to [refreshReadouts] - restoring a fixed white
     * here would undo the A/C-on green.
     */
    private fun flash(view: TextView) {
        view.setTextColor(ICON_PRESSED_COLOR)
        view.postDelayed({ refreshReadouts() }, FLASH_MS)
    }

    private fun systemBar(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "navbar"
        private const val NOTIFICATION_ID = 42
        private const val NAV_BAR_DIMEN = "navigation_bar_height"
        private const val STATUS_BAR_DIMEN = "status_bar_height"

        /** Inset between the bar's edge and its buttons. */
        private const val BAR_PADDING_DP = 6

        /**
         * A unit caption's size as a fraction of the normal readout size - tied to
         * that rather than to the enlarged value above it, so growing the number does
         * not drag its label up with it.
         */
        private const val UNIT_CAPTION_SCALE = 0.45f

        /** Below this a unit is decoration, not a label, so the figure yields instead. */
        private const val MIN_UNIT_SP = 8f

        /**
         * A line's height as a multiple of its text size, with the font's own padding
         * switched off. Roboto's ascent and descent come to about 1.17em; the rest is
         * headroom, so a stack that fits on paper is not left one rounded pixel from
         * clipping on the glass.
         */
        private const val LINE_HEIGHT_FACTOR = 1.23f

        /**
         * The API pushes nothing, so a change made anywhere else - BYD's own climate
         * panel, OverDrive, the car itself - is only noticed on the next poll. At
         * five seconds that showed up as a two-to-three second lag before the bar
         * caught up. A loopback request costs a few milliseconds, so polling once a
         * second is effectively free and keeps the bar in step with the car.
         */
        private const val POLL_INTERVAL_MS = 1_000L

        /** Half a flash: roughly the rate a car's own indicator ticks at. */
        private const val BLINK_INTERVAL_MS = 380L

        /** Long enough that a jammed rebind cannot be retriggered by tapping. */
        private const val REPAIR_COOLDOWN_MS = 15_000L

        /** The platform needs a beat after the toggle before the service is live. */
        private const val REBIND_SETTLE_MS = 1_200L

        /**
         * How long the service is given to connect on its own before we decide it is
         * stuck. Generous: at boot the platform has a great deal else to do first.
         */
        private const val ACCESSIBILITY_GRACE_MS = 6_000L

        /** Past this the toggle is not working, and the process restart takes over. */
        private const val ACCESSIBILITY_REPAIR_ATTEMPTS = 2

        private const val TAG = "NavBarService"

        /**
         * Diagnostic state, deliberately in its own file: the user's settings are
         * backed up and diffed around every install and have no business carrying
         * a repair timestamp.
         */
        private const val REPAIR_PREFS = "navbar_repair"
        private const val KEY_LAST_SELF_RESTART = "last_self_restart"

        /** Long enough for the platform to have finished with the dead process. */
        private const val SELF_RESTART_DELAY_MS = 2_500L

        /**
         * Wall-clock, because elapsed time would not survive the restart this
         * guards against. Generous: a restart that did not take is a fault to
         * report, not something to keep retrying at.
         */
        private const val SELF_RESTART_COOLDOWN_MS = 10 * 60 * 1000L

        /** Lit layers indexed by how many parts are green; 0 is blank. */
        private val WIFI_SYMBOL = RadioSymbol(
            outline = R.drawable.ic_wifi_4,
            off = R.drawable.ic_wifi_off,
            lit = intArrayOf(
                R.drawable.ic_wifi_0,
                R.drawable.ic_wifi_1,
                R.drawable.ic_wifi_2,
                R.drawable.ic_wifi_3,
                R.drawable.ic_wifi_4,
            ),
        )

        private val SIGNAL_SYMBOL = RadioSymbol(
            outline = R.drawable.ic_signal_5,
            off = R.drawable.ic_signal_off,
            lit = intArrayOf(
                R.drawable.ic_signal_0,
                R.drawable.ic_signal_1,
                R.drawable.ic_signal_2,
                R.drawable.ic_signal_3,
                R.drawable.ic_signal_4,
                R.drawable.ic_signal_5,
            ),
        )

        /** Two steps only: paired and connected, or powered with nothing attached. */
        private val BLUETOOTH_SYMBOL = RadioSymbol(
            outline = R.drawable.ic_bt_1,
            off = R.drawable.ic_bt_off,
            lit = intArrayOf(R.drawable.ic_bt_0, R.drawable.ic_bt_1),
        )

        /** Tried in order; this ROM's Settings does not carry all of them. */
        /**
         * Indexed by which sides are flashing, so the index is a bit pair rather than
         * a level: 1 left, 2 right, 3 both. No struck-through state - a car with no
         * reading just shows the pair of outlines.
         */
        private val TURN_SYMBOL = RadioSymbol(
            outline = R.drawable.ic_turn_3,
            off = R.drawable.ic_turn_3,
            lit = intArrayOf(
                R.drawable.ic_turn_0,
                R.drawable.ic_turn_1,
                R.drawable.ic_turn_2,
                R.drawable.ic_turn_3,
            ),
        )

        private const val BROADCAST_PREFIX = "broadcast:"

        /**
         * BYD's pairing sheet - paired devices, available devices, the radio switch -
         * is raised by a broadcast to com.byd.btsetting's receiver, not by starting an
         * activity. This ROM publishes no BLUETOOTH_SETTINGS handler at all, and the
         * wireless fallback lands on Wi-Fi, so there is nothing else to aim at.
         */
        private val SETTINGS_BLUETOOTH = listOf(
            BROADCAST_PREFIX + "com.byd.bluetooth.action.OPEN_BLUETOOTH_SETTING",
        )

        private val SETTINGS_WIFI = listOf(
            android.provider.Settings.ACTION_WIFI_SETTINGS,
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        )

        private val SETTINGS_CELLULAR = listOf(
            android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
            android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS,
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        )

        /** Matches OnSurface / Accent in the Compose theme. */
        private val ICON_COLOR = Color.parseColor("#E8EAED")
        private val ICON_PRESSED_COLOR = Color.parseColor("#4FC3F7")

        /** Green means "this function is currently on" - A/C, defrost, seat heat. */
        private val ICON_ACTIVE_COLOR = Color.parseColor("#4ADE80")

        /** Main beam, in the blue every dashboard uses for it. */
        private val ICON_BEAM_COLOR = Color.parseColor("#4FA8FF")

        /** A tank or battery running down: amber, then red. */
        private val ICON_WARN_COLOR = Color.parseColor("#FBBF24")
        private val ICON_LOW_COLOR = Color.parseColor("#F87171")

        private const val LEVEL_LOW_PERCENT = 20

        private const val KPA_PER_PSI = 6.894757f
        private const val KPA_PER_BAR = 100f
        private const val LEVEL_WARN_PERCENT = 40

        /** Captions are deliberately dimmer than their value. */
        private val CAPTION_COLOR = Color.parseColor("#9AA3AD")
        private val CAPTION_ACTIVE_COLOR = Color.parseColor("#8C4ADE80")

        /** Long enough to register, short enough not to lag a rapid step. */
        private const val FLASH_MS = 180L

        /**
         * How long to let the system-bar setting settle after someone else writes
         * it before putting it back. Another launcher writes it more than once as
         * it comes forward; answering each write would be a tug of war.
         */
        private const val POLICY_SETTLE_MS = 800L

        /** The fold handle at the end of a bar: narrow, but still a target. */
        private const val HANDLE_WIDTH_DP = 26

        /**
         * The restore tab left in the corner once the bar is folded. Wide enough
         * to hit, thin enough that the app underneath loses almost nothing.
         */
        private const val TAB_WIDTH_DP = 52
        private const val TAB_HEIGHT_DP = 24

        /**
         * The buttons that carry a live percentage, resolved once. [refreshReadouts]
         * runs every second across four overlay windows, and filtering the action
         * list there would allocate on that path for a set that never changes.
         */
        private val PERCENT_ACTIONS = NavAction.entries.filter { it.isPercent }

        const val ACTION_STOP = "com.example.esmerui.NAVBAR_STOP"
        const val ACTION_PERFORM = "com.example.esmerui.NAVBAR_PERFORM"
        const val ACTION_RESERVE = "com.example.esmerui.NAVBAR_RESERVE"
        const val ACTION_REPAIR = "com.example.esmerui.NAVBAR_REPAIR"
        const val EXTRA_ACTION = "action"

        /** Debug only: exercises the process restart that clears a stuck binding. */
        fun repairAccessibility(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, NavBarService::class.java).setAction(ACTION_REPAIR)
                )
            }
        }

        /** Asks the bar to re-reserve the edges for whatever is now in front. */
        fun reserveEdges(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, NavBarService::class.java).setAction(ACTION_RESERVE)
                )
            }
        }

        /** Runs a bar action from elsewhere in the app - the home grid, chiefly. */
        fun perform(context: Context, action: NavAction) {
            context.startService(
                Intent(context, NavBarService::class.java)
                    .setAction(ACTION_PERFORM)
                    .putExtra(EXTRA_ACTION, action.name)
            )
        }

        /** Starts, restarts or stops the bar to match current settings. */
        fun sync(context: Context) {
            val settings = LauncherSettings.get(context)
            val intent = Intent(context, NavBarService::class.java)
            if (settings.renderableNavBars.isNotEmpty()) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent.setAction(ACTION_STOP))
            }
        }
    }
}
