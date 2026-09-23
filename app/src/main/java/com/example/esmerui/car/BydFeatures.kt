package com.example.esmerui.car

import android.content.Context
import android.util.Log
import com.example.esmerui.launcher.window.HiddenApi

/**
 * BYD's generic feature bus, reached through `BYDAutoDeviceManager`.
 *
 * This is the interface Dudu Launcher uses, and unlike the per-device wrappers
 * (`BYDAutoAcDevice` and friends) it is *not* gated on the signature-level
 * `BYDAUTO_*_GET` / `_SET` permissions - verified on this unit by reading and
 * writing with none of them held. Every signal is addressed by a feature id that
 * encodes its device, so the leading argument is ignored.
 *
 * Ids are assigned in the class initialiser rather than baked into the dex, so they
 * can only be read from a running VM - hence the reflection and the cache.
 */
object BydFeatures {

    private const val TAG = "BydFeatures"

    /** The SDK's success code; anything else is a refusal. */
    private const val COMMAND_SUCCESS = 0

    /** Ignored by the platform, since the feature id carries the device. */
    private const val DEVICE_ANY = 0

    private var manager: Any? = null
    private var getInt: java.lang.reflect.Method? = null
    private var getDouble: java.lang.reflect.Method? = null
    private var setInt: java.lang.reflect.Method? = null
    private val ids = mutableMapOf<String, Int?>()

    val isAvailable: Boolean get() = manager != null

    fun init(context: Context): Boolean {
        if (manager != null) return true
        HiddenApi.exempted

        return runCatching {
            val clazz = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            getInt = clazz.getMethod(
                "getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            getDouble = runCatching {
                clazz.getMethod(
                    "getDouble", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            setInt = clazz.getMethod(
                "setInt",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            manager = instance
            instance != null
        }.onFailure {
            Log.w(TAG, "BYDAutoDeviceManager unavailable: ${it.cause ?: it}")
        }.getOrDefault(false)
    }

    /**
     * Every nested group, for the fallback search below. Ordered with the climate and
     * body groups first so the common misses resolve in a handful of lookups.
     */
    private val GROUPS = listOf(
        "Ac", "Setting", "Bodywork", "Instrument", "Adas", "Audio", "Charging", "Door",
        "Energy", "Engine", "Func", "Gb", "Gearbox", "Light", "Location", "Motor",
        "Mqtt", "Ota", "Panorama", "Phone", "Pm2p5", "Power", "Property", "Qcfs",
        "Radar", "Radio", "Rear", "Reminder", "Rescue", "Rse", "Safety", "Security",
        "Sensor", "Signal", "Special", "Speed", "Statistic", "Test", "Time", "Tyre",
        "Vehicle", "Version", "Video", "Wiper", "Yun", "Aux", "Bigdata", "Collision",
        "Cpu", "Dtc",
    )

    /**
     * Resolves `BYDAutoFeatureIds$<group>.<field>`, caching hits and misses alike.
     *
     * Falls back to searching the other groups when the named one does not have it:
     * BYD moves signals between groups across ROMs - seat climate sits under Setting
     * on this one and under Ac on others - and a caller naming the wrong group should
     * cost a slow first lookup, not a dead button.
     */
    fun featureId(group: String, field: String): Int? = ids.getOrPut("$group.$field") {
        lookup(group, field) ?: GROUPS.asSequence()
            .filter { it != group }
            .mapNotNull { lookup(it, field) }
            .firstOrNull()
            .also { if (it == null) Log.w(TAG, "no feature id for $field") }
    }

    private fun lookup(group: String, field: String): Int? = runCatching {
        Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$$group")
            .getField(field).getInt(null)
    }.getOrNull()

    /** The nested groups the ROM actually ships, for a full sweep of the bus. */
    fun groups(): List<String> = runCatching {
        Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            .declaredClasses
            .mapNotNull { it.simpleName }
            .sorted()
    }.getOrDefault(GROUPS)

    /** Every public int constant on a group, which is its list of signal names. */
    fun fieldsOf(group: String): List<String> = runCatching {
        Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$$group")
            .fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name }
    }.getOrDefault(emptyList())

    /** Reads by id directly, skipping the name cache - used only by the sweep. */
    fun readId(id: Int): Int? {
        val target = manager ?: return null
        return runCatching { getInt?.invoke(target, DEVICE_ANY, id) as? Int }.getOrNull()
    }

    /** Reads a constant declared on one of the device classes, e.g. a mode value. */
    fun deviceConstant(deviceClass: String, field: String): Int? = runCatching {
        Class.forName("android.hardware.bydauto.$deviceClass").getField(field).getInt(null)
    }.getOrNull()

    fun readInt(group: String, field: String): Int? {
        val id = featureId(group, field) ?: return null
        val target = manager ?: return null
        return runCatching { getInt?.invoke(target, DEVICE_ANY, id) as? Int }
            .onFailure { Log.w(TAG, "read $group.$field failed: ${it.cause ?: it}") }
            .getOrNull()
    }

    /**
     * Reads a signal the platform carries as a double rather than an int.
     *
     * A handful are: road speed is graded 0..282 in `BYDAutoSpeedDevice`'s own
     * constants, all of them doubles. Asking [readInt] for one of those gets the
     * "not fitted" code, which is how the speed looked absent when it is simply
     * a different type on the same bus.
     */
    fun readDouble(group: String, field: String): Double? {
        val id = featureId(group, field) ?: return null
        val target = manager ?: return null
        return runCatching { getDouble?.invoke(target, DEVICE_ANY, id) as? Double }
            .onFailure { Log.w(TAG, "readDouble $group.$field failed: ${it.cause ?: it}") }
            .getOrNull()
    }

    /**
     * Writes by raw id, for probing a register this ROM names differently, or not
     * at all. Diagnostics only; everything the bar does goes through names.
     */
    fun writeId(id: Int, value: Int): Boolean {
        val target = manager ?: return false
        val result = runCatching { setInt?.invoke(target, DEVICE_ANY, id, value) as? Int }
            .onFailure { Log.w(TAG, "write id ${"0x%08X".format(id)} failed: ${it.cause ?: it}") }
            .getOrNull()
        if (result != COMMAND_SUCCESS) {
            Log.w(TAG, "write id ${"0x%08X".format(id)}=$value returned $result")
        }
        return result == COMMAND_SUCCESS
    }

    /**
     * Writes a value. Note the convention: a signal is read from `X` but written to
     * `X_SET` - writing to the read id is rejected by the platform.
     */
    fun writeInt(group: String, field: String, value: Int): Boolean {
        val id = featureId(group, field) ?: return false
        val target = manager ?: return false
        val result = runCatching { setInt?.invoke(target, DEVICE_ANY, id, value) as? Int }
            .onFailure { Log.w(TAG, "write $group.$field failed: ${it.cause ?: it}") }
            .getOrNull()
        if (result != COMMAND_SUCCESS) {
            Log.w(TAG, "write $group.$field=$value returned $result")
        }
        return result == COMMAND_SUCCESS
    }
}
