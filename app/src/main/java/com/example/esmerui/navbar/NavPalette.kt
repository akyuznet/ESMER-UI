package com.example.esmerui.navbar

import android.graphics.Color
import com.example.esmerui.car.CarBus
import com.example.esmerui.car.CarInfo
import com.example.esmerui.home.HomeAssistant
import com.example.esmerui.launcher.NavAction

/**
 * The colours the bar draws with, and the rules for choosing between them.
 *
 * Shared rather than private to the bar because the same actions can now sit on the
 * home grid, and a gear that reads red on one surface and white on the other would
 * be worse than having no colour at all.
 */
object NavPalette {

    /** Matches OnSurface / Accent in the Compose theme. */
    val ICON = Color.parseColor("#E8EAED")
    val PRESSED = Color.parseColor("#4FC3F7")

    /** Green means "this function is currently on" - A/C, defrost, seat heat. */
    val ACTIVE = Color.parseColor("#4ADE80")

    /** Main beam, in the blue every dashboard uses for it. */
    val BEAM = Color.parseColor("#4FA8FF")

    /** A tank or battery running down: amber, then red. */
    val WARN = Color.parseColor("#FBBF24")
    val LOW = Color.parseColor("#F87171")

    /** Captions are deliberately dimmer than their value. */
    val CAPTION = Color.parseColor("#9AA3AD")
    val CAPTION_ACTIVE = Color.parseColor("#8C4ADE80")

    const val LEVEL_LOW_PERCENT = 20
    const val LEVEL_WARN_PERCENT = 40

    /**
     * What colour a readout should be drawn in.
     *
     * Headlights follow the dashboard's own convention: nothing lit is white, dipped
     * beam green, main beam blue - the colour every car uses for it, so it reads
     * without a legend.
     */
    fun infoColour(action: NavAction): Int = if (action.isHome) {
        // A switch or a binary sensor that is on reads as active, like a button.
        if (HomeAssistant.isOn(action)) ACTIVE else ICON
    } else when (action) {
        NavAction.INFO_LIGHTS -> when (CarInfo.headlights) {
            CarBus.Beam.HIGH -> BEAM
            CarBus.Beam.LOW -> ACTIVE
            else -> ICON
        }

        // Reverse is the one shift position worth flagging on its own - the car is
        // about to move the way the driver is not facing. Everything else out of park
        // is simply "ready to go", which is what green already says.
        NavAction.INFO_GEAR -> when (CarInfo.gear) {
            "R" -> LOW
            null, "P" -> ICON
            else -> ACTIVE
        }

        NavAction.INFO_RANGE_EV -> levelColour(CarInfo.batteryPercent)
        NavAction.INFO_RANGE_FUEL -> levelColour(CarInfo.fuelPercent)

        else -> if (CarInfo.isActive(action)) ACTIVE else ICON
    }

    /**
     * How full reads as a colour, the way a fuel gauge does: comfortable, getting on,
     * and find a pump. White until the car has told us anything, so an unknown level
     * never looks like a warning.
     */
    fun levelColour(percent: Int?): Int = when {
        percent == null -> ICON
        percent < LEVEL_LOW_PERCENT -> LOW
        percent < LEVEL_WARN_PERCENT -> WARN
        else -> ACTIVE
    }

    /** A tyre pressure in kPa, against the car's own warning bands. */
    fun tyreColour(kpa: Int?): Int = when {
        kpa == null -> ICON
        kpa < CarBus.TYRE_CRITICAL_KPA -> LOW
        kpa < CarBus.TYRE_LOW_KPA -> WARN
        else -> ICON
    }
}
