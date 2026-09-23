package com.example.esmerui.car

import android.os.IBinder
import android.util.Log
import com.example.esmerui.launcher.window.HiddenApi
import java.lang.reflect.Method

/**
 * The head unit's link to the instrument cluster, `android.os.AutoContainerManager`.
 *
 * On this "1for2" unit the cluster and the head-up display are drawn by a second
 * OS on the same chip, and the head unit talks to it through this manager: a
 * `sendInfo(type, code, text)` for control messages - type 5 switches the
 * container's navigation mode, type 1000 carries the cluster-debug control codes -
 * and `sendInfo2(type, bytes)` for content frames, type 4 being the navigation
 * frame the OEM bridge builds. Reached by reflection, since the class ships in a
 * BYD framework jar rather than the SDK.
 */
object ClusterContainer {

    private const val TAG = "ClusterContainer"

    /**
     * The `IAutoContainer` binder proxy. On this ROM the manager class is only a
     * thin wrapper constructed around it, with no accessor of its own, so the
     * interface is called directly: `ServiceManager.getService("AutoContainer")`,
     * then `IAutoContainer.Stub.asInterface`.
     */
    private var service: Any? = null
    private var sendInfo: Method? = null
    private var sendInfo2: Method? = null

    private fun init(): Boolean {
        if (service != null) return true
        HiddenApi.exempted

        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "AutoContainer") as? IBinder
                ?: error("no AutoContainer service")
            val proxy = Class.forName("android.os.IAutoContainer\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: error("asInterface returned null")
            val iface = Class.forName("android.os.IAutoContainer")
            sendInfo = iface.getMethod(
                "sendInfo",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            sendInfo2 = runCatching {
                iface.getMethod("sendInfo2", Int::class.javaPrimitiveType, ByteArray::class.java)
            }.getOrNull()
            service = proxy
            Log.i(TAG, "connected to AutoContainer")
            true
        }.onFailure {
            Log.w(TAG, "AutoContainer unavailable: ${it.cause ?: it}")
        }.getOrDefault(false)
    }

    /** The container's own result code, or null when the call could not be made. */
    fun sendInfo(type: Int, code: Int, text: String = ""): Int? {
        if (!init()) return null
        return runCatching { sendInfo?.invoke(service, type, code, text) as? Int }
            .onFailure { Log.w(TAG, "sendInfo($type, $code) failed: ${it.cause ?: it}") }
            .getOrNull()
    }

    fun sendInfo2(type: Int, payload: ByteArray): Int? {
        if (!init()) return null
        val method = sendInfo2 ?: return null
        return runCatching { method.invoke(service, type, payload) as? Int }
            .onFailure { Log.w(TAG, "sendInfo2($type) failed: ${it.cause ?: it}") }
            .getOrNull()
    }
}
