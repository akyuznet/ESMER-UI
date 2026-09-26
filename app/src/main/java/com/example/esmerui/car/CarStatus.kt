package com.example.esmerui.car

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esmerui.launcher.NavAction

/** What a roof panel is doing, which is what decides its button's next press. */
enum class PanelMotion { IDLE, OPENING, CLOSING }

/**
 * One roof panel's position and whether it is travelling.
 *
 * The travel matters as much as the position, because the button is a three-way
 * press rather than a toggle: start it, stop it where it is, send it back. That
 * is how the overhead switch behaves, and stopping part-way is the only way to
 * leave the glass cracked open - as well as the thing a hand reaches for when the
 * panel is going somewhere it should not.
 *
 * Motion is set the moment a command goes out rather than waiting for the car to
 * report movement: the second press of a double-tap arrives long before the first
 * one has moved anything, and it still has to mean "stop". It is also inferred
 * from the readings, so a panel started on the overhead switch is followed too.
 */
class MovingPanel(
    /**
     * How long after a command the readings are not to be believed. Zero for
     * anything that reports its position as it goes. The tailgate reports only
     * shut or open: it says "open" the moment it unlatches and "shut" only once
     * it has latched again, so for the seconds in between neither an arrival nor
     * a stall can be read off it, and a press during that time must still mean
     * "stop".
     */
    private val minTravelMs: Long = 0L,
) {

    @Volatile
    var percent: Int? = null
        private set

    @Volatile
    var motion: PanelMotion = PanelMotion.IDLE
        private set

    /** Where we last sent it, cleared once it arrives or is stopped. */
    @Volatile
    private var target: Int? = null

    /** When the reading last moved, for noticing that travel has finished. */
    @Volatile
    private var changedAt = 0L

    /** When travel began, so the pause before the motor picks up is not a stall. */
    @Volatile
    private var startedAt = 0L

    val isOpen: Boolean get() = (percent ?: 0) > 0

    /** Takes the car's own reading and works out whether the panel is still moving. */
    fun observe(reading: Int) {
        val now = SystemClock.elapsedRealtime()
        val previous = percent
        percent = reading

        if (previous != null && reading != previous) {
            changedAt = now
            if (motion == PanelMotion.IDLE) {
                // Moving without us having asked: the overhead switch. Follow it, so
                // a press still means "stop" rather than starting a second move.
                motion = if (reading > previous) PanelMotion.OPENING else PanelMotion.CLOSING
                startedAt = now
            }
        }

        if (motion == PanelMotion.IDLE) return

        // A hatch that reports only shut or open has nothing to say until it has
        // had time to get there; see [minTravelMs].
        if (now - startedAt < minTravelMs) return

        // "Fully open" is read with some slack: a window reports 98 at the bottom
        // of its travel, never 100, and without the slack it would count as still
        // moving until the stall timer gave up on it.
        val arrived = reading == target ||
            (motion == PanelMotion.OPENING && reading >= CarBus.ROOF_OPEN_PERCENT - FULL_OPEN_SLACK) ||
            (motion == PanelMotion.CLOSING && reading <= CarBus.ROOF_CLOSED_PERCENT)
        // Gone quiet part-way: an obstacle, or someone else's stop. Either way it is
        // no longer moving, so the next press should start it rather than stop it.
        val stalled = now - startedAt > START_GRACE_MS && now - changedAt > STALL_MS
        if (arrived || stalled) settle()
    }

    /** Records a move we have just sent, before the car has had time to react. */
    fun commanded(to: Int) {
        val now = SystemClock.elapsedRealtime()
        target = to
        startedAt = now
        changedAt = now
        motion = if (to > (percent ?: 0)) PanelMotion.OPENING else PanelMotion.CLOSING
    }

    fun stopped() = settle()

    private fun settle() {
        motion = PanelMotion.IDLE
        target = null
    }

    private companion object {
        /** A panel takes a moment to start; that pause is not a stall. */
        const val START_GRACE_MS = 3_000L

        /** The poll runs once a second, so three quiet passes means it has stopped. */
        const val STALL_MS = 3_000L

        /** How short of 100 a panel may stop and still count as fully open; windows read 98 to 100. */
        const val FULL_OPEN_SLACK = 5
    }
}

/**
 * Cached vehicle state for the bar's live readouts.
 *
 * The stepper groups show the car's actual setpoint and fan level, so something
 * has to poll - the API pushes nothing. Values are plain @Volatile fields because
 * the bar reads them from views, not from Compose; [available] is Compose state
 * because the settings screen does observe it.
 */
object CarStatus {

    var available by mutableStateOf(false)
        private set

    @Volatile
    var setpointC: Int? = null

    @Volatile
    var fanLevel: Int? = null

    /**
     * Climate power: whether the system is running at all.
     *
     * Distinct from [compressorOn] - the blower can be pushing cabin air with the
     * compressor idle. This is the one that says whether any air is moving, so it
     * drives the readouts and the vent buttons.
     */
    @Volatile
    var climateOn: Boolean = false

    /** The compressor itself - cooling on or off, which is what the A/C button means. */
    @Volatile
    var compressorOn: Boolean = false

    /**
     * Recirculation, defrost and steering heat are not reported by /state, so these
     * mirror the last command we sent rather than the car. Good enough to colour a
     * button; a stale flag costs one wasted press.
     */
    @Volatile
    var recirculateOn: Boolean = false

    @Volatile
    var defrostOn: Boolean = false

    @Volatile
    var defrostRearOn: Boolean = false

    /** Read straight from the car's feature bus, not from the REST snapshot. */
    @Volatile
    var airflow: Airflow? = null

    @Volatile
    var steeringHeatOn: Boolean = false

    @Volatile
    var seatHeatLevel: Int = 0

    @Volatile
    var seatVentLevel: Int = 0

    @Volatile
    var seatHeatLevelPassenger: Int = 0

    @Volatile
    var seatVentLevelPassenger: Int = 0

    // ---- Head-up display ---------------------------------------------------

    /**
     * Set by the bar while a HUD control is on screen. The six HUD signals are only
     * worth their round trips then; a bar without one skips them entirely.
     */
    @Volatile
    var hudWanted: Boolean = false

    /** Whether the car has a HUD at all; null until the bus has answered once. */
    @Volatile
    var hudFitted: Boolean? = null

    /**
     * True once the poll has filled the HUD fields at least once. Before that a
     * toggle has nothing to flip from, so it asks the car directly instead.
     */
    @Volatile
    var hudSynced: Boolean = false
        private set

    @Volatile
    var hudOn: Boolean = false

    @Volatile
    var hudNavigationOn: Boolean = false

    @Volatile
    var hudAdasOn: Boolean = false

    /** [CarBus.HUD_THEME_CLASSIC] or [CarBus.HUD_THEME_SNOW]; null until read. */
    @Volatile
    var hudTheme: Int? = null

    @Volatile
    var hudBrightness: Int? = null

    @Volatile
    var hudHeight: Int? = null

    /**
     * Until when the poll leaves the HUD values alone after one of our writes. The
     * car echoes a HUD change a poll or two late, and taking its word in between
     * would snap a readout back to the old value before settling on the new one.
     */
    @Volatile
    private var hudHoldUntil = 0L

    private const val HUD_HOLD_MS = 3_000L

    // ---- Roof, windows, tailgate ---------------------------------------------

    /**
     * Set by the bar while any travelling panel's control is on screen, exactly
     * like [hudWanted]. Named for the roof, which was the first of them.
     */
    @Volatile
    var roofWanted: Boolean = false

    /** Null until the bus has answered once; false on a car without that panel. */
    @Volatile
    var sunroofFitted: Boolean? = null

    @Volatile
    var sunshadeFitted: Boolean? = null

    @Volatile
    var tailgateFitted: Boolean? = null

    /**
     * The glass and the blind, each tracking its own position and travel.
     *
     * The percentage is never held optimistically, unlike every other control
     * here. A panel takes several seconds to travel and the poll is the only thing
     * that shows it moving, so pinning the number to the commanded value would
     * hide the one part worth watching - and would read 100% with the glass still
     * at 30. What *is* held is the motion, which a press needs to know about
     * before the car has reported anything.
     */
    val sunroof = MovingPanel()

    val sunshade = MovingPanel()

    /** The four side windows, tracked the same way. */
    val windows: Map<CarBus.CarWindow, MovingPanel> =
        CarBus.CarWindow.entries.associateWith { MovingPanel() }

    /**
     * The four together, for the one button that drives them all. Its reading is
     * the widest open of the four, so it reads 0 only when every window is shut;
     * its motion is whatever the all-windows press last sent, or whichever
     * window moved first if the car did it.
     */
    val allWindows = MovingPanel()

    /**
     * The tailgate, which reports shut or open and nothing between - tracked as
     * 0 or 100, with the travel time a hatch needs before either is believed.
     */
    val tailgate = MovingPanel(minTravelMs = TAILGATE_TRAVEL_MS)

    /** A powered hatch takes about this long end to end; measured on none yet. */
    private const val TAILGATE_TRAVEL_MS = 10_000L

    /**
     * Safety net for a pending write that the car never confirms - a refused command
     * or a value the car silently clamped. Past this the server's word wins.
     */
    private const val PENDING_TIMEOUT_MS = 8_000L

    /**
     * Values we have commanded but the car has not yet echoed back.
     *
     * A plain time window was not good enough: OverDrive's own snapshot lags the car
     * by a second or two, so a fixed grace either expired too early - flicking the
     * display back to the old value - or lingered long enough to hide a change made
     * somewhere else. Holding the value until the car *agrees* fixes both: our own
     * presses never flicker, and anything changed elsewhere still lands on the very
     * next poll, because nothing is pending.
     */
    @Volatile
    private var pendingClimate: Boolean? = null

    @Volatile
    private var pendingCompressor: Boolean? = null

    @Volatile
    private var pendingSetpoint: Int? = null

    @Volatile
    private var pendingFan: Int? = null

    @Volatile
    private var pendingUntil = 0L

    /**
     * Reads the whole cabin. Blocking - callers must be off the main thread.
     *
     * The feature bus answers everything, so a working bus means no HTTP at all and
     * OverDrive need not be installed. The REST snapshot is read only as a fallback
     * for a car whose bus we cannot reach.
     */
    fun refresh(): Boolean {
        val expired = SystemClock.elapsedRealtime() >= pendingUntil
        if (CarBus.isAvailable) {
            readFromBus(expired)
            available = true
            return true
        }

        val state = OverdriveApi.state()
        available = state != null
        state?.optJSONObject("climate")?.let { climate ->
            applyClimate(
                power = climate.optBoolean("acOn", false),
                setpoint = climate.optInt("setpointDriver", -1).takeIf { it >= 0 },
                fan = climate.optInt("fanLevel", -1).takeIf { it >= 0 },
                expired = expired,
            )
        }
        // Both arrays are [driver, passenger].
        state?.optJSONObject("seats")?.let { seats ->
            val heat = seats.optJSONArray("heat")
            val cool = seats.optJSONArray("cool")
            seatHeatLevel = heat?.optInt(0, 0) ?: 0
            seatHeatLevelPassenger = heat?.optInt(1, 0) ?: 0
            seatVentLevel = cool?.optInt(0, 0) ?: 0
            seatVentLevelPassenger = cool?.optInt(1, 0) ?: 0
        }
        return available
    }

    /** One line per connection, so an unexpected reading can be traced to its signal. */
    @Volatile
    private var described = false

    /**
     * Where the sweep in progress is writing, or null when none is.
     *
     * Runs on the poll's own thread, since the sweep is a few thousand round trips
     * and has no business on the main one.
     */
    @Volatile
    private var scanRequest: java.io.File? = null

    @Volatile
    private var scanQueue: MutableList<String>? = null

    /**
     * Asks the next polls to sweep every signal into [file], one domain per poll.
     *
     * On demand only - it used to run at every service start, which cost about fifty
     * seconds and nearly three thousand binder calls per boot for a catalogue that
     * changes only when the car does. The queue is set before the file so a poll
     * that lands in between sees no file and simply waits for the next one.
     */
    fun requestSweep(file: java.io.File) {
        runCatching { file.writeText("") }
        scanQueue = BydFeatures.groups().toMutableList()
        scanRequest = file
    }

    /**
     * Sweeps one group per poll rather than all of them at once.
     *
     * A single pass over every signal wedged on a binder call that never came back -
     * the car answers most ids and silently swallows a few. One group at a time keeps
     * a bad id from taking the whole sweep with it, appends as it goes so a hang still
     * leaves everything found so far, and names the group in the log before reading it
     * so the culprit is obvious.
     */
    private fun sweepOneGroup() {
        val queue = scanQueue ?: return
        val file = scanRequest ?: return
        if (queue.isEmpty()) {
            scanQueue = null
            scanRequest = null
            android.util.Log.i("CarBus", "sweep complete: $file")
            return
        }

        val group = queue.removeAt(0)
        android.util.Log.i("CarBus", "sweeping $group (${queue.size} left)")
        runCatching { file.appendText(CarBus.scanGroup(group)) }
    }

    private fun readFromBus(expired: Boolean) {
        if (!described) {
            described = true
            android.util.Log.i("CarBus", CarBus.describe())
            android.util.Log.i("CarBus", CarBus.describeInfo())
        }
        sweepOneGroup()
        airflow = Airflow.current()

        val busCompressor = Compressor.isOn()
        if (settle(pendingCompressor, busCompressor, expired)) {
            pendingCompressor = null
            if (busCompressor != null) compressorOn = busCompressor
        }

        applyClimate(
            power = CarBus.climateOn(),
            setpoint = CarBus.setpoint(),
            fan = CarBus.fanLevel(),
            expired = expired,
        )

        // Unlike the REST snapshot these are the car's own flags, not our record of
        // the last command, so a change made on the BYD panel shows up here too.
        CarBus.recirculating()?.let { recirculateOn = it }
        CarBus.defrostRear()?.let { defrostRearOn = it }
        CarBus.steeringHeat()?.let { steeringHeatOn = it }
        // Front defrost is reconciled inside applyClimate, which knows whether the
        // system just went down and took it with it.

        CarBus.seatLevel(driver = true, heating = true)?.let { seatHeatLevel = it }
        CarBus.seatLevel(driver = true, heating = false)?.let { seatVentLevel = it }
        CarBus.seatLevel(driver = false, heating = true)?.let { seatHeatLevelPassenger = it }
        CarBus.seatLevel(driver = false, heating = false)?.let { seatVentLevelPassenger = it }

        readHud()
        readRoof()
    }

    /**
     * The travelling panels - roof, windows, tailgate - read only while something
     * is showing one of them.
     *
     * Which panels exist is a single read each, wanted once - the settings screen
     * needs it before any such control exists to want the positions.
     */
    private fun readRoof() {
        if (sunroofFitted == null) sunroofFitted = CarBus.sunroofFitted()
        if (sunshadeFitted == null) sunshadeFitted = CarBus.sunshadeFitted()
        if (tailgateFitted == null) tailgateFitted = CarBus.tailgateFitted()
        if (!roofWanted) return

        CarBus.sunroofPercent()?.let { sunroof.observe(it) }
        CarBus.sunshadePercent()?.let { sunshade.observe(it) }

        var widest: Int? = null
        windows.forEach { (window, panel) ->
            CarBus.windowPercent(window)?.let { percent ->
                panel.observe(percent)
                widest = maxOf(widest ?: percent, percent)
            }
        }
        widest?.let { allWindows.observe(it) }

        CarBus.tailgateOpen()?.let { open ->
            tailgate.observe(if (open) CarBus.ROOF_OPEN_PERCENT else CarBus.ROOF_CLOSED_PERCENT)
        }
    }

    /** The HUD's switches and levels, read only while something is showing them. */
    private fun readHud() {
        // Fitted or not is a single read, wanted once: the settings screen needs it
        // before any HUD control exists to want the rest.
        if (hudFitted == null) hudFitted = CarBus.hudFitted()
        if (!hudWanted || SystemClock.elapsedRealtime() < hudHoldUntil) return

        CarBus.hudOn()?.let { hudOn = it }
        CarBus.hudNavigation()?.let { hudNavigationOn = it }
        CarBus.hudAdas()?.let { hudAdasOn = it }
        CarBus.hudTheme()?.let { hudTheme = it }
        CarBus.hudBrightness()?.let { hudBrightness = it }
        CarBus.hudHeight()?.let { hudHeight = it }
        hudSynced = true
    }

    /** Shared by both transports, so they cannot drift on the reconciliation rules. */
    private fun applyClimate(power: Boolean?, setpoint: Int?, fan: Int?, expired: Boolean) {
        if (power != null && settle(pendingClimate, power, expired)) {
            pendingClimate = null
            // Front defrost dies with the blower and the car does not restore it, so
            // drop our flag as the system goes down - whether that was us or the car's
            // own panel. Above that, take the car's word when it has one.
            if (climateOn && !power) defrostOn = false else CarBus.defrostFront()?.let { defrostOn = it }
            climateOn = power
        }

        if (settle(pendingSetpoint, setpoint, expired)) {
            pendingSetpoint = null
            setpointC = setpoint
        }

        if (settle(pendingFan, fan, expired)) {
            pendingFan = null
            fanLevel = fan
        }
    }

    /** Readout text for a stepper group; "--" until the first poll lands. */
    fun temperatureLabel(): String = setpointC?.let { "$it°" } ?: "--"

    fun fanLabel(): String = fanLevel?.toString() ?: "--"

    fun hudBrightnessLabel(): String = hudBrightness?.toString() ?: "--"

    fun hudHeightLabel(): String = hudHeight?.toString() ?: "--"

    /** One table for every stepper, so the bar and the grid cannot drift apart. */
    fun stepperLabel(action: NavAction): String = when (action) {
        NavAction.TEMP_GROUP -> temperatureLabel()
        NavAction.FAN_GROUP -> fanLabel()
        NavAction.HUD_BRIGHTNESS_GROUP -> hudBrightnessLabel()
        NavAction.HUD_HEIGHT_GROUP -> hudHeightLabel()
        else -> if (action.isPercent) roofLabel(action) else ""
    }

    /** The travelling panel behind a button - roof, window or tailgate - or null. */
    fun roofPanel(action: NavAction): MovingPanel? = when (action) {
        NavAction.SUNROOF -> sunroof
        NavAction.SUNSHADE -> sunshade
        NavAction.WINDOW_LEFT_FRONT -> windows[CarBus.CarWindow.LEFT_FRONT]
        NavAction.WINDOW_RIGHT_FRONT -> windows[CarBus.CarWindow.RIGHT_FRONT]
        NavAction.WINDOW_LEFT_REAR -> windows[CarBus.CarWindow.LEFT_REAR]
        NavAction.WINDOW_RIGHT_REAR -> windows[CarBus.CarWindow.RIGHT_REAR]
        NavAction.WINDOWS_ALL -> allWindows
        NavAction.TAILGATE -> tailgate
        else -> null
    }

    /** How far one panel is open, or null before the first poll has landed. */
    fun roofPercent(action: NavAction): Int? = roofPanel(action)?.percent

    /** Whether one panel is travelling, which is what makes a press mean "stop". */
    fun roofMoving(action: NavAction): Boolean =
        roofPanel(action)?.motion?.let { it != PanelMotion.IDLE } ?: false

    /**
     * Whether the car has the panel this button drives; null while unknown. The
     * windows are taken as always there - every car on this platform has four.
     */
    fun roofFitted(action: NavAction): Boolean? = when (action) {
        NavAction.SUNROOF -> sunroofFitted
        NavAction.SUNSHADE -> sunshadeFitted
        NavAction.TAILGATE -> tailgateFitted
        else -> null
    }

    /**
     * The value under the icon. A percentage for anything that reports one; the
     * tailgate only knows shut or open, and says so in a word rather than a fake
     * 0% or 100%.
     */
    fun roofLabel(action: NavAction): String {
        val percent = roofPercent(action) ?: return "--"
        return when {
            action != NavAction.TAILGATE -> "$percent%"
            percent > CarBus.ROOF_CLOSED_PERCENT -> "OPEN"
            else -> "SHUT"
        }
    }

    // The HUD previews mirror the climate ones below: move the cached value on the
    // press, hold it while the car catches up, and let a refusal re-sync it.

    fun previewHudBrightness(delta: Int): Int? {
        val next = (hudBrightness ?: return null).plus(delta).coerceIn(CarBus.HUD_BRIGHTNESS_RANGE)
        hudBrightness = next
        holdHud()
        return next
    }

    fun previewHudHeight(delta: Int): Int? {
        val next = (hudHeight ?: return null).plus(delta).coerceIn(CarBus.HUD_HEIGHT_RANGE)
        hudHeight = next
        holdHud()
        return next
    }

    fun previewHudOn(on: Boolean) {
        hudOn = on
        holdHud()
    }

    fun previewHudNavigation(on: Boolean) {
        hudNavigationOn = on
        holdHud()
    }

    fun previewHudAdas(on: Boolean) {
        hudAdasOn = on
        holdHud()
    }

    fun previewHudTheme(theme: Int) {
        hudTheme = theme
        holdHud()
    }

    private fun holdHud() {
        hudHoldUntil = SystemClock.elapsedRealtime() + HUD_HOLD_MS
    }

    /** Drops the hold so the next poll re-syncs the HUD from the car. */
    fun releaseHud() {
        hudHoldUntil = 0L
    }

    /**
     * Applies a step to the cached value and returns the new target, without
     * touching the network.
     *
     * The press handler uses this to move the readout immediately: a round trip to
     * the car is tens of milliseconds at best, and the car often still reports the
     * old setpoint straight after a write, so waiting for confirmation made the
     * display lag every press. The command still reconciles against the car
     * afterwards, so a refused or clamped value corrects itself.
     */
    /**
     * True when the server's value should be taken. Keeps the optimistic value only
     * while a write is still outstanding and the car has not caught up yet.
     */
    private fun <T> settle(pending: T?, server: T?, expired: Boolean): Boolean =
        pending == null || pending == server || expired

    fun previewTemperature(delta: Int, range: IntRange): Int? {
        val next = (setpointC ?: return null).plus(delta).coerceIn(range)
        setpointC = next
        pendingSetpoint = next
        holdPending()
        return next
    }

    fun previewFan(delta: Int, range: IntRange): Int? {
        val next = (fanLevel ?: return null).plus(delta).coerceIn(range)
        fanLevel = next
        pendingFan = next
        holdPending()
        return next
    }

    /**
     * Flips the cached compressor state and returns the new value, without waiting
     * for the car. The blower is untouched either way, so the readouts stay as they
     * are - only the A/C button changes colour.
     */
    fun previewCompressor(on: Boolean): Boolean {
        compressorOn = on
        pendingCompressor = on
        holdPending()
        return on
    }

    /**
     * Marks the climate system as coming up, for the one case that starts it: asking
     * for A/C on a system that was off. The fan level is left alone because the car
     * picks it, and we cannot guess what it will choose.
     */
    fun previewClimateStart() {
        climateOn = true
        pendingClimate = true
        holdPending()
    }

    /**
     * Marks the whole system as going down, which takes the blower and the front
     * defrost with it.
     *
     * The compressor flag is left to the car: it may well go on reporting the last
     * setting with the system off, and pending a guess against it would only flicker
     * once the hold expired. Nothing shows it while the system is down anyway.
     */
    fun previewClimateStop() {
        climateOn = false
        pendingClimate = false
        fanLevel = 0
        pendingFan = 0
        defrostOn = false
        holdPending()
    }

    private fun holdPending() {
        pendingUntil = SystemClock.elapsedRealtime() + PENDING_TIMEOUT_MS
    }

    /** Abandons every pending write so the next poll re-syncs from the car. */
    fun releaseOptimistic() {
        pendingClimate = null
        pendingCompressor = null
        pendingSetpoint = null
        pendingFan = null
        pendingUntil = 0L
        hudHoldUntil = 0L
    }
}
