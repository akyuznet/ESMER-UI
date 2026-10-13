package com.example.esmerui.home

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esmerui.car.CarInfo
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.NavAction
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Readouts from a Home Assistant server.
 *
 * The user gives the server's URL and a long-lived access token, picks an
 * entity for each of the [SLOTS] "Home" readouts, and those readouts then show
 * the entity's state like any other: on a bar, in the home grid, on the
 * cluster. The states are fetched over the REST API (`/api/states/<entity>`)
 * on their own thread at the chosen interval, but only while some Home readout
 * is actually on screen, the same rule as for the car's signals.
 *
 * Nothing is written to the server. The token is kept in the launcher's
 * settings file on the unit, like everything else there.
 */
object HomeAssistant {

    private const val TAG = "HomeAssistant"

    const val SLOTS = 6
    const val DEFAULT_INTERVAL_S = 30

    /** Poll intervals offered, in seconds. */
    val intervals = listOf(10, 30, 60, 300)

    private const val TIMEOUT_MS = 8_000

    /** One entity as the server reports it. */
    data class Entity(val id: String, val name: String, val state: String, val unit: String?) {

        /** The state as a readout prints it: a number with its unit, On, Off, or a dash. */
        val display: String
            get() {
                when (state) {
                    "on" -> return "On"
                    "off" -> return "Off"
                    "unavailable", "unknown", "" -> return CarInfo.NO_VALUE
                }
                val number = state.toDoubleOrNull()
                val text = if (number != null && state.contains('.')) {
                    // Sensors report to many places; one is enough to read at a glance.
                    String.format(java.util.Locale.US, "%.1f", number).removeSuffix(".0")
                } else {
                    state
                }
                val suffix = unit.orEmpty()
                return when {
                    suffix.isEmpty() -> text
                    suffix == "%" || suffix.startsWith("°") -> text + suffix
                    else -> "$text $suffix"
                }
            }
    }

    var status by mutableStateOf("Not connected.")
        private set
    var busy by mutableStateOf(false)
        private set

    /** Every entity the server listed, for the picker; empty until loaded. */
    var catalog by mutableStateOf<List<Entity>>(emptyList())
        private set

    /** When the readouts last changed, epoch ms; 0 until the first fetch. */
    var lastUpdated by mutableStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private val values = ConcurrentHashMap<Int, Entity>()
    private val worker = Handler(HandlerThread("home-assistant").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** The slots on screen right now; the poll runs only while this is not empty. */
    @Volatile private var polling: Set<Int> = emptySet()

    // ── Settings ─────────────────────────────────────────────────────────────

    fun configured(settings: LauncherSettings): Boolean =
        !settings.homeAssistantUrl.isNullOrBlank() && !settings.homeAssistantToken.isNullOrBlank()

    /** `slot:entity_id` joined with `;`. */
    private fun parseSlots(saved: String?): Map<Int, String> =
        saved.orEmpty().split(';').mapNotNull { entry ->
            val slot = entry.substringBefore(':').trim().toIntOrNull() ?: return@mapNotNull null
            val id = entry.substringAfter(':', "").trim()
            if (slot in 1..SLOTS && id.isNotEmpty()) slot to id else null
        }.toMap()

    fun slotEntity(settings: LauncherSettings, slot: Int): String? =
        parseSlots(settings.homeAssistantSlots)[slot]

    /** Points a slot at an entity, or clears it with null. */
    fun setSlot(context: Context, slot: Int, entityId: String?) {
        val settings = LauncherSettings.get(context)
        val slots = parseSlots(settings.homeAssistantSlots).toMutableMap()
        if (entityId.isNullOrBlank()) slots.remove(slot) else slots[slot] = entityId.trim()
        settings.homeAssistantSlots = slots.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}:${it.value}" }
            .ifEmpty { null }
        values.remove(slot)
        // A fresh pick is worth seeing straight away.
        catalog.firstOrNull { it.id == entityId }?.let { values[slot] = it }
        refreshNow(context)
    }

    // ── Readout values ───────────────────────────────────────────────────────

    fun valueFor(action: NavAction): String {
        val slot = action.homeSlot ?: return CarInfo.NO_VALUE
        return values[slot]?.display ?: CarInfo.NO_VALUE
    }

    /** The entity's name, kept short enough for a bar cell. */
    fun captionFor(action: NavAction): String? {
        val slot = action.homeSlot ?: return null
        val name = values[slot]?.name
            ?: app?.let { slotEntity(LauncherSettings.get(it), slot) }
                ?.substringAfter('.')?.replace('_', ' ')
            ?: return null
        return if (name.length > 16) name.take(15) + "…" else name
    }

    fun isOn(action: NavAction): Boolean {
        val slot = action.homeSlot ?: return false
        return values[slot]?.state == "on"
    }

    /** The entity shown in a slot right now, for the settings page. */
    fun current(slot: Int): Entity? = values[slot]

    // ── Polling ──────────────────────────────────────────────────────────────

    /** Tells the poll which Home readouts are on screen; it runs only for those. */
    fun wanted(context: Context, shown: Set<NavAction>) {
        app = context.applicationContext
        val slots = shown.mapNotNull { it.homeSlot }.toSet()
        if (slots == polling) return
        val was = polling
        polling = slots
        if (slots.isNotEmpty() && was.isEmpty()) {
            worker.removeCallbacks(poll)
            worker.post(poll)
        }
    }

    /** One fetch of every configured slot, now. */
    fun refreshNow(context: Context) {
        app = context.applicationContext
        worker.removeCallbacks(poll)
        worker.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            val context = app ?: return
            val settings = LauncherSettings.get(context)
            var error: String? = null
            if (configured(settings)) {
                parseSlots(settings.homeAssistantSlots).forEach { (slot, id) ->
                    runCatching { values[slot] = entity(JSONObject(get(settings, "/api/states/$id"))) }
                        .onFailure { error = "$id: ${describe(it)}" }
                }
            }
            val failed = error
            main.post {
                lastUpdated = System.currentTimeMillis()
                lastError = failed
            }
            if (failed != null) Log.w(TAG, "poll: $failed")
            if (polling.isNotEmpty()) {
                worker.postDelayed(this, settings.homeAssistantInterval.coerceAtLeast(5) * 1_000L)
            }
        }
    }

    // ── Server calls ─────────────────────────────────────────────────────────

    /** Checks the URL and token against `/api/config` and says what answered. */
    fun test(context: Context) {
        val settings = LauncherSettings.get(context.applicationContext)
        app = context.applicationContext
        busy = true
        status = "Connecting…"
        worker.post {
            val result = runCatching {
                val config = JSONObject(get(settings, "/api/config"))
                val name = config.optString("location_name").ifEmpty { "Home Assistant" }
                val version = config.optString("version")
                "Connected to $name" + if (version.isEmpty()) "." else " (Home Assistant $version)."
            }
            main.post {
                busy = false
                status = result.getOrElse { "Could not connect: ${describe(it)}" }
            }
        }
    }

    /** Fetches every entity on the server for the picker. */
    fun loadCatalog(context: Context) {
        val settings = LauncherSettings.get(context.applicationContext)
        app = context.applicationContext
        busy = true
        status = "Loading entities…"
        worker.post {
            val result = runCatching {
                val array = JSONArray(get(settings, "/api/states"))
                (0 until array.length()).map { entity(array.getJSONObject(it)) }
                    .sortedBy { it.name.lowercase() }
            }
            main.post {
                busy = false
                result.onSuccess {
                    catalog = it
                    status = "${it.size} entities loaded."
                }.onFailure {
                    status = "Could not load entities: ${describe(it)}"
                }
            }
        }
    }

    private fun entity(json: JSONObject): Entity {
        val id = json.getString("entity_id")
        val attributes = json.optJSONObject("attributes")
        return Entity(
            id = id,
            name = attributes?.optString("friendly_name")?.takeIf { it.isNotEmpty() } ?: id,
            state = json.optString("state"),
            unit = attributes?.optString("unit_of_measurement")?.takeIf { it.isNotEmpty() },
        )
    }

    /** `http://host:8123` from whatever was typed: scheme added, trailing slash dropped. */
    fun baseUrl(typed: String?): String? {
        val trimmed = typed?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null
        return if (trimmed.contains("://")) trimmed else "http://$trimmed"
    }

    private fun get(settings: LauncherSettings, path: String): String {
        val base = baseUrl(settings.homeAssistantUrl) ?: error("no URL set")
        val token = settings.homeAssistantToken?.trim().orEmpty()
        if (token.isEmpty()) error("no token set")
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error(
                    when (code) {
                        401 -> "the token was refused"
                        404 -> "nothing there; is this the Home Assistant URL?"
                        else -> "HTTP $code"
                    },
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "host not found"
        is ConnectException -> "connection refused"
        is SocketTimeoutException -> "timed out"
        else -> error.message ?: error.javaClass.simpleName
    }
}
