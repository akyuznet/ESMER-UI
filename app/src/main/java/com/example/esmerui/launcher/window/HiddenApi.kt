package com.example.esmerui.launcher.window

import android.util.Log
import java.lang.reflect.Method

/**
 * Lifts the hidden-API blacklist for this process.
 *
 * Windowing another app needs a couple of `@hide` framework methods
 * (`ActivityOptions.setLaunchWindowingMode`, `MotionEvent.setDisplayId`) that are
 * blocked from reflection on API 28+. The double-reflection trick below reaches
 * `VMRuntime.setHiddenApiExemptions` through `Class.forName`/`getDeclaredMethod`
 * handles, which are themselves not on the blacklist.
 *
 * Best effort: if it fails, callers still work through whatever public API exists.
 */
object HiddenApi {

    private const val TAG = "HiddenApi"

    val exempted: Boolean by lazy { tryExempt() }

    private fun tryExempt(): Boolean = runCatching {
        val forName = Class::class.java
            .getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java
            .getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)

        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod
            .invoke(vmRuntime, "getRuntime", null) as Method
        val setExemptions = getDeclaredMethod
            .invoke(vmRuntime, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as Method

        // "L" prefixes every JNI class signature, so this exempts everything.
        setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        true
    }.onFailure {
        Log.w(TAG, "Could not lift hidden-API restrictions: ${it.message}")
    }.getOrDefault(false)
}
