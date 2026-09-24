package com.example.esmerui.car

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Car actions for the navigation bar, on top of [OverdriveApi].
 *
 * Relative commands (Fan +, Temp -) are resolved by reading the live setpoint and
 * sending an absolute value. The API does offer a step_temp action, but its "area"
 * argument is undocumented and guessing it would risk commanding the wrong zone -
 * read-then-set costs one extra request and is unambiguous.
 */
object CarControl {

    /** AC_TEMP_IN_CELSIUS_MIN..MAX. */
    private val TEMP_RANGE = 17..33

    /** The API rejects fan levels outside 1..7; 0 is "off", reached by powering down. */
    private val FAN_RANGE = 1..7

    /**
     * Off, low, high - three presses and you are back to off.
     *
     * The car accepts a level 3, but cycling through it meant four presses to turn
     * the seat off again. Two levels also matches the icon set, which ships artwork
     * for level 1 and level 2 only.
     */
    private val SEAT_LEVELS = 0..2

    /** Driver zone: 0 addresses both zones, 1 driver, 2 passenger. */
    private const val ZONE_BOTH = 0
    private const val SEAT_DRIVER = 1
    private const val SEAT_PASSENGER = 2

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Runs [work] off the main thread and reports back on it. Bar buttons are
     * pressed on the UI thread and every call here is a network round trip, so
     * doing this inline would jank the bar - or trip StrictMode.
     */
    fun run(onResult: (String) -> Unit, work: () -> String) {
        io.execute {
            val message = runCatching(work).getOrElse { "Car control failed" }
            main.post { onResult(message) }
        }
    }

    fun isAvailable(): Boolean = CarBus.isAvailable || OverdriveApi.isReachable()

    /**
     * Sends a command on the feature bus, falling back to OverDrive's REST API.
     *
     * The bus is the app's own path to the car and needs nothing else installed. The
     * fallback covers a signal a given car will not take that way, and costs one
     * refused loopback connection when OverDrive is not running - immediate, and only
     * on a command that had already failed.
     */
    private inline fun send(bus: () -> Boolean, rest: () -> Boolean): Boolean = bus() || rest()

    // ---- Climate -----------------------------------------------------------

    /**
     * Sends an absolute setpoint derived from [target], which the caller has already
     * computed from the cached value.
     *
     * Deliberately one request: reading the car first doubled the latency of every
     * press, and the cache is refreshed by the poller anyway. On failure the cache
     * is re-synced so an optimistic readout cannot stay wrong.
     */
    fun setTemperature(target: Int): String {
        val value = target.coerceIn(temperatureRange)
        val ok = send(
            bus = { CarBus.setSetpoint(value) },
            rest = { OverdriveApi.climate("set_temp", mapOf("zone" to ZONE_BOTH, "temp" to value)) },
        )
        if (!ok) resync()
        return if (ok) "$value°" else REFUSED
    }

    fun setFan(target: Int): String {
        val value = target.coerceIn(FAN_RANGE)
        val ok = send(
            bus = { CarBus.setFan(value) },
            rest = { OverdriveApi.climate("set_fan", mapOf("fan" to value)) },
        )
        if (!ok) resync()
        return if (ok) "Fan $value" else REFUSED
    }

    /** A refused command means the optimistic value is a lie - drop it at once. */
    private fun resync() {
        CarStatus.releaseOptimistic()
        CarStatus.refresh()
    }

    /** True when [message] reports a failure rather than a new value. */
    fun isFailure(message: String): Boolean =
        message == REFUSED || message == UNAVAILABLE || message == HUD_UNAVAILABLE ||
            message == ROOF_UNAVAILABLE

    /** Falls back to reading the car only when the cache has never been filled. */
    fun nudgeTemperature(delta: Int): String {
        val base = CarStatus.setpointC ?: readSetpoint() ?: return UNAVAILABLE
        return setTemperature(base + delta)
    }

    fun nudgeFan(delta: Int): String {
        val base = CarStatus.fanLevel ?: readFan() ?: return UNAVAILABLE
        return setFan(base + delta)
    }

    private fun readSetpoint(): Int? = CarBus.setpoint()
        ?: OverdriveApi.state()?.optJSONObject("climate")?.optInt("setpointDriver", -1)
            ?.takeIf { it >= 0 }

    private fun readFan(): Int? = CarBus.fanLevel()
        ?: OverdriveApi.state()?.optJSONObject("climate")?.optInt("fanLevel", -1)
            ?.takeIf { it >= 0 }

    /** The car's own band, which is in Fahrenheit on a car set to display that. */
    val temperatureRange: IntRange
        get() = if (CarBus.isAvailable) CarBus.temperatureRange else TEMP_RANGE

    val fanRange: IntRange get() = FAN_RANGE

    /** Front windscreen defrost. */
    fun toggleDefrost(): String {
        val next = !CarStatus.defrostOn
        val ok = send(
            bus = { CarBus.setDefrostFront(next) },
            rest = { OverdriveApi.climate(if (next) "defrost_front_on" else "defrost_front_off") },
        )
        return if (ok) {
            CarStatus.defrostOn = next
            if (next) "Defrost on" else "Defrost off"
        } else {
            REFUSED
        }
    }

    /**
     * Switches one vent on or off, leaving the others as they are.
     *
     * Unlike the climate commands this goes straight to the car's feature bus, not
     * through OverDrive - its REST API has no airflow action at all.
     */
    fun toggleVent(vent: Vent): String {
        val current = CarStatus.airflow ?: Airflow.current() ?: return UNAVAILABLE

        val vents = if (vent in current.vents) current.vents - vent else current.vents + vent
        // The car has no "all vents closed" mode, so the last one stays on.
        if (vents.isEmpty()) return "${current.label} (last vent)"

        val target = Airflow.of(vents) ?: return REFUSED
        val ok = Airflow.set(target)
        CarStatus.airflow = if (ok) target else Airflow.current()
        return if (ok) target.label else REFUSED
    }

    fun toggleRearDefrost(): String {
        val next = !CarStatus.defrostRearOn
        val ok = send(
            bus = { CarBus.setDefrostRear(next) },
            rest = { OverdriveApi.climate(if (next) "defrost_rear_on" else "defrost_rear_off") },
        )
        return if (ok) {
            CarStatus.defrostRearOn = next
            if (next) "Rear defrost on" else "Rear defrost off"
        } else {
            REFUSED
        }
    }

    /**
     * Switches the compressor, which the caller has already applied optimistically.
     *
     * Cooling only: the blower keeps running at whatever level it was on, the way a
     * dash A/C button behaves. Climate power is a separate signal and is touched in
     * exactly one case - [startSystem], set by the caller when it saw the system down
     * before the press, where a compressor with no blower would do nothing you could
     * feel. The caller has to say so because it has already applied the change
     * optimistically, leaving the cache no longer able to answer the question.
     */
    fun setAc(on: Boolean, startSystem: Boolean): String {
        if (!Compressor.isSupported) return setClimatePower(on)

        if (on && startSystem && !setClimateRunning(true)) {
            resync()
            return REFUSED
        }

        val ok = Compressor.set(on)
        if (!ok) resync()
        return if (ok) {
            if (on) "A/C on" else "A/C off"
        } else {
            REFUSED
        }
    }

    /**
     * Whole-system power, for a car that exposes no separate compressor signal.
     *
     * One request: reading the car first only added latency, and the read straight
     * after a write returns the *previous* power state anyway.
     */
    private fun setClimatePower(on: Boolean): String {
        val ok = setClimateRunning(on)
        if (!ok) resync()
        return if (ok) {
            if (on) "A/C on" else "A/C off"
        } else {
            REFUSED
        }
    }

    private fun setClimateRunning(on: Boolean): Boolean = send(
        bus = { CarBus.setClimate(on) },
        rest = { OverdriveApi.climate(if (on) "power_on" else "power_off") },
    )

    /** Fallback for callers with no cached state to flip. */
    fun toggleAc(): String = setAc(!CarStatus.compressorOn, startSystem = !CarStatus.climateOn)

    /**
     * Shuts the whole climate system down, blower included.
     *
     * The short press deliberately no longer does this - it switches the compressor
     * and leaves the fan running - so this is the only way left to stop the system
     * from the bar, and it is reached by a long press.
     */
    fun stopClimate(): String {
        val ok = setClimateRunning(false)
        if (!ok) resync()
        return if (ok) "Climate off" else REFUSED
    }

    fun toggleRecirculation(): String {
        val next = !CarStatus.recirculateOn
        val ok = send(
            bus = { CarBus.setRecirculate(next) },
            rest = { OverdriveApi.climate(if (next) "recirculate_on" else "recirculate_off") },
        )
        return if (ok) {
            CarStatus.recirculateOn = next
            if (next) "Recirculating" else "Fresh air"
        } else {
            REFUSED
        }
    }

    fun toggleSteeringHeat(): String {
        val next = !CarStatus.steeringHeatOn
        val ok = send(
            bus = { CarBus.setSteeringHeat(next) },
            rest = { OverdriveApi.climate(if (next) "steering_heat_on" else "steering_heat_off") },
        )
        return if (ok) {
            CarStatus.steeringHeatOn = next
            if (next) "Wheel heat on" else "Wheel heat off"
        } else {
            REFUSED
        }
    }

    // ---- Seats -------------------------------------------------------------

    fun cycleSeatHeat(): String =
        cycleSeat(driver = true, heating = true, label = "Seat heat")

    fun cycleSeatVent(): String =
        cycleSeat(driver = true, heating = false, label = "Seat cool")

    fun cycleSeatHeatPassenger(): String =
        cycleSeat(driver = false, heating = true, label = "Passenger heat")

    fun cycleSeatVentPassenger(): String =
        cycleSeat(driver = false, heating = false, label = "Passenger cool")

    /**
     * Steps one seat through off -> 1 -> 2 -> off.
     *
     * The level is read back before stepping so a change made on the car's own panel
     * is not overwritten with a stale one.
     */
    private fun cycleSeat(driver: Boolean, heating: Boolean, label: String): String {
        val current = seatLevel(driver, heating) ?: return UNAVAILABLE
        val next = if (current >= SEAT_LEVELS.last) 0 else current + 1

        val position = if (driver) SEAT_DRIVER else SEAT_PASSENGER
        val action = if (heating) "heating" else "ventilation"
        val ok = send(
            bus = { CarBus.setSeatLevel(driver, heating, next) },
            rest = { OverdriveApi.seat(action, position, next) },
        )
        return if (ok) {
            if (next == 0) "$label off" else "$label $next"
        } else {
            REFUSED
        }
    }

    private fun seatLevel(driver: Boolean, heating: Boolean): Int? {
        CarBus.seatLevel(driver, heating)?.let { return it }
        val seats = OverdriveApi.state()?.optJSONObject("seats") ?: return null
        val index = if (driver) 0 else 1
        return seats.optJSONArray(if (heating) "heat" else "cool")?.optInt(index, 0) ?: 0
    }

    // ---- Head-up display ---------------------------------------------------

    /**
     * The HUD's switches go straight to the feature bus: OverDrive's REST API has
     * no HUD actions, and the Setting group is the one this car has never refused.
     * Each preview moves the button before the write goes out, and a refusal drops
     * the preview and re-reads, the same contract as the climate commands.
     */
    /**
     * The cached value once the poll has filled it, else the car's own answer.
     *
     * A HUD button pressed before any HUD control has been polled - from the home
     * grid on a bar without one, say - would otherwise flip from a default and
     * write the state the car is already in.
     */
    private inline fun <T> hudCurrent(cached: T, read: () -> T?): T =
        if (CarStatus.hudSynced) cached else read() ?: cached

    fun toggleHud(): String {
        val next = !hudCurrent(CarStatus.hudOn) { CarBus.hudOn() }
        CarStatus.previewHudOn(next)
        return hudResult(CarBus.setHud(next), if (next) "HUD on" else "HUD off")
    }

    fun toggleHudNavigation(): String {
        val next = !hudCurrent(CarStatus.hudNavigationOn) { CarBus.hudNavigation() }
        CarStatus.previewHudNavigation(next)
        return hudResult(
            CarBus.setHudNavigation(next),
            if (next) "HUD navigation on" else "HUD navigation off",
        )
    }

    fun toggleHudAdas(): String {
        val next = !hudCurrent(CarStatus.hudAdasOn) { CarBus.hudAdas() }
        CarStatus.previewHudAdas(next)
        return hudResult(CarBus.setHudAdas(next), if (next) "HUD ADAS on" else "HUD ADAS off")
    }

    /** Two themes, so a press is simply the other one. */
    fun toggleHudTheme(): String {
        val current = hudCurrent(CarStatus.hudTheme) { CarBus.hudTheme() }
        val next = if (current == CarBus.HUD_THEME_SNOW) {
            CarBus.HUD_THEME_CLASSIC
        } else {
            CarBus.HUD_THEME_SNOW
        }
        CarStatus.previewHudTheme(next)
        return hudResult(
            CarBus.setHudTheme(next),
            if (next == CarBus.HUD_THEME_SNOW) "HUD theme: Snow" else "HUD theme: Classic",
        )
    }

    fun setHudBrightness(target: Int): String {
        val value = target.coerceIn(CarBus.HUD_BRIGHTNESS_RANGE)
        return hudResult(CarBus.setHudBrightness(value), "HUD brightness $value")
    }

    fun setHudHeight(target: Int): String {
        val value = target.coerceIn(CarBus.HUD_HEIGHT_RANGE)
        return hudResult(CarBus.setHudHeight(value), "HUD height $value")
    }

    /** Falls back to reading the car only when the cache has never been filled. */
    fun nudgeHudBrightness(delta: Int): String {
        val base = CarStatus.hudBrightness ?: CarBus.hudBrightness() ?: return HUD_UNAVAILABLE
        return setHudBrightness(base + delta)
    }

    fun nudgeHudHeight(delta: Int): String {
        val base = CarStatus.hudHeight ?: CarBus.hudHeight() ?: return HUD_UNAVAILABLE
        return setHudHeight(base + delta)
    }

    // ---- Roof --------------------------------------------------------------

    /**
     * One press means "go the other way": anything open closes, a shut panel opens
     * fully.
     *
     * Mid-travel counts as open, so a second press reverses a panel still on its
     * way rather than doing nothing until it arrives - which is also the only stop
     * the bar offers, and the one a hand reaches for when the roof is going
     * somewhere it should not.
     *
     * Nothing is previewed. The car reports the travel a poll at a time and that
     * is the useful thing to show; claiming the commanded value straight away
     * would read 100% with the glass barely moving.
     */
    fun toggleSunroof(): String = toggleRoof(
        label = "Sunroof",
        current = CarStatus.sunroofPercent ?: CarBus.sunroofPercent(),
    ) { CarBus.setSunroofPercent(it) }

    fun toggleSunshade(): String = toggleRoof(
        label = "Sunshade",
        current = CarStatus.sunshadePercent ?: CarBus.sunshadePercent(),
    ) { CarBus.setSunshadePercent(it) }

    private inline fun toggleRoof(
        label: String,
        current: Int?,
        write: (Int) -> Boolean,
    ): String {
        val opening = (current ?: return ROOF_UNAVAILABLE) <= CarBus.ROOF_CLOSED_PERCENT
        val target = if (opening) CarBus.ROOF_OPEN_PERCENT else CarBus.ROOF_CLOSED_PERCENT
        if (!write(target)) return REFUSED
        return if (opening) "$label opening" else "$label closing"
    }

    /** A refused HUD write means the preview is a lie - drop it and re-read at once. */
    private fun hudResult(ok: Boolean, message: String): String {
        if (!ok) {
            CarStatus.releaseHud()
            CarStatus.refresh()
        }
        return if (ok) message else REFUSED
    }

    private const val UNAVAILABLE = "OverDrive not reachable"
    private const val HUD_UNAVAILABLE = "HUD not answering"
    private const val ROOF_UNAVAILABLE = "Roof not answering"
    private const val REFUSED = "Car refused the command"
}
