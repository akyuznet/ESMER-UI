package com.example.esmerui.car

/**
 * The air-conditioning compressor, which is not the same thing as climate power.
 *
 * OverDrive's REST API only exposes the latter: its `power_off` shuts the whole
 * system down, blower included. A dash A/C button means something narrower - stop
 * cooling, keep blowing - and that is a separate signal, reachable only on the
 * feature bus.
 */
object Compressor {

    private const val GROUP = "Ac"
    private const val READ = "AC_COMPRESSOR_MODE"
    private const val WRITE = "AC_COMPRESSOR_MODE_SET"

    /** Fall back to 1/0 if the ROM hides the constants; every BYD build so far uses them. */
    private val onValue: Int by lazy {
        BydFeatures.deviceConstant("ac.BYDAutoAcDevice", "AC_COMPRESSOR_ON") ?: 1
    }

    private val offValue: Int by lazy {
        BydFeatures.deviceConstant("ac.BYDAutoAcDevice", "AC_COMPRESSOR_OFF") ?: 0
    }

    /** False on a car with no compressor signal, so the caller can fall back. */
    val isSupported: Boolean
        get() = BydFeatures.isAvailable && BydFeatures.featureId(GROUP, WRITE) != null

    /** Null when the car does not report it. */
    fun isOn(): Boolean? = BydFeatures.readInt(GROUP, READ)?.let { it == onValue }

    /** True if the car accepted the change. */
    fun set(on: Boolean): Boolean =
        BydFeatures.writeInt(GROUP, WRITE, if (on) onValue else offValue)
}
