package com.example.esmerui.car

/**
 * Every car signal the bar touches, named in one place.
 *
 * This is the app's own path to the car, over [BydFeatures] - no OverDrive, no
 * HTTP, no second app that has to be running. OverDrive is kept only as a fallback
 * in [CarControl] for anything a given car refuses on the bus.
 *
 * Two conventions run through all of it: a signal is read from `X` and written to
 * `X_SET`, and the value constants come from the ROM rather than being hardcoded,
 * so a car that numbers them differently still works. The literals here are only
 * the defaults for when a ROM hides the constant.
 */
object CarBus {

    private const val AC = "Ac"
    private const val SETTING = "Setting"
    private const val AC_DEVICE = "ac.BYDAutoAcDevice"

    /** BYD's "no reading" sentinel, returned by signals this car does not populate. */
    private const val UNAVAILABLE = 65535

    val isAvailable: Boolean get() = BydFeatures.isAvailable

    private fun constant(field: String, fallback: Int): Int =
        BydFeatures.deviceConstant(AC_DEVICE, field) ?: fallback

    // ---- Climate power -----------------------------------------------------

    private val powerOn by lazy { constant("AC_POWER_ON", 1) }
    private val powerOff by lazy { constant("AC_POWER_OFF", 0) }

    fun climateOn(): Boolean? = BydFeatures.readInt(AC, "AC_POWER_STATE")?.let { it == powerOn }

    fun setClimate(on: Boolean): Boolean =
        BydFeatures.writeInt(AC, "AC_POWER_STATE_SET", if (on) powerOn else powerOff)

    // ---- Fan ---------------------------------------------------------------

    fun fanLevel(): Int? = BydFeatures.readInt(AC, "AC_WIND_LEVEL")

    fun setFan(level: Int): Boolean = BydFeatures.writeInt(AC, "AC_WIND_LEVEL_SET", level)

    // ---- Temperature -------------------------------------------------------

    /**
     * Setpoints are carried in whatever unit the car is displaying, so the range has
     * to follow suit - handing a Celsius number to a car showing Fahrenheit would be
     * clamped to its minimum and read as the dial jumping to cold.
     */
    private val unitCelsius by lazy { constant("AC_TEMPERATURE_UNIT_OC", 1) }
    private val setpointInvalid by lazy { constant("AC_TEMP_INVALID", 0) }

    val celsius: Boolean
        get() = BydFeatures.readInt(AC, "AC_TEMPERATURE_UNIT")
            ?.let { it == unitCelsius } ?: true

    val temperatureRange: IntRange
        get() = if (celsius) {
            constant("AC_TEMP_IN_CELSIUS_MIN", 17)..constant("AC_TEMP_IN_CELSIUS_MAX", 33)
        } else {
            constant("AC_TEMP_IN_FAHRENHEIT_MIN", 64)..constant("AC_TEMP_IN_FAHRENHEIT_MAX", 91)
        }

    // The invalid marker is a ROM constant looked up by reflection; resolved once,
    // not on every one of the poll's reads.
    fun setpoint(): Int? =
        BydFeatures.readInt(AC, "AC_TEMP_MAIN")?.takeIf { it != setpointInvalid }

    /** Writes both zones, matching the single dial the bar presents. */
    fun setSetpoint(value: Int): Boolean {
        val main = BydFeatures.writeInt(AC, "AC_TEMP_MAIN_SET", value)
        // The passenger zone is a separate signal and not every car has one, so its
        // refusal must not fail the driver's write.
        BydFeatures.writeInt(AC, "AC_TEMP_DEPUTY_SET", value)
        return main
    }

    // ---- Recirculation -----------------------------------------------------

    private val cycleIn by lazy { constant("AC_CYCLEMODE_INLOOP", 1) }
    private val cycleOut by lazy { constant("AC_CYCLEMODE_OUTLOOP", 0) }

    fun recirculating(): Boolean? = BydFeatures.readInt(AC, "AC_CYCLE_MODE")?.let { it == cycleIn }

    fun setRecirculate(on: Boolean): Boolean =
        BydFeatures.writeInt(AC, "AC_CYCLE_MODE_SET", if (on) cycleIn else cycleOut)

    // ---- Defrost -----------------------------------------------------------

    private val defrostOn by lazy { constant("AC_DEFROST_STATE_ON", 1) }
    private val defrostOff by lazy { constant("AC_DEFROST_STATE_OFF", 0) }

    fun defrostFront(): Boolean? =
        BydFeatures.readInt(AC, "AC_DEFROST_FRONT_STATE")?.let { it == defrostOn }

    fun setDefrostFront(on: Boolean): Boolean =
        BydFeatures.writeInt(AC, "AC_DEFROST_FRONT_STATE_SET", if (on) defrostOn else defrostOff)

    fun defrostRear(): Boolean? =
        BydFeatures.readInt(AC, "AC_DEFROST_REAR_STATE")?.let { it == defrostOn }

    fun setDefrostRear(on: Boolean): Boolean =
        BydFeatures.writeInt(AC, "AC_DEFROST_REAR_STATE_SET", if (on) defrostOn else defrostOff)

    // ---- Seats -------------------------------------------------------------

    /**
     * Seat climate counts from one: the car reports 1 for off, 2 and 3 for the two
     * levels. Measured against the car rather than assumed - reading it as a plain
     * level lit all four seat buttons green on a cabin with every seat off.
     */
    private const val SEAT_OFF = 1

    // Seat climate lives in the Setting group rather than Ac, and is per-seat rather
    // than the [driver, passenger] array the REST snapshot reported.
    private fun seatField(driver: Boolean, heating: Boolean): String {
        val seat = if (driver) "DRIVER" else "PASSENGER"
        val what = if (heating) "HEATING" else "VENTILATING"
        return "SET_${seat}_SEAT_${what}_STATE"
    }

    fun seatLevel(driver: Boolean, heating: Boolean): Int? =
        BydFeatures.readInt(SETTING, seatField(driver, heating))
            ?.takeIf { it >= SEAT_OFF }
            ?.minus(SEAT_OFF)

    fun setSeatLevel(driver: Boolean, heating: Boolean, level: Int): Boolean =
        BydFeatures.writeInt(SETTING, "${seatField(driver, heating)}_SET", level + SEAT_OFF)

    // ---- Steering wheel ----------------------------------------------------

    /**
     * Read from the heating gear, not `SET_STEERING_WHEEL_HEAT_STATE`: that one reads
     * 65535 on this car - BYD's "no reading" sentinel - which taken at face value
     * would light the button on a cold wheel.
     */
    fun steeringHeat(): Boolean? =
        BydFeatures.readInt(SETTING, "SET_AUTO_STEER_WHEEL_HEATING_GEAR")
            ?.takeIf { it != UNAVAILABLE }
            ?.let { it > 0 }

    /** Level 1 is the only "on" the bar offers. */
    fun setSteeringHeat(on: Boolean): Boolean =
        BydFeatures.writeInt(SETTING, "SET_AUTO_STEER_WHEEL_HEATING_GEAR_SET", if (on) 1 else 0)

    // ---- Head-up display ---------------------------------------------------

    /**
     * The HUD is drawn by the cluster's own controller from CAN data; the head unit
     * only holds its switches, which live in the Setting group and share one
     * encoding - 1 on, 2 off - exactly as the car's Settings app writes them. Each
     * is read from a `_FEEDBACK` signal and written to its `_SET` twin, and the
     * names were taken from that app rather than guessed: "navigation on HUD" is
     * the dynamic-navi function, "ADAS on HUD" the safe-driving-assist status, and
     * `SET_HUD_MODE` is the theme picker, not a content selector.
     */
    private const val HUD_ON = 1
    private const val HUD_OFF = 2

    const val HUD_THEME_CLASSIC = 1
    const val HUD_THEME_SNOW = 2

    /** The car's own limits, from its Settings app. */
    val HUD_BRIGHTNESS_RANGE = 1..11
    val HUD_HEIGHT_RANGE = 1..21

    /** Whether the car has a HUD at all: 1 is a windscreen unit, 2 an AR one. */
    fun hudFitted(): Boolean? = reading(SETTING, "SET_HUD_CONFIG")?.let { it > 0 }

    private fun hudSwitch(feedback: String): Boolean? =
        reading(SETTING, feedback)?.let { it == HUD_ON }

    private fun setHudSwitch(target: String, on: Boolean): Boolean =
        BydFeatures.writeInt(SETTING, target, if (on) HUD_ON else HUD_OFF)

    fun hudOn(): Boolean? = hudSwitch("SET_HUD_SWITCH_STATUS_FEEDBACK")

    fun setHud(on: Boolean): Boolean = setHudSwitch("SET_HUD_SWITCH_SET", on)

    fun hudNavigation(): Boolean? = hudSwitch("SET_DYNAMIC_NAVI_FUNCTION_STATUS_FEEDBACK")

    fun setHudNavigation(on: Boolean): Boolean =
        setHudSwitch("SET_DYNAMIC_NAVI_FUNCTION_STATUS_SET", on)

    fun hudAdas(): Boolean? = hudSwitch("SET_SAFE_DRIVING_ASSIST_STATUS_FEEDBACK")

    fun setHudAdas(on: Boolean): Boolean = setHudSwitch("SET_SAFE_DRIVING_ASSIST_STATUS_SET", on)

    fun hudTheme(): Int? = reading(SETTING, "SET_HUD_MODE_FEEDBACK")
        ?.takeIf { it == HUD_THEME_CLASSIC || it == HUD_THEME_SNOW }

    fun setHudTheme(theme: Int): Boolean = BydFeatures.writeInt(SETTING, "SET_HUD_MODE_SET", theme)

    fun hudBrightness(): Int? =
        reading(SETTING, "SET_BRIGHTNESS_GEAR_FEEDBACK")?.takeIf { it in HUD_BRIGHTNESS_RANGE }

    fun setHudBrightness(level: Int): Boolean =
        BydFeatures.writeInt(SETTING, "SET_BRIGHTNESS_GEAR_SET", level)

    fun hudHeight(): Int? =
        reading(SETTING, "SET_HEIGHT_GEAR_FEEDBACK")?.takeIf { it in HUD_HEIGHT_RANGE }

    fun setHudHeight(level: Int): Boolean = BydFeatures.writeInt(SETTING, "SET_HEIGHT_GEAR_SET", level)

    // ---- Roof --------------------------------------------------------------

    /**
     * The glass panel and the blind under it.
     *
     * Neither takes an open/close switch: both are driven by writing the
     * percentage they should travel to, which is what the ROM's own
     * `BYDAutoBodyworkDevice.setMoonRoofState` and `setSunshadeState` do - each
     * validates a percentage and then writes it straight to the `_PERCENT_SET`
     * id. Zero closes. The wrapper enforces `BYDAUTO_BODYWORK_SET`, which is
     * signature-level and not granted here, so these go over the generic manager
     * like every other write the bar makes.
     */
    private const val BODYWORK = "Bodywork"
    private const val BODYWORK_DEVICE = "bodywork.BYDAutoBodyworkDevice"

    // ---- Power ------------------------------------------------------------

    /** The level that means the car is off; the others are ACC, on and ready. */
    val carOff: Int by lazy {
        BydFeatures.deviceConstant(BODYWORK_DEVICE, "BODYWORK_POWER_LEVEL_OFF") ?: 0
    }

    /**
     * The car's power level, 0 off / 1 ACC / 2 on / 3 ready on this ROM. Polled
     * fast by the bar service: the head unit force-stops every third-party app
     * about a second after this reads off, and the bar has to act before that.
     */
    fun powerLevel(): Int? =
        BydFeatures.readInt(BODYWORK, "BODYWORK_POWER_LEVEL")?.takeIf { it != UNAVAILABLE }

    /**
     * What the roof is built from, as `getMoonRoofConfig` reports it. A car can
     * have the blind without the glass, so the two buttons are offered separately.
     */
    private const val ROOF_CONFIG_ROOF_AND_SHADE = 1
    private const val ROOF_CONFIG_SHADE_ONLY = 2
    private const val ROOF_CONFIG_ROOF_ANTI_PINCH = 3

    const val ROOF_OPEN_PERCENT = 100
    const val ROOF_CLOSED_PERCENT = 0

    /**
     * The glass refuses a percentage between 1 and 20 - `setMoonRoofState` rejects
     * that whole band rather than clamping it - so the smallest opening it will
     * accept is 21. The blind has no such floor and takes all of 0..100.
     */
    private const val SUNROOF_MIN_OPEN = 21

    /** "Stop where you are", accepted by both panels. */
    const val ROOF_STOP = 254

    private fun roofConfig(): Int? = reading(BODYWORK, "BODYWORK_MOON_ROOF_CONFIG")

    fun sunroofFitted(): Boolean? = roofConfig()?.let {
        it == ROOF_CONFIG_ROOF_AND_SHADE || it == ROOF_CONFIG_ROOF_ANTI_PINCH
    }

    fun sunshadeFitted(): Boolean? = roofConfig()?.let {
        it == ROOF_CONFIG_ROOF_AND_SHADE || it == ROOF_CONFIG_SHADE_ONLY
    }

    /** A panel mid-travel reports where it has got to; anything outside is noise. */
    private fun roofPercent(field: String): Int? =
        reading(BODYWORK, field)?.takeIf { it in ROOF_CLOSED_PERCENT..ROOF_OPEN_PERCENT }

    fun sunroofPercent(): Int? = roofPercent("BODYWORK_MOON_ROOF_OPEN_PERCENT")

    fun sunshadePercent(): Int? = roofPercent("BODYWORK_SUNSHADE_PANEL_PERCENT")

    fun setSunroofPercent(percent: Int): Boolean = BydFeatures.writeInt(
        BODYWORK,
        "BODYWORK_MOON_ROOF_OPEN_PERCENT_SET",
        when {
            percent == ROOF_STOP -> percent
            percent <= ROOF_CLOSED_PERCENT -> ROOF_CLOSED_PERCENT
            else -> percent.coerceIn(SUNROOF_MIN_OPEN, ROOF_OPEN_PERCENT)
        },
    )

    fun setSunshadePercent(percent: Int): Boolean = BydFeatures.writeInt(
        BODYWORK,
        "BODYWORK_SUNSHADE_PANEL_PERCENT_SET",
        if (percent == ROOF_STOP) percent else percent.coerceIn(ROOF_CLOSED_PERCENT, ROOF_OPEN_PERCENT),
    )

    // ---- Windows and tailgate -----------------------------------------------

    /**
     * The four side windows, by the register their position is read from and the
     * one they are driven through.
     *
     * Unlike the roof, a window is not sent a percentage: the ROM's own
     * `setBodyWindowCtrlState` writes one of a few commands to the CTRL register
     * and the door module does the travel. Position comes back as how far *open*
     * the glass is, 0 shut to 100 fully down - measured on the car 26 Sep 2026,
     * when "open half" took the rear-right window from 0 to 49 and "close" took
     * it back to 0. Left and right are the car's sides.
     */
    enum class CarWindow(val percentField: String, val controlField: String) {
        LEFT_FRONT("BODYWORK_WINDOW_LEFT_FRONT_PERCENT", "BODYWORK_LF_WINDOW_CTRL_SET"),
        RIGHT_FRONT("BODYWORK_WINDOW_RIGHT_FRONT_PERCENT", "BODYWORK_RF_WINDOW_CTRL_SET"),
        LEFT_REAR("BODYWORK_WINDOW_LEFT_REAR_PERCENT", "BODYWORK_LR_WINDOW_CTRL_SET"),
        RIGHT_REAR("BODYWORK_WINDOW_RIGHT_REAR_PERCENT", "BODYWORK_RR_WINDOW_CTRL_SET"),
    }

    /**
     * The CTRL register's vocabulary, from the ROM's BYDAutoBodyworkDevice: open
     * fully 1, close 2, stop 3 (4 is "half", 5 "breathe" - a crack for venting).
     */
    const val WINDOW_OPEN = 1
    const val WINDOW_CLOSE = 2
    const val WINDOW_STOP = 3

    /**
     * A window at the bottom of its travel reports 98, sometimes 100, never
     * reliably the latter - so the last few percent are read as fully open, and
     * the button says 100% rather than a 98% that looks like it stopped short.
     */
    private const val WINDOW_FULL_OPEN_FROM = 95

    fun windowPercent(window: CarWindow): Int? =
        reading(BODYWORK, window.percentField)
            ?.takeIf { it in ROOF_CLOSED_PERCENT..ROOF_OPEN_PERCENT }
            ?.let { if (it >= WINDOW_FULL_OPEN_FROM) ROOF_OPEN_PERCENT else it }

    fun setWindow(window: CarWindow, command: Int): Boolean =
        BydFeatures.writeInt(BODYWORK, window.controlField, command)

    /**
     * All four windows in one batch, the way the ROM's `setAllWindowState` does
     * it. Sent singly, back to back, the car drops some of them - two of four
     * moved, each time it was tried. If the batch call is refused the four are
     * sent one at a time with a pause between, which was measured to reach all
     * four; the caller is on a background thread, so the pause costs nothing
     * visible.
     */
    fun setAllWindows(command: Int): Boolean {
        val windows = CarWindow.entries
        if (BydFeatures.writeInts(BODYWORK, windows.map { it.controlField }, List(windows.size) { command })) {
            return true
        }
        var ok = true
        windows.forEachIndexed { i, window ->
            if (i > 0) Thread.sleep(WINDOW_WRITE_GAP_MS)
            ok = setWindow(window, command) && ok
        }
        return ok
    }

    /** The spacing that made four single window writes all land; 400 did, 0 did not. */
    private const val WINDOW_WRITE_GAP_MS = 400L

    // ---- Slope ---------------------------------------------------------------

    /**
     * The car's own pitch, from `SENSOR_AUTO_SLOPE` - what the ROM's
     * `BYDAutoSensorDevice.getSlope` reads and calls the "auto obliquity". Its
     * declared range is -60..60. The unit is not stated anywhere in the ROM;
     * it reads -1 to -2 on a garage floor and is shown as a percentage grade,
     * which is the reading the user asked for. Check it against a known hill.
     */
    fun slope(): Int? = reading(SENSOR, "SENSOR_AUTO_SLOPE")?.takeIf { it in SLOPE_RANGE }

    private const val SENSOR = "Sensor"
    private val SLOPE_RANGE = -60..60

    /**
     * The powered tailgate. It reports only shut or open, never how far, and is
     * driven through the register the car's voice assistant uses - the one
     * command path the ROM's setting wrapper exposes, `voiceCtlBackDoor`, whose
     * vocabulary is open 1, stop 2, close 3. The write path is *not yet exercised*
     * on the car: on the day the windows were, the tailgate was parked a hand's
     * width from a bench.
     */
    const val TAILGATE_OPEN = 1
    const val TAILGATE_STOP = 2
    const val TAILGATE_CLOSE = 3

    private const val DOOR_CLOSED = 0
    private const val DOOR_OPEN = 1

    /** Whether the car has a powered tailgate to drive; null until the bus has answered. */
    fun tailgateFitted(): Boolean? =
        reading(SETTING, "SET_CAR_BACK_DOOR_ELECTRIC_MODE_DET")?.let { it == 1 }

    /** Open or shut, or null while the car does not know (255, "undefined"). */
    fun tailgateOpen(): Boolean? = reading(BODYWORK, "BODYWORK_LUGGAGE_DOOR")?.let {
        when (it) {
            DOOR_OPEN -> true
            DOOR_CLOSED -> false
            else -> null
        }
    }

    fun setTailgate(command: Int): Boolean =
        BydFeatures.writeInt(SETTING, "SET_VOICE_CTRL_BACK_DOOR_SET", command)

    // ---- Readouts ----------------------------------------------------------

    /**
     * BYD reports "this car has no such signal" as one of two sentinels rather than
     * an error, so both have to be filtered or the bar shows 65535 km of range.
     */
    private fun reading(group: String, field: String): Int? =
        BydFeatures.readInt(group, field)?.takeIf { it != UNAVAILABLE && it != NOT_FITTED }

    private const val NOT_FITTED = -10011

    private const val STATISTIC = "Statistic"
    private const val LIGHT = "Light"
    private const val GEARBOX = "Gearbox"

    /** Ambient air temperature, in whatever unit the car is displaying. */
    fun outsideTemp(): Int? = reading(AC, "AC_TEMP_OUT")
        ?: reading("Instrument", "INSTRUMENT_DD_OUT_TEMP")

    /**
     * Cabin temperature, which this ROM does not put on the feature bus - the Ac
     * group carries the three setpoints and the outside reading, nothing for inside.
     * The head unit's own sensor is the nearest thing the bus offers.
     */
    fun insideTemp(): Int? = reading("Sensor", "SENSOR_TEMPERATURE")

    fun electricRangeKm(): Int? = reading(STATISTIC, "STATISTIC_ELEC_DRIVING_RANGE")

    fun batteryPercent(): Int? = reading(STATISTIC, "STATISTIC_SOC_BATTERY_PERCENTAGE")

    fun fuelRangeKm(): Int? = reading(STATISTIC, "STATISTIC_FUEL_DRIVING_RANGE")

    fun fuelPercent(): Int? = reading(STATISTIC, "STATISTIC_FUEL_PERCENTAGE")

    private const val GEARBOX_DEVICE = "gearbox.BYDAutoGearboxDevice"

    /**
     * Selected gear, as its letter.
     *
     * The signal this car populates is `GEARBOX_AUTO_MODE_TYPE`, which reads like a
     * description of the gearbox and went unrecognised as the lever position until
     * the ROM's own constants settled it: `GEARBOX_AUTO_MODE_P` is 1, and 1 is what a
     * parked car reports. The two "gear" signals with the obvious names answer
     * "not fitted" on this car, awake or asleep.
     *
     * `GEARBOX_ACTUAL_GERA_STATE` is the fallback for a car that carries that one
     * instead; it uses the other scale (`GEAR_P` is 3), so it gets its own table.
     */
    private val autoModeGears by lazy {
        listOf("P", "R", "N", "D", "M", "S").withIndex().associate { (index, letter) ->
            (BydFeatures.deviceConstant(GEARBOX_DEVICE, "GEARBOX_AUTO_MODE_$letter") ?: index + 1) to letter
        }
    }

    private val actualGears by lazy {
        mapOf("N" to 0, "R" to 1, "D" to 2, "P" to 3).entries.associate { (letter, fallback) ->
            (BydFeatures.deviceConstant(GEARBOX_DEVICE, "GEAR_$letter") ?: fallback) to letter
        }
    }

    fun gear(): String? =
        reading(GEARBOX, "GEARBOX_AUTO_MODE_TYPE")?.let { autoModeGears[it] }
            ?: reading(GEARBOX, "GEARBOX_ACTUAL_GERA_STATE")?.let { actualGears[it] }

    private const val SPEED = "Speed"

    /**
     * Road speed, whole km/h.
     *
     * Carried as a double, not an int - `BYDAutoSpeedDevice` grades it 0..282 and
     * every one of its speed constants is a double. Read as an int it answers with
     * the "not fitted" code, which is what made the speed look absent on a car that
     * reports it perfectly well.
     *
     * Three signals carry it; they are tried in order and the first plausible one
     * wins, since which of them a car populates varies.
     */
    private val SPEED_FIELDS = listOf("SPEED_AUTO_SPEED", "SPEED_FROM_GATEWAY", "SPEED_AUTO_SPEED_121")

    /** The platform's own ceiling; anything past it is a sentinel, not a reading. */
    private val SPEED_RANGE = 0.0..282.0

    fun speedKmh(): Int? {
        SPEED_FIELDS.forEach { field ->
            BydFeatures.readDouble(SPEED, field)
                ?.takeIf { it in SPEED_RANGE }
                ?.let { return Math.round(it).toInt() }
        }
        return null
    }

    enum class Beam { OFF, LOW, HIGH }

    /**
     * Both beams are plain flags: zero off, non-zero on.
     *
     * An earlier guess had low beam counting from one the way the seats do, which
     * left it reading "off" whenever it was lit. The car settles it: with the high
     * beam and both fog lights definitely out they all read 0, while low beam, side
     * lights and the daytime runners read 1 together - one encoding, not two.
     */
    fun headlights(): Beam? {
        val high = reading(LIGHT, "LIGHT_HIGH_BEAM_LIGHT")
        val low = reading(LIGHT, "LIGHT_LOW_BEAM_LIGHT")
        if (high == null && low == null) return null
        return when {
            (high ?: 0) > 0 -> Beam.HIGH
            (low ?: 0) > 0 -> Beam.LOW
            else -> Beam.OFF
        }
    }

    /**
     * The combined indicator signal's bits, measured on the car by sampling it at
     * 10 Hz through a known left and right indication: 1 with nothing on, 2 while the
     * left lamp is lit, 4 while the right one is. Hazards set both, so a bit test
     * covers that without a fourth constant.
     */
    private const val TURN_LEFT = 2
    private const val TURN_RIGHT = 4

    /**
     * The four tyre pressures in kPa, ordered front-left, front-right, rear-left,
     * rear-right, or null if the car reports none of them.
     *
     * The bus carries them as psi to one decimal held as a whole number - 384 is
     * 38.4 psi, which is the 265 kPa the car's own app shows. Paired corner for
     * corner against that app, matching on the temperatures, which are plain celsius.
     */
    fun tyrePressures(): IntArray? {
        val corners = intArrayOf(0, 0, 0, 0)
        var any = false
        listOf("LF", "RF", "LB", "RB").forEachIndexed { index, corner ->
            val raw = reading("Instrument", "INSTRUMENT_2IN1_${corner}_TYRE_PRESSURE")
                ?: return@forEachIndexed
            if (raw <= 0) return@forEachIndexed
            any = true
            corners[index] = Math.round(raw * PSI_TENTHS_TO_KPA)
        }
        return if (any) corners else null
    }

    /**
     * Cabin and outside particulates in µg/m³, as [inside, outside].
     *
     * The car measures both sides of the filter, which is the useful part - the pair
     * says whether recirculating is doing anything, not just what the air is like.
     */
    fun particulates(): IntArray? {
        val inside = reading("Pm2p5", "PM2P5_VALUE_IN")
        val outside = reading("Pm2p5", "PM2P5_VALUE_OUT")
        if (inside == null && outside == null) return null
        return intArrayOf(inside ?: -1, outside ?: -1)
    }

    /** One tenth of a psi, in kPa. */
    private const val PSI_TENTHS_TO_KPA = 0.6894757f

    /** The car's own warning bands, in kPa. */
    const val TYRE_LOW_KPA = 234
    const val TYRE_CRITICAL_KPA = 152

    /**
     * Which indicators are lit, as a bit per side: 1 left, 2 right, 3 hazards.
     *
     * This car carries them in one signal rather than a flag per side - the per-side
     * ids exist but sit at zero even mid-flash. Those are still read as a fallback,
     * for a car where they are the ones that work.
     *
     * Note this follows the lamp, not the stalk: it alternates with the flash, so the
     * caller has to hold the value or the arrow blinks in and out of the readout.
     */
    fun turnSignals(): Int? {
        reading(LIGHT, "LIGHT_TURN_SIGNAL_LIGHT")?.let { raw ->
            return (if (raw and TURN_LEFT != 0) 1 else 0) or
                (if (raw and TURN_RIGHT != 0) 2 else 0)
        }

        val left = reading(LIGHT, "LIGHT_LEFT_TURN_SIGNAL_LIGHT")
        val right = reading(LIGHT, "LIGHT_RIGHT_TURN_SIGNAL_LIGHT")
        if (left == null && right == null) return null
        return (if ((left ?: 0) > 0) 1 else 0) or (if ((right ?: 0) > 0) 2 else 0)
    }

    // ---- Diagnostics -------------------------------------------------------

    /**
     * Raw values for every signal, for working out an encoding this ROM does not
     * document. Logged once per connection rather than per poll.
     */
    fun describe(): String = listOf(
        "AC_POWER_STATE", "AC_COMPRESSOR_MODE", "AC_WIND_LEVEL", "AC_WIND_MODE",
        "AC_TEMP_MAIN", "AC_TEMP_DEPUTY", "AC_TEMPERATURE_UNIT", "AC_CYCLE_MODE",
        "AC_DEFROST_FRONT_STATE", "AC_DEFROST_REAR_STATE",
    ).joinToString(" ") { "$it=${BydFeatures.readInt(AC, it)}" } + " " + listOf(
        "SET_DRIVER_SEAT_HEATING_STATE", "SET_DRIVER_SEAT_VENTILATING_STATE",
        "SET_PASSENGER_SEAT_HEATING_STATE", "SET_PASSENGER_SEAT_VENTILATING_STATE",
        "SET_HAS_DRIVER_SEAT_HEATING", "SET_STEERING_WHEEL_HEAT_STATE",
        "SET_AUTO_STEER_WHEEL_HEATING_GEAR",
    ).joinToString(" ") { "$it=${BydFeatures.readInt(SETTING, it)}" }

    /**
     * Reads every signal the ROM declares and reports the ones this car answers.
     *
     * The declared list is the same on every BYD; what differs is which of them the
     * vehicle populates. A signal that is not fitted comes back as one of two
     * sentinels rather than an error, so the only way to know what this car really
     * offers is to ask it for everything once and see what comes back.
     *
     * Write targets are skipped - a `_SET` id is a command, not a reading.
     */
    fun scanGroup(group: String): String = buildString {
        val rows = BydFeatures.fieldsOf(group)
            .filterNot { it.endsWith("_SET") || it.contains("hal_only") }
            .sorted()
            .mapNotNull { field ->
                val id = BydFeatures.featureId(group, field) ?: return@mapNotNull null
                val value = BydFeatures.readId(id) ?: return@mapNotNull null
                if (value == UNAVAILABLE || value == NOT_FITTED) return@mapNotNull null
                "    $field = $value"
            }

        if (rows.isNotEmpty()) {
            appendLine("===== $group (${rows.size}) =====")
            rows.forEach { appendLine(it) }
            appendLine()
        }
    }

    /** Candidate signals for the readout items, to be checked against the car. */
    fun describeInfo(): String = listOf(
        // Gear: only GEARBOX_AUTO_MODE_TYPE answers on this car; the rest are here
        // so a car that carries a different one shows up in the log.
        GEARBOX to "GEARBOX_STATE",
        GEARBOX to "GEARBOX_ACTUAL_GERA_STATE",
        GEARBOX to "GEARBOX_AUTOMATIC_GEAR",
        GEARBOX to "GEARBOX_AUTOMATIC_GEAR_3rd",
        GEARBOX to "GEARBOX_MANUAL_MODE_LEVEL",
        GEARBOX to "GEARBOX_AUTO_MODE_TYPE",
        GEARBOX to "GEARBOX_TYPE",
        GEARBOX to "GEARBOX_EPB_STATE",
        GEARBOX to "GEARBOX_PARK_BRAKE_SWITCH",
        "Instrument" to "INSTRUMENT_GEAR_SYSTEM",
        "Instrument" to "INSTRUMENT_B_M_GEAR_SYSTEM",
        // Lights, including the indicators we have yet to show.
        LIGHT to "LIGHT_LOW_BEAM_LIGHT",
        LIGHT to "LIGHT_HIGH_BEAM_LIGHT",
        LIGHT to "LIGHT_SIDE_LIGHT",
        LIGHT to "LIGHT_FRONT_FOG_LIGHT",
        LIGHT to "LIGHT_REAR_FOG_LIGHT",
        LIGHT to "LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE",
        LIGHT to "LIGHT_LEFT_TURN_SIGNAL_LIGHT",
        LIGHT to "LIGHT_RIGHT_TURN_SIGNAL_LIGHT",
        LIGHT to "LIGHT_LEFT_TURN_SIGNAL_LIGHT_SWITCH_STATE",
        LIGHT to "LIGHT_RIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE",
        LIGHT to "LIGHT_CMD_DOUBLE_FLASH_STATE",
    ).joinToString(" ") { (g, f) -> "$f=${BydFeatures.readInt(g, f)}" }
}
