package com.example.esmerui.car

/** An individual vent, so the bar buttons can be toggled independently. */
enum class Vent { FACE, FOOT, SCREEN }

/**
 * Cabin airflow direction.
 *
 * The seven modes are exactly the seven non-empty combinations of the three vents,
 * so each vent can be switched on its own and the mode derived from the result.
 *
 * Values are read from the ROM rather than hardcoded - they are not in numeric order
 * on this unit (face+screen is 7, face+foot+screen is 6), so assuming a sequence
 * would quietly select the wrong vents.
 */
enum class Airflow(
    private val constant: String,
    val label: String,
    val vents: Set<Vent>,
) {
    FACE("AC_WINDMODE_FACE", "Face", setOf(Vent.FACE)),
    FACE_FOOT("AC_WINDMODE_FACEFOOT", "Face + feet", setOf(Vent.FACE, Vent.FOOT)),
    FOOT("AC_WINDMODE_FOOT", "Feet", setOf(Vent.FOOT)),
    FOOT_SCREEN("AC_WINDMODE_FOOTDEFROST", "Feet + screen", setOf(Vent.FOOT, Vent.SCREEN)),
    SCREEN("AC_WINDMODE_DEFROST", "Screen", setOf(Vent.SCREEN)),
    FACE_FOOT_SCREEN(
        "AC_WINDMODE_FACEFOOTDEFROST", "All vents", setOf(Vent.FACE, Vent.FOOT, Vent.SCREEN),
    ),
    FACE_SCREEN("AC_WINDMODE_FACEDEFROST", "Face + screen", setOf(Vent.FACE, Vent.SCREEN)),
    ;

    /** The ROM's value for this mode, or null if this car does not offer it. */
    val value: Int? by lazy {
        BydFeatures.deviceConstant("ac.BYDAutoAcDevice", constant)
    }

    companion object {
        private const val GROUP = "Ac"
        private const val READ = "AC_WIND_MODE"
        private const val WRITE = "AC_WIND_MODE_SET"

        fun current(): Airflow? {
            val v = BydFeatures.readInt(GROUP, READ) ?: return null
            return entries.firstOrNull { it.value == v }
        }

        fun of(vents: Set<Vent>): Airflow? = entries.firstOrNull { it.vents == vents }

        /** True if the car accepted the change. */
        fun set(mode: Airflow): Boolean {
            val v = mode.value ?: return false
            return BydFeatures.writeInt(GROUP, WRITE, v)
        }
    }
}
