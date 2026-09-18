package com.example.esmerui.car

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.example.esmerui.launcher.NavAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The read-only values the bar can display.
 *
 * Separate from [CarStatus] because nothing here is ever written, so none of it
 * needs the optimistic-update machinery: a readout is only ever as fresh as the
 * last poll, and that is all it claims to be.
 *
 * Every field is null until the car answers, and stays null on a car that does not
 * carry the signal - the bar shows a dash rather than a made-up number.
 */
object CarInfo {

    /** Nothing to show yet. Deliberately not "0", which would read as a real value. */
    const val NO_VALUE = "--"

    @Volatile
    var gear: String? = null

    /** Road speed in whole km/h; null until the car reports one. */
    @Volatile
    var speedKmh: Int? = null

    @Volatile
    var headlights: CarBus.Beam? = null

    /** A bit per side: 1 left, 2 right, 3 both - hazards, or a lane change mid-flash. */
    @Volatile
    var turnSignals: Int? = null

    /** kPa at each corner: front-left, front-right, rear-left, rear-right. */
    @Volatile
    var tyrePressures: IntArray? = null

    /** Particulates in µg/m³, as [inside, outside]. */
    @Volatile
    var particulates: IntArray? = null

    @Volatile
    var electricRangeKm: Int? = null

    @Volatile
    var batteryPercent: Int? = null

    @Volatile
    var fuelRangeKm: Int? = null

    @Volatile
    var fuelPercent: Int? = null

    @Volatile
    var insideTemp: Int? = null

    @Volatile
    var outsideTemp: Int? = null

    /**
     * How much of each radio's symbol is lit, or null when the radio is switched off
     * - which the bar draws as the symbol struck through.
     *
     * Zero is a third state and not the same as off: the radio is running but has
     * nothing, so the symbol shows its full outline with none of it green.
     */
    @Volatile
    var signalBars: Int? = null

    @Volatile
    var wifiBars: Int? = null

    /**
     * Bluetooth has no strength to show, so it uses the same three states with only
     * two lit steps: 0 for on-but-nothing-paired, 1 for a live connection.
     */
    @Volatile
    var bluetoothBars: Int? = null

    /** The platform grades Wi-Fi 0..3; the symbol adds the dot, so 1..4 are lit. */
    private const val WIFI_GRADES = 4

    /**
     * Profiles that mean "a phone is attached", covering both roles: the gateway ones
     * a phone-like device runs, and the client ones a head unit runs.
     */
    private val PHONE_PROFILES = listOf(
        BluetoothProfile.HEADSET,
        BluetoothProfile.A2DP,
        11, // A2DP_SINK
        16, // HEADSET_CLIENT
        17, // PBAP_CLIENT
    )

    /**
     * Which readouts are actually on a bar. Every signal here is a round trip to the
     * car, so reading the ones nobody is showing is pure waste - a bar with a clock
     * on it should not be polling the fuel tank.
     */
    @Volatile
    var shown: Set<NavAction> = emptySet()

    private fun showing(vararg actions: NavAction) = actions.any { it in shown }

    private var turnSeenAt = 0L

    /**
     * A little longer than one flash, so the arrow stays lit through the dark half.
     *
     * The signal follows the lamp and the poll runs at about the flash rate, so
     * without this the arrow would appear and vanish more or less at random.
     */
    private const val TURN_HOLD_MS = 2_500L

    private fun holdTurnSignals(sides: Int?) {
        if (sides != null && sides != 0) {
            turnSignals = sides
            turnSeenAt = SystemClock.elapsedRealtime()
            return
        }
        // Nothing lit: let the last side stand briefly before clearing it.
        if (SystemClock.elapsedRealtime() - turnSeenAt >= TURN_HOLD_MS) turnSignals = sides
    }

    /**
     * The poll runs once a second, which is what the gear, speed, lights and
     * indicators need. The tyres, the ranges, the air and the temperatures move on
     * a scale of minutes, so they are read on every [SLOW_EVERY]th pass - a dozen
     * binder round trips a second saved, for a lag no one can see.
     */
    private const val SLOW_EVERY = 5
    private var pollCount = 0

    /** Blocking - called from the same poll as [CarStatus.refresh]. */
    fun refresh() {
        if (!CarBus.isAvailable) return
        if (showing(NavAction.INFO_GEAR)) gear = CarBus.gear()
        if (showing(NavAction.INFO_SPEED)) speedKmh = CarBus.speedKmh()
        if (showing(NavAction.INFO_LIGHTS)) headlights = CarBus.headlights()
        if (showing(NavAction.INFO_TURN)) holdTurnSignals(CarBus.turnSignals())

        if (pollCount++ % SLOW_EVERY != 0) return
        if (showing(NavAction.INFO_TYRES, NavAction.INFO_TYRES_PSI, NavAction.INFO_TYRES_KPA)) tyrePressures = CarBus.tyrePressures()
        if (showing(NavAction.INFO_RANGE_EV)) {
            electricRangeKm = CarBus.electricRangeKm()
            batteryPercent = CarBus.batteryPercent()
        }
        if (showing(NavAction.INFO_RANGE_FUEL)) {
            fuelRangeKm = CarBus.fuelRangeKm()
            fuelPercent = CarBus.fuelPercent()
        }
        if (showing(NavAction.INFO_TEMP_IN)) insideTemp = CarBus.insideTemp()
        if (showing(NavAction.INFO_TEMP_OUT)) outsideTemp = CarBus.outsideTemp()
        if (showing(NavAction.INFO_PM25)) particulates = CarBus.particulates()
    }

    /**
     * Reads the modem once, for the signal readout.
     *
     * [SignalStrength.getLevel] is the same 0..4 the status bar draws, and unlike the
     * raw dBm it needs no permission on this platform.
     */
    /**
     * Whether each radio is on is checked every poll, so a switch flipped shows
     * within the second. How strong it is only every [SLOW_EVERY]th: the strength
     * reads are the expensive half - a binder round trip into telephony, another
     * into Wi-Fi, and one per profile into Bluetooth - and cost more than every
     * signal read from the car put together. A radio that has just come on is read
     * in full at once rather than waiting its turn, so it never shows blank bars.
     */
    private var signalTick = 0

    fun refreshSignal(context: Context) {
        val strength = signalTick++ % SLOW_EVERY == 0
        if (showing(NavAction.INFO_SIGNAL)) {
            signalBars = readSignalBars(context, strength || signalBars == null)
        }
        if (showing(NavAction.INFO_WIFI)) {
            wifiBars = readWifiBars(context, strength || wifiBars == null)
        }
        if (showing(NavAction.INFO_BLUETOOTH)) {
            bluetoothBars = readBluetoothBars(strength || bluetoothBars == null)
        }
    }

    private fun readSignalBars(context: Context, strength: Boolean): Int? =
        runCatching {
            // Airplane mode or no usable SIM means the modem is not running at all,
            // which is the struck-through state rather than a weak signal.
            val airplane = Settings.Global.getInt(
                context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0,
            ) == 1
            if (airplane) return@runCatching null

            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (telephony.simState != TelephonyManager.SIM_STATE_READY) return@runCatching null
            // Switching cellular off on this unit means switching its data off; the
            // modem stays registered for voice and still reports a healthy signal,
            // so the bars have to follow the toggle rather than the reception.
            if (!mobileDataOn(context)) return@runCatching null
            if (!strength) return@runCatching signalBars

            @Suppress("DEPRECATION")
            val level = telephony.signalStrength?.level ?: return@runCatching 0
            // Level 0 is "none or unknown", so it lights nothing; 1..4 are the real
            // grades and spread across the remaining bars, with the top one reserved
            // for a full-strength signal.
            if (level <= 0) 0 else level + 1
        }.getOrNull()

    private fun readWifiBars(context: Context, strength: Boolean): Int? =
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) return@runCatching null
            if (!strength) return@runCatching wifiBars

            @Suppress("DEPRECATION")
            val rssi = wifi.connectionInfo?.rssi ?: return@runCatching 0
            // An unassociated radio reports a floor value: the radio is on, so no
            // slash, but there is nothing to colour in either.
            if (rssi == Int.MIN_VALUE || rssi <= -127) return@runCatching 0

            @Suppress("DEPRECATION")
            // The dot counts as the first lit part, so a joined radio is never blank.
            WifiManager.calculateSignalLevel(rssi, WIFI_GRADES) + 1
        }.getOrNull()

    private fun readBluetoothBars(strength: Boolean): Int? =
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runCatching null
            if (!adapter.isEnabled) return@runCatching null
            if (!strength) return@runCatching bluetoothBars
            // The head unit is the *client* side of the link - it runs A2dpSinkService
            // and HeadsetClientService, not the source and gateway a phone runs - so
            // the plain A2DP and HEADSET states read as disconnected with a phone
            // plainly connected. Those profile ids are @hide, hence the literals.
            val connected = PHONE_PROFILES.any {
                adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED
            }
            if (connected) 1 else 0
        }.getOrNull()

    /**
     * Whether mobile data is switched on for the subscription that carries it.
     *
     * Read from the setting rather than `TelephonyManager.isDataEnabled`, which wants
     * READ_PHONE_STATE - a runtime permission for a status glyph. The key is
     * per-subscription: the plain `mobile_data` still read 1 on this dual-SIM unit
     * with data switched off, which is what kept the bars lit.
     */
    private fun mobileDataOn(context: Context): Boolean {
        val resolver = context.contentResolver
        val subId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        val perSubscription = if (subId >= 0) {
            runCatching { Settings.Global.getInt(resolver, "mobile_data$subId") }.getOrNull()
        } else {
            null
        }

        val value = perSubscription
            ?: runCatching { Settings.Global.getInt(resolver, "mobile_data") }.getOrNull()
        // Unknown is treated as on: a readout that blanks itself on an unfamiliar ROM
        // is worse than one that occasionally shows bars it should not.
        return value != 0
    }

    // ---- Formatting --------------------------------------------------------

    /** Twelve-hour with the meridiem, whatever the unit's own clock is set to. */
    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    fun clock(): String = clockFormat.format(Date())

    fun date(): String = dateFormat.format(Date())

    private fun km(value: Int?): String = value?.let { "$it km" } ?: NO_VALUE

    private fun degrees(value: Int?): String = value?.let { "$it°" } ?: NO_VALUE

    /** The text under an item's icon, or a dash when the car has not answered. */
    fun valueFor(action: NavAction): String = when (action) {
        NavAction.INFO_CLOCK -> clock()
        NavAction.INFO_DATE -> date()
        // These draw their state as a symbol, so there is no text to add.
        NavAction.INFO_SIGNAL, NavAction.INFO_WIFI, NavAction.INFO_BLUETOOTH,
        NavAction.INFO_TURN, NavAction.INFO_TYRES, NavAction.INFO_TYRES_PSI, NavAction.INFO_TYRES_KPA -> ""
        NavAction.INFO_GEAR -> gear ?: NO_VALUE
        NavAction.INFO_SPEED -> speedKmh?.toString() ?: NO_VALUE
        // The beam is in the icon's colour, so spelling it out as well would be
        // saying the same thing twice.
        NavAction.INFO_LIGHTS -> ""

        NavAction.INFO_RANGE_EV -> km(electricRangeKm)
        NavAction.INFO_RANGE_FUEL -> km(fuelRangeKm)
        NavAction.INFO_TEMP_IN -> degrees(insideTemp)
        NavAction.INFO_TEMP_OUT -> degrees(outsideTemp)
        // Both sides of the filter, stacked - the caption below carries the outside.
        NavAction.INFO_PM25 -> particulates?.getOrNull(0)?.takeIf { it >= 0 }
            ?.let { "IN: $it" } ?: "IN: $NO_VALUE"
        else -> NO_VALUE
    }

    /**
     * The smaller line under the value - the percentage behind a range, so the pair
     * reads the way the instrument cluster prints it. Null where there is nothing
     * useful to add.
     */
    fun captionFor(action: NavAction): String? = when (action) {
        NavAction.INFO_RANGE_EV -> batteryPercent?.let { "$it%" }
        NavAction.INFO_RANGE_FUEL -> fuelPercent?.let { "$it%" }
        NavAction.INFO_TEMP_IN -> "IN"
        NavAction.INFO_TEMP_OUT -> "OUT"
        // The number alone could be anything; the unit is what names it.
        NavAction.INFO_SPEED -> "km/h"
        NavAction.INFO_PM25 -> particulates?.getOrNull(1)?.takeIf { it >= 0 }
            ?.let { "OUT: $it" } ?: "OUT: $NO_VALUE"
        else -> null
    }

    /** Green when the reading means something is active, as the buttons do. */
    fun isActive(action: NavAction): Boolean = when (action) {
        NavAction.INFO_LIGHTS -> headlights != null && headlights != CarBus.Beam.OFF
        NavAction.INFO_GEAR -> gear != null && gear != "P"
        else -> false
    }
}
