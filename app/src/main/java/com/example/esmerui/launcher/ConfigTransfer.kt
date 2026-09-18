package com.example.esmerui.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Exports and imports the launcher's settings as a JSON file.
 *
 * Every preference is copied verbatim rather than through a hand-written schema, so
 * a setting added later is carried across without touching this file. Values keep a
 * type tag because SharedPreferences is typed and a bare JSON number cannot say
 * whether it was stored as an Int or a Long.
 *
 * The file lives in the app's external files directory: reachable over adb or a file
 * manager, but needing no storage permission.
 */
object ConfigTransfer {

    private const val TAG = "ConfigTransfer"
    private const val FILE_NAME = "esmer-launcher-config.json"
    private const val VERSION = 1

    sealed interface Result {
        data class Success(val path: String, val entries: Int) : Result
        data class Failure(val reason: String) : Result
    }

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null), FILE_NAME)

    /** Where the file is, in a form worth showing in the UI. */
    fun displayPath(context: Context): String = file(context).absolutePath

    fun exportConfig(context: Context): Result = runCatching {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            val tagged = when (value) {
                is Boolean -> JSONObject().put("type", "bool").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is String -> JSONObject().put("type", "string").put("value", value)
                // Sets and anything exotic are skipped rather than silently mangled.
                else -> null
            }
            if (tagged != null) values.put(key, tagged)
        }

        val document = JSONObject()
            .put("version", VERSION)
            .put("exported", System.currentTimeMillis())
            .put("values", values)

        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(document.toString(2))

        Result.Success(target.absolutePath, values.length())
    }.getOrElse {
        Log.w(TAG, "export failed", it)
        Result.Failure(it.message ?: "Could not write the file")
    }

    fun importConfig(context: Context): Result = runCatching {
        val source = file(context)
        if (!source.exists()) {
            return Result.Failure("No config file at ${source.absolutePath}")
        }

        val document = JSONObject(source.readText())
        val values = document.optJSONObject("values")
            ?: return Result.Failure("File has no \"values\" section")

        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        // Replace wholesale: a merge would leave settings from the old config that
        // the imported one deliberately dropped.
        editor.clear()

        var written = 0
        values.keys().forEach { key ->
            val entry = values.optJSONObject(key) ?: return@forEach
            when (entry.optString("type")) {
                "bool" -> editor.putBoolean(key, entry.optBoolean("value"))
                "int" -> editor.putInt(key, entry.optInt("value"))
                "long" -> editor.putLong(key, entry.optLong("value"))
                "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                "string" -> editor.putString(key, entry.optString("value"))
                else -> return@forEach
            }
            written++
        }
        // Commit, not apply: the settings are re-read immediately afterwards.
        editor.commit()

        LauncherSettings.get(context).reload()
        Result.Success(source.absolutePath, written)
    }.getOrElse {
        Log.w(TAG, "import failed", it)
        Result.Failure(it.message ?: "Could not read the file")
    }

    private const val PREFS_NAME = "launcher_settings"
}
