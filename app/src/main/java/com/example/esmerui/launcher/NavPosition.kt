package com.example.esmerui.launcher

/** Screen edge the custom navigation bar is docked to. */
enum class NavPosition(val label: String) {
    BOTTOM("Bottom"),
    TOP("Top"),
    LEFT("Left"),
    RIGHT("Right");

    val isHorizontal: Boolean get() = this == BOTTOM || this == TOP

    companion object {
        fun from(name: String?): NavPosition =
            entries.firstOrNull { it.name == name } ?: BOTTOM
    }
}

/** How the bar fills its edge. */
enum class NavLayout(val label: String, val description: String) {

    /** Spans the edge like a stock system bar; targets spread evenly. */
    FULL_WIDTH("Full width", "Spans the edge, large evenly spaced targets"),

    /** A centred floating pill that leaves the rest of the edge free. */
    PILL("Floating pill", "Centred pill, rest of the edge stays clickable"),
    ;

    companion object {
        fun from(name: String?): NavLayout =
            entries.firstOrNull { it.name == name } ?: FULL_WIDTH
    }
}

/** What a built-in bar button does. */
enum class NavActionGroup(val label: String) {
    NAVIGATION("Navigation"),
    MEDIA("Volume"),
    CLIMATE("Climate"),
    SEATS("Seats"),
    HUD("Head-up display"),
    CLUSTER("Cluster projection"),
    ROOF("Roof"),
    BODY("Windows and tailgate"),
    INFO("Information"),
}

/**
 * A button that can sit on the navigation bar.
 *
 * [needsCar] marks the ones that talk to BYD's automotive SDK; the settings screen
 * hides those when the car does not expose them, rather than offering a dead button.
 */
enum class NavAction(
    val label: String,
    val group: NavActionGroup,
    val needsCar: Boolean = false,
    /** Renders as a three-cell stepper with a live readout, not a single icon. */
    val isGroup: Boolean = false,
    /** Shows a value instead of doing something; nothing happens when pressed. */
    val isReadout: Boolean = false,
    /**
     * Pressable *and* carrying a live position - the roof panels, the windows and
     * the tailgate, which are the one kind of thing on the bar that is neither a
     * switch nor a number but both: a press sends it somewhere, and how far it
     * has got is the point.
     */
    val isPercent: Boolean = false,
) {
    BACK("Back", NavActionGroup.NAVIGATION),
    HOME("Home", NavActionGroup.NAVIGATION),
    RECENTS("Recents", NavActionGroup.NAVIGATION),
    APPS("All apps", NavActionGroup.NAVIGATION),

    VOLUME_UP("Volume +", NavActionGroup.MEDIA),
    VOLUME_DOWN("Volume -", NavActionGroup.MEDIA),
    MUTE("Mute", NavActionGroup.MEDIA),

    /**
     * Stepper groups: one bar entry that renders as [-] [live value] [+], the way
     * the head unit's own climate bar does. They occupy three cells instead of one.
     */
    TEMP_GROUP("Temperature", NavActionGroup.CLIMATE, needsCar = true, isGroup = true),
    FAN_GROUP("Fan", NavActionGroup.CLIMATE, needsCar = true, isGroup = true),

    TEMP_UP("Temp +", NavActionGroup.CLIMATE, needsCar = true),
    TEMP_DOWN("Temp -", NavActionGroup.CLIMATE, needsCar = true),
    FAN_UP("Fan +", NavActionGroup.CLIMATE, needsCar = true),
    FAN_DOWN("Fan -", NavActionGroup.CLIMATE, needsCar = true),
    AC_TOGGLE("A/C", NavActionGroup.CLIMATE, needsCar = true),
    RECIRCULATE("Recirculate", NavActionGroup.CLIMATE, needsCar = true),
    DEFROST("Defrost", NavActionGroup.CLIMATE, needsCar = true),
    DEFROST_REAR("Rear defrost", NavActionGroup.CLIMATE, needsCar = true),

    AIRFLOW_FACE("Air face", NavActionGroup.CLIMATE, needsCar = true),
    AIRFLOW_FEET("Air feet", NavActionGroup.CLIMATE, needsCar = true),
    AIRFLOW_SCREEN("Air screen", NavActionGroup.CLIMATE, needsCar = true),
    STEERING_HEAT("Wheel heat", NavActionGroup.CLIMATE, needsCar = true),

    SEAT_HEAT("Heat L", NavActionGroup.SEATS, needsCar = true),
    SEAT_VENT("Cool L", NavActionGroup.SEATS, needsCar = true),
    SEAT_HEAT_PASSENGER("Heat R", NavActionGroup.SEATS, needsCar = true),
    SEAT_VENT_PASSENGER("Cool R", NavActionGroup.SEATS, needsCar = true),

    /**
     * The head-up display's own switches, driven through the same setting registers
     * the car's Settings app writes - the HUD itself is drawn by the cluster, so
     * these choose what it shows rather than drawing on it. The theme is a two-way
     * pick, so its button swaps glyph instead of lighting up; the others are plain
     * on/off. Brightness and height are steppers like the climate pair.
     */
    HUD_POWER("HUD", NavActionGroup.HUD, needsCar = true),
    HUD_NAV("HUD nav", NavActionGroup.HUD, needsCar = true),
    HUD_ADAS("HUD ADAS", NavActionGroup.HUD, needsCar = true),
    HUD_THEME("HUD theme", NavActionGroup.HUD, needsCar = true),
    HUD_BRIGHTNESS_GROUP("HUD brightness", NavActionGroup.HUD, needsCar = true, isGroup = true),
    HUD_HEIGHT_GROUP("HUD height", NavActionGroup.HUD, needsCar = true, isGroup = true),
    HUD_BRIGHTNESS_UP("HUD bright +", NavActionGroup.HUD, needsCar = true),
    HUD_BRIGHTNESS_DOWN("HUD bright -", NavActionGroup.HUD, needsCar = true),
    HUD_HEIGHT_UP("HUD height +", NavActionGroup.HUD, needsCar = true),
    HUD_HEIGHT_DOWN("HUD height -", NavActionGroup.HUD, needsCar = true),

    /** Casts the last-used app to the cluster, or stops the projection running. */
    CLUSTER_CAST("Cast to cluster", NavActionGroup.CLUSTER),
    /** Shows or hides the floating live mirror of the cluster. */
    CLUSTER_MIRROR("Cluster mirror", NavActionGroup.CLUSTER),

    /**
     * The glass panel and the blind under it. Both travel to a commanded
     * percentage rather than taking an open/close switch, so each button is a
     * toggle that also reports how far its panel has actually got.
     */
    SUNROOF("Sunroof", NavActionGroup.ROOF, needsCar = true, isPercent = true),
    SUNSHADE("Sunshade", NavActionGroup.ROOF, needsCar = true, isPercent = true),

    /**
     * The side glass and the tailgate, pressed the same three-way way as the roof:
     * off, stop, back. A window reports how far down it is; the tailgate reports
     * only shut or open, so its button shows that word instead of a number. Left
     * and right are the car's own sides, not the driver's.
     */
    WINDOW_LEFT_FRONT("Left front window", NavActionGroup.BODY, needsCar = true, isPercent = true),
    WINDOW_RIGHT_FRONT("Right front window", NavActionGroup.BODY, needsCar = true, isPercent = true),
    WINDOW_LEFT_REAR("Left rear window", NavActionGroup.BODY, needsCar = true, isPercent = true),
    WINDOW_RIGHT_REAR("Right rear window", NavActionGroup.BODY, needsCar = true, isPercent = true),
    WINDOWS_ALL("All windows", NavActionGroup.BODY, needsCar = true, isPercent = true),
    TAILGATE("Tailgate", NavActionGroup.BODY, needsCar = true, isPercent = true),

    /**
     * Readouts: an icon and a live value, pressed for nothing. The clock and date
     * come from the head unit; the rest are the car's own signals.
     */
    INFO_CLOCK("Clock", NavActionGroup.INFO, isReadout = true),
    INFO_DATE("Date", NavActionGroup.INFO, isReadout = true),
    /** Both in one cell, the time over the date, for a bar with room for one item. */
    INFO_CLOCK_DATE("Clock and date", NavActionGroup.INFO, isReadout = true),
    // The two radios show strength as bars rather than a number, and are the one
    // pair of readouts that respond to a press - straight to their own settings.
    INFO_SIGNAL("Signal", NavActionGroup.INFO, isReadout = true),
    INFO_WIFI("Wi-Fi", NavActionGroup.INFO, isReadout = true),
    INFO_BLUETOOTH("Bluetooth", NavActionGroup.INFO, isReadout = true),
    INFO_GEAR("Gear", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_SPEED("Speed", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_LIGHTS("Headlights", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TURN("Indicators", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TYRES("Tyre pressure BAR", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TYRES_PSI("Tyre pressure PSI", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TYRES_KPA("Tyre pressure kPa", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_PM25("Air quality", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_RANGE_EV("Electric range", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_RANGE_FUEL("Fuel range", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TEMP_IN("Inside temp", NavActionGroup.INFO, needsCar = true, isReadout = true),
    INFO_TEMP_OUT("Outside temp", NavActionGroup.INFO, needsCar = true, isReadout = true),
    /** The road's grade under the car, from its own pitch sensor. */
    INFO_SLOPE("Slope", NavActionGroup.INFO, needsCar = true, isReadout = true),
    /**
     * Readouts from Home Assistant: each shows the state of whichever entity the
     * Home Assistant tab assigned to it. Numbered rather than named, since what
     * they show is the user's choice.
     */
    INFO_HOME_1("Home 1", NavActionGroup.INFO, isReadout = true),
    INFO_HOME_2("Home 2", NavActionGroup.INFO, isReadout = true),
    INFO_HOME_3("Home 3", NavActionGroup.INFO, isReadout = true),
    INFO_HOME_4("Home 4", NavActionGroup.INFO, isReadout = true),
    INFO_HOME_5("Home 5", NavActionGroup.INFO, isReadout = true),
    INFO_HOME_6("Home 6", NavActionGroup.INFO, isReadout = true),
    ;

    /** A Home Assistant readout, and which of the six it is. */
    val isHome: Boolean get() = name.startsWith("INFO_HOME_")
    val homeSlot: Int? get() = if (isHome) name.substringAfterLast('_').toIntOrNull() else null

    /** The head-up display controls, which share one poll gate and one colour rule. */
    val isHud: Boolean get() = group == NavActionGroup.HUD

    /**
     * Everything that travels - roof panels, windows, tailgate - which share one
     * poll gate the way the HUD controls do. The name predates the windows; the
     * roof was the first of them.
     */
    val isRoof: Boolean get() = isPercent

    /**
     * The single-step actions a stepper is built from, down then up, or null for
     * anything that is not a stepper. Kept here so the bar, the grid and the command
     * dispatch cannot disagree about which button moves which value.
     */
    val steps: Pair<NavAction, NavAction>?
        get() = when (this) {
            TEMP_GROUP -> TEMP_DOWN to TEMP_UP
            FAN_GROUP -> FAN_DOWN to FAN_UP
            HUD_BRIGHTNESS_GROUP -> HUD_BRIGHTNESS_DOWN to HUD_BRIGHTNESS_UP
            HUD_HEIGHT_GROUP -> HUD_HEIGHT_DOWN to HUD_HEIGHT_UP
            else -> null
        }

    /**
     * The caption under a live value, so a bare number is not ambiguous. Shared by
     * the steppers and the travelling panels, which both draw a value with a label
     * tucked under it.
     */
    val stepperCaption: String
        get() = when (this) {
            TEMP_GROUP -> "TEMP"
            FAN_GROUP -> "FAN"
            HUD_BRIGHTNESS_GROUP -> "HUD BRIGHT"
            HUD_HEIGHT_GROUP -> "HUD HEIGHT"
            SUNROOF -> "ROOF"
            SUNSHADE -> "SHADE"
            WINDOW_LEFT_FRONT -> "L FRONT"
            WINDOW_RIGHT_FRONT -> "R FRONT"
            WINDOW_LEFT_REAR -> "L REAR"
            WINDOW_RIGHT_REAR -> "R REAR"
            WINDOWS_ALL -> "WINDOWS"
            TAILGATE -> "TAILGATE"
            else -> ""
        }

    companion object {
        fun from(name: String?): NavAction? = entries.firstOrNull { it.name == name }
    }
}
