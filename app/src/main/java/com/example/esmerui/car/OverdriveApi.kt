package com.example.esmerui.car

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for OverDrive's local vehicle REST API.
 *
 * BYD's in-framework SDK is gated behind signature-level BYDAUTO_*_GET/_SET
 * permissions that no unsigned app can hold. OverDrive already holds a working
 * control path and exposes it over plain HTTP on the loopback interface, so this
 * borrows that path instead of fighting the permission model:
 *
 *     GET  /api/vehicle/state     -> full live vehicle snapshot
 *     POST /api/vehicle/climate   -> {"action":"set_temp","zone":0,"temp":22}
 *     POST /api/vehicle/seat      -> {"action":"heating","position":1,"level":2}
 *
 * There is no authentication, so nothing needs to be configured. The trade-off is
 * a hard dependency on OverDrive being installed and running - [isReachable]
 * exists so the UI can hide these controls rather than offer dead buttons.
 */
object OverdriveApi {

    private const val TAG = "OverdriveApi"
    private const val BASE = "http://127.0.0.1:8080/api/vehicle"

    /** Short: these calls sit behind a bar button, so a slow car must not hang it. */
    private const val TIMEOUT_MS = 4000

    /** Full vehicle snapshot, or null when OverDrive is not answering. */
    fun state(): JSONObject? = request("GET", "/state", null)

    fun climate(action: String, extras: Map<String, Any> = emptyMap()): Boolean =
        succeeded(request("POST", "/climate", body(action, extras)))

    fun seat(action: String, position: Int, level: Int): Boolean =
        succeeded(
            request("POST", "/seat", body(action, mapOf("position" to position, "level" to level)))
        )

    /** Cheap liveness check used to decide whether to show car controls at all. */
    fun isReachable(): Boolean = state() != null

    private fun body(action: String, extras: Map<String, Any>): String =
        JSONObject().apply {
            put("action", action)
            extras.forEach { (key, value) -> put(key, value) }
        }.toString()

    /**
     * The API answers with `{"success":true,"commandSuccess":true,...}`. Both matter:
     * `success` only says the request was understood, `commandSuccess` says the car
     * accepted it - treating the first as the answer would report a refused command
     * as if it had worked.
     */
    private fun succeeded(response: JSONObject?): Boolean {
        response ?: return false
        val accepted = response.optBoolean("success", false) &&
            response.optBoolean("commandSuccess", true)
        if (!accepted) {
            Log.w(TAG, "command refused: $response")
        }
        return accepted
    }

    private fun request(method: String, path: String, body: String?): JSONObject? = runCatching {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (text.isBlank()) null else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        Log.w(TAG, "$method $path failed: ${it.message}")
    }.getOrNull()
}
