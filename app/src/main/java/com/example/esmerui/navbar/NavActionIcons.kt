package com.example.esmerui.navbar

import androidx.annotation.DrawableRes
import com.example.esmerui.R
import com.example.esmerui.car.CarBus
import com.example.esmerui.car.CarStatus
import com.example.esmerui.launcher.NavAction

/** Single source of truth for action icons, shared by the bar and the settings list. */
object NavActionIcons {

    /**
     * The icon for an action right now.
     *
     * A few actions draw differently depending on the car: the ESMERUI set ships
     * separate artwork for seat level 1 and level 2, and opposite glyphs for
     * recirculating vs fresh air, so the button shows what the car is doing rather
     * than just which button it is.
     */
    @DrawableRes
    fun current(action: NavAction): Int = when (action) {
        NavAction.RECIRCULATE ->
            if (CarStatus.recirculateOn) R.drawable.ic_recirculate else R.drawable.ic_fresh_air

        NavAction.SEAT_HEAT ->
            if (CarStatus.seatHeatLevel >= 2) R.drawable.ic_seat_heat_2 else R.drawable.ic_seat_heat

        NavAction.SEAT_VENT ->
            if (CarStatus.seatVentLevel >= 2) R.drawable.ic_seat_vent_2 else R.drawable.ic_seat_vent

        NavAction.SEAT_HEAT_PASSENGER ->
            if (CarStatus.seatHeatLevelPassenger >= 2) R.drawable.ic_seat_heat_2_p
            else R.drawable.ic_seat_heat_p

        NavAction.SEAT_VENT_PASSENGER ->
            if (CarStatus.seatVentLevelPassenger >= 2) R.drawable.ic_seat_vent_2_p
            else R.drawable.ic_seat_vent_p

        // Two themes, two glyphs: the button shows the one that is on.
        NavAction.HUD_THEME ->
            if (CarStatus.hudTheme == CarBus.HUD_THEME_SNOW) R.drawable.ic_hud_snow
            else R.drawable.ic_hud_theme

        else -> of(action)
    }

    /**
     * The icon a readout shows on the bar, or 0 for none.
     *
     * The clock and the date read as what they are - "10:15", "17 Sep" - so a glyph
     * beside them only costs width. The settings picker still uses [of], where the
     * tile needs something to look at.
     */
    @DrawableRes
    fun onBar(action: NavAction): Int = when (action) {
        // The gear letter and the speed read as what they are too, and both are
        // glanced at rather than read - width spent on a glyph is width not spent
        // on the number.
        NavAction.INFO_CLOCK, NavAction.INFO_DATE,
        NavAction.INFO_GEAR, NavAction.INFO_SPEED -> 0
        else -> of(action)
    }

    /** Level 0 is the starting point; the bar swaps in the right one as it reads. */

    /** The action's default icon, ignoring car state - used by the settings picker. */
    @DrawableRes
    fun of(action: NavAction): Int = when (action) {
        NavAction.BACK -> R.drawable.ic_arrow_back
        NavAction.HOME -> R.drawable.ic_home
        NavAction.RECENTS -> R.drawable.ic_recents
        NavAction.APPS -> R.drawable.ic_apps

        NavAction.VOLUME_UP -> R.drawable.ic_volume_up
        NavAction.VOLUME_DOWN -> R.drawable.ic_volume_down
        NavAction.MUTE -> R.drawable.ic_volume_off

        NavAction.TEMP_UP -> R.drawable.ic_thermostat
        NavAction.TEMP_DOWN -> R.drawable.ic_thermostat
        NavAction.FAN_UP -> R.drawable.ic_fan
        NavAction.FAN_DOWN -> R.drawable.ic_fan
        NavAction.AC_TOGGLE -> R.drawable.ic_ac_unit
        NavAction.RECIRCULATE -> R.drawable.ic_recirculate
        NavAction.DEFROST -> R.drawable.ic_defrost
        NavAction.DEFROST_REAR -> R.drawable.ic_defrost_rear
        NavAction.STEERING_HEAT -> R.drawable.ic_thermostat

        NavAction.SEAT_HEAT -> R.drawable.ic_seat_heat
        NavAction.SEAT_VENT -> R.drawable.ic_seat_vent
        NavAction.SEAT_HEAT_PASSENGER -> R.drawable.ic_seat_heat_p
        NavAction.SEAT_VENT_PASSENGER -> R.drawable.ic_seat_vent_p

        NavAction.AIRFLOW_FACE -> R.drawable.ic_airflow_face
        NavAction.AIRFLOW_FEET -> R.drawable.ic_airflow_feet
        NavAction.AIRFLOW_SCREEN -> R.drawable.ic_airflow_screen

        // Groups draw their own +/-; this is only for the settings picker tile.
        NavAction.TEMP_GROUP -> R.drawable.ic_thermostat
        NavAction.FAN_GROUP -> R.drawable.ic_fan

        NavAction.HUD_POWER -> R.drawable.ic_hud
        NavAction.HUD_NAV -> R.drawable.ic_hud_nav
        NavAction.HUD_ADAS -> R.drawable.ic_hud_adas
        NavAction.HUD_THEME -> R.drawable.ic_hud_theme
        NavAction.HUD_BRIGHTNESS_GROUP, NavAction.HUD_BRIGHTNESS_UP,
        NavAction.HUD_BRIGHTNESS_DOWN -> R.drawable.ic_hud_brightness
        NavAction.HUD_HEIGHT_GROUP, NavAction.HUD_HEIGHT_UP,
        NavAction.HUD_HEIGHT_DOWN -> R.drawable.ic_hud_height

        NavAction.INFO_CLOCK -> R.drawable.ic_info_clock
        NavAction.INFO_DATE -> R.drawable.ic_info_date
        NavAction.INFO_SIGNAL -> R.drawable.ic_info_signal
        NavAction.INFO_WIFI -> R.drawable.ic_wifi_4
        NavAction.INFO_BLUETOOTH -> R.drawable.ic_bt_1
        NavAction.INFO_TURN -> R.drawable.ic_turn_3
        NavAction.INFO_TYRES -> R.drawable.ic_tyre
        NavAction.INFO_TYRES_PSI -> R.drawable.ic_tyre
        NavAction.INFO_TYRES_KPA -> R.drawable.ic_tyre
        NavAction.INFO_PM25 -> R.drawable.ic_air_quality
        NavAction.INFO_GEAR -> R.drawable.ic_info_gear
        NavAction.INFO_SPEED -> R.drawable.ic_info_speed
        NavAction.INFO_LIGHTS -> R.drawable.ic_info_lights
        NavAction.INFO_RANGE_EV -> R.drawable.ic_info_battery
        NavAction.INFO_RANGE_FUEL -> R.drawable.ic_info_fuel
        NavAction.INFO_TEMP_IN -> R.drawable.ic_info_temp
        NavAction.INFO_TEMP_OUT -> R.drawable.ic_info_temp
    }
}
