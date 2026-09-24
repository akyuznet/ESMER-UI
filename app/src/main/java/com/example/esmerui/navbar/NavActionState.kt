package com.example.esmerui.navbar

import com.example.esmerui.car.CarStatus
import com.example.esmerui.car.Vent
import com.example.esmerui.launcher.NavAction

/**
 * Which bar actions change appearance with the car, and how.
 *
 * Two separate questions, because they do not always coincide: a button can need
 * redrawing without needing to be highlighted. Kept apart from the drawing code so
 * the bar has one place to ask, and so actions with no on/off meaning (Back, Home,
 * an app shortcut) are never treated as toggles.
 */
object NavActionState {

    /**
     * Each mode knows its vents, so a button lights whenever its own vent is open.
     *
     * With the climate system off, none of them count as open: the car goes on
     * reporting whichever mode it was left in, but nothing is blowing, so a lit
     * button would promise air that is not coming. The compressor is irrelevant
     * here - the vents are just as open blowing cabin air as blowing cold air.
     */
    private fun ventOpen(vent: Vent): Boolean =
        CarStatus.climateOn && CarStatus.airflow?.vents?.contains(vent) == true

    /** Appearance depends on car state - the icon, the tint, or both. */
    fun isStateful(action: NavAction): Boolean = when (action) {
        NavAction.AC_TOGGLE,
        NavAction.RECIRCULATE,
        NavAction.DEFROST,
        NavAction.DEFROST_REAR,
        NavAction.STEERING_HEAT,
        NavAction.SEAT_HEAT,
        NavAction.SEAT_VENT,
        NavAction.SEAT_HEAT_PASSENGER,
        NavAction.SEAT_VENT_PASSENGER,
        NavAction.AIRFLOW_FACE,
        NavAction.AIRFLOW_FEET,
        NavAction.AIRFLOW_SCREEN,
        NavAction.HUD_POWER,
        NavAction.HUD_NAV,
        NavAction.HUD_ADAS,
        NavAction.HUD_THEME,
        NavAction.SUNROOF,
        NavAction.SUNSHADE -> true

        else -> false
    }

    /**
     * Whether the button turns green when active.
     *
     * Recirculation is deliberately excluded: its two icons already say which mode
     * the cabin is in, so colouring it as well would be saying the same thing twice
     * - and would wrongly imply fresh air is the "off" state rather than the other
     * half of a pair. The HUD theme is the same shape of thing: Classic and Snow
     * are two choices, not on and off, and the glyph already says which.
     */
    fun showsActiveColour(action: NavAction): Boolean =
        isStateful(action) && action != NavAction.RECIRCULATE && action != NavAction.HUD_THEME

    fun isActive(action: NavAction): Boolean = when (action) {
        // The button switches cooling, so it reports cooling - but a compressor
        // cannot run with the system down, and the car goes on reporting the last
        // setting after a shutdown, so that has to be discounted.
        NavAction.AC_TOGGLE -> CarStatus.climateOn && CarStatus.compressorOn
        NavAction.RECIRCULATE -> CarStatus.recirculateOn
        // Front defrost drives the blower, so it dies with the climate system.
        // Rear defrost is a heated grid in the glass and keeps working regardless.
        NavAction.DEFROST -> CarStatus.climateOn && CarStatus.defrostOn
        NavAction.DEFROST_REAR -> CarStatus.defrostRearOn
        NavAction.STEERING_HEAT -> CarStatus.steeringHeatOn
        // Seat heat and cool are levels, not switches: any level above off is "on".
        NavAction.SEAT_HEAT -> CarStatus.seatHeatLevel > 0
        NavAction.SEAT_VENT -> CarStatus.seatVentLevel > 0
        NavAction.SEAT_HEAT_PASSENGER -> CarStatus.seatHeatLevelPassenger > 0
        NavAction.SEAT_VENT_PASSENGER -> CarStatus.seatVentLevelPassenger > 0

        NavAction.AIRFLOW_FACE -> ventOpen(Vent.FACE)
        NavAction.AIRFLOW_FEET -> ventOpen(Vent.FOOT)
        NavAction.AIRFLOW_SCREEN -> ventOpen(Vent.SCREEN)

        NavAction.HUD_POWER -> CarStatus.hudOn
        // The two content switches only mean anything with the display lit, the
        // way a vent only counts as open with the blower running.
        NavAction.HUD_NAV -> CarStatus.hudOn && CarStatus.hudNavigationOn
        NavAction.HUD_ADAS -> CarStatus.hudOn && CarStatus.hudAdasOn

        // Any opening at all counts, including a panel still on its way: the
        // percentage beside the icon says how far, and the colour says "not shut".
        NavAction.SUNROOF, NavAction.SUNSHADE ->
            (CarStatus.roofPercent(action) ?: 0) > 0

        else -> false
    }
}
