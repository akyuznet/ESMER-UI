package com.example.esmerui.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * The bus diagnostics, both driven over adb and idle until asked.
 *
 * A watch logs a few signals at a steady rate, for pinning down an encoding by
 * doing something known in the car and watching what moves - the indicator bits
 * and the gear letters were both settled this way:
 *
 *     adb shell am broadcast -a com.example.esmerui.WATCH \
 *         --es signals Gearbox.GEARBOX_AUTO_MODE_TYPE,Speed.SPEED_AUTO_SPEED \
 *         --ei hz 5 --ei seconds 60
 *     adb logcat -s BusWatch
 *
 * A signal is logged whenever its value changes, and once a second regardless, so a
 * quiet run reads differently from a dead one. Sending the broadcast again replaces
 * the current watch; sending it with no signals stops it.
 *
 * A sweep reads every signal the ROM declares, one domain per poll, into
 * `files/bus-scan.txt` under the app's external directory - about fifty seconds:
 *
 *     adb shell am broadcast -a com.example.esmerui.SWEEP -p com.example.esmerui
 *
 * The watch runs on its own thread: every read is a binder round trip and there
 * can be a hundred of them a second, which is nothing the bar's poll should wait
 * behind. The sweep rides the poll's thread, since that is where the file is
 * appended one group at a time.
 */
object BusWatch : BroadcastReceiver() {

    private const val TAG = "BusWatch"
    private const val ACTION = "com.example.esmerui.WATCH"
    private const val ACTION_SWEEP = "com.example.esmerui.SWEEP"

    /**
     * Presses a bar action from adb, for checking a control against the car's own
     * feedback without a finger on the screen:
     *
     *     adb shell am broadcast -a com.example.esmerui.PERFORM -p com.example.esmerui \
     *         --es action HUD_THEME
     *
     * Debug builds only. The service itself is not exported, so this is the one
     * way in from outside - and a release build has no business offering it.
     */
    private const val ACTION_PERFORM = "com.example.esmerui.PERFORM"

    /**
     * Two more debug-only hooks, for working out what the cluster and the head-up
     * display need before they will draw navigation data:
     *
     *     adb shell am broadcast -a com.example.esmerui.CLUSTER -p com.example.esmerui \
     *         --ei type 5 --ei code 0 [--es text ""]
     *     adb shell am broadcast -a com.example.esmerui.SET -p com.example.esmerui \
     *         --es group Instrument --es field INSTRUMENT_NAVI_TYPE_SET --ei value 8
     *
     * The first is a control message to the cluster container, the second a raw
     * write to any bus register. Both log their result under this tag.
     */
    private const val ACTION_CLUSTER = "com.example.esmerui.CLUSTER"
    private const val ACTION_SET = "com.example.esmerui.SET"

    /**
     * Presents an arrow on the cluster's projection display (see [ClusterCanvas]):
     *
     *     adb shell am broadcast -a com.example.esmerui.PRESENT -p com.example.esmerui \
     *         [--ei display 1] [--ei seconds 60] [--es "text" "300 m|ESMER-TEST"] [--ez hide true]
     */
    private const val ACTION_PRESENT = "com.example.esmerui.PRESENT"

    /**
     * Runs the accessibility rebind - our entry taken out of the enabled list, held
     * out until the platform has seen it gone, and put back - which is what clears
     * a binding the platform has left half-open:
     *
     *     adb shell am broadcast -a com.example.esmerui.REPAIR -p com.example.esmerui
     *
     * Debug builds only. The path it exercises runs by itself when the binding is
     * actually stuck; this is the only way to watch it work on a healthy unit,
     * where it shows as the service disconnecting and connecting again a few
     * seconds later (tag NavBarService, "debug rebind finished; connected=true").
     */
    private const val ACTION_REPAIR = "com.example.esmerui.REPAIR"

    /**
     * Feeds the bar service a car power level, as if the bus had reported it, and
     * keeps the real reading out for `hold` seconds (default 5):
     *
     *     adb shell am broadcast -a com.example.esmerui.POWER -p com.example.esmerui \
     *         --ei level 0 [--ei hold 5]
     *
     * Debug builds only. Level 0 stands the accessibility service down the way an
     * ignition-off does; the real level coming back after the hold stands it up.
     */
    private const val ACTION_POWER = "com.example.esmerui.POWER"

    /**
     * Drives the cluster projection from adb, debug builds only:
     *
     *     adb shell am broadcast -a com.example.esmerui.PROJECT -p com.example.esmerui \n     *         --es component com.waze/.MainActivity
     *     adb shell am broadcast -a com.example.esmerui.PROJECT -p com.example.esmerui --ez stop true
     *     adb shell am broadcast -a com.example.esmerui.PROJECT -p com.example.esmerui --ez connect true
     */
    private const val ACTION_PROJECT = "com.example.esmerui.PROJECT"

    private const val MAX_HZ = 20
    private const val MAX_SECONDS = 600
    private const val HEARTBEAT_MS = 1_000L

    private val handler by lazy { Handler(HandlerThread("bus-watch").apply { start() }.looper) }

    fun register(context: Context) {
        context.registerReceiver(this, IntentFilter().apply {
            addAction(ACTION)
            addAction(ACTION_SWEEP)
            addAction(ACTION_PERFORM)
            addAction(ACTION_CLUSTER)
            addAction(ACTION_SET)
            addAction(ACTION_PRESENT)
            addAction(ACTION_REPAIR)
            addAction(ACTION_POWER)
            addAction(ACTION_PROJECT)
        })
    }

    private fun debuggable(context: Context): Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PROJECT) {
            if (!debuggable(context)) return
            val projection = com.example.esmerui.cluster.ClusterProjection
            when {
                intent.getBooleanExtra("stop", false) -> projection.stop(context)
                intent.getBooleanExtra("connect", false) -> projection.connect(context)
                else -> {
                    val component = intent.getStringExtra("component")
                        ?.let { android.content.ComponentName.unflattenFromString(it) }
                    if (component == null) Log.w(TAG, "project: needs component, stop or connect")
                    else projection.start(context, component)
                }
            }
            return
        }

        if (intent.action == ACTION_POWER) {
            if (!debuggable(context)) return
            val level = intent.getIntExtra("level", -1)
            val hold = intent.getIntExtra("hold", 5)
            Log.i(TAG, "power: simulating level $level for $hold s")
            com.example.esmerui.navbar.NavBarService.simulatePower(context, level, hold)
            return
        }

        if (intent.action == ACTION_REPAIR) {
            if (!debuggable(context)) return
            Log.i(TAG, "repair: forcing an accessibility rebind")
            com.example.esmerui.navbar.NavBarService.repairAccessibility(context)
            return
        }

        if (intent.action == ACTION_PRESENT) {
            if (!debuggable(context)) return
            if (intent.getBooleanExtra("hide", false)) {
                ClusterCanvas.hide()
                return
            }
            ClusterCanvas.show(
                context,
                displayId = intent.getIntExtra("display", 1),
                text = intent.getStringExtra("text") ?: "300 m|ESMER-TEST",
                seconds = intent.getIntExtra("seconds", 60).coerceIn(1, 600),
            )
            return
        }

        if (intent.action == ACTION_CLUSTER) {
            if (!debuggable(context)) return
            val type = intent.getIntExtra("type", -1)
            val code = intent.getIntExtra("code", 0)
            val text = intent.getStringExtra("text").orEmpty()
            handler.post {
                val result = ClusterContainer.sendInfo(type, code, text)
                Log.i(TAG, "cluster sendInfo($type, $code, \"$text\") -> $result")
            }
            return
        }

        if (intent.action == ACTION_SET) {
            if (!debuggable(context)) return
            val group = intent.getStringExtra("group").orEmpty()
            val field = intent.getStringExtra("field").orEmpty()
            val rawId = intent.getIntExtra("id", 0)
            val value = intent.getIntExtra("value", Int.MIN_VALUE)
            // A dry run only says what a name resolves to on this ROM, and what the
            // register reads right now, without writing anything.
            val dry = intent.getBooleanExtra("dry", false)
            if (rawId == 0 && (group.isEmpty() || field.isEmpty())) {
                Log.w(TAG, "set: needs group and field, or id")
                return
            }
            if (!dry && value == Int.MIN_VALUE) {
                Log.w(TAG, "set: needs value")
                return
            }
            handler.post {
                val id = if (rawId != 0) rawId else BydFeatures.featureId(group, field)
                val name = if (rawId != 0) "id" else "$group.$field"
                if (id == null) {
                    Log.w(TAG, "set $name: no such feature on this ROM")
                    return@post
                }
                val hex = "0x%08X".format(id)
                if (dry) {
                    Log.i(TAG, "set $name resolves to $hex, reads ${BydFeatures.readId(id)}")
                    return@post
                }
                val ok = if (rawId != 0) BydFeatures.writeId(id, value) else BydFeatures.writeInt(group, field, value)
                Log.i(TAG, "set $name ($hex)=$value -> ${if (ok) "accepted" else "refused"}")
            }
            return
        }

        if (intent.action == ACTION_PERFORM) {
            if (!debuggable(context)) return
            val action = com.example.esmerui.launcher.NavAction.from(intent.getStringExtra("action"))
            if (action == null) {
                Log.w(TAG, "perform: unknown action ${intent.getStringExtra("action")}")
                return
            }
            Log.i(TAG, "perform $action")
            com.example.esmerui.navbar.NavBarService.perform(context, action)
            return
        }

        if (intent.action == ACTION_SWEEP) {
            val file = java.io.File(context.getExternalFilesDir(null), "bus-scan.txt")
            CarStatus.requestSweep(file)
            Log.i(TAG, "sweep requested: $file")
            return
        }

        val signals = intent.getStringExtra("signals").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.contains('.') }
        val hz = intent.getIntExtra("hz", 5).coerceIn(1, MAX_HZ)
        val seconds = intent.getIntExtra("seconds", 30).coerceIn(1, MAX_SECONDS)

        handler.removeCallbacksAndMessages(null)
        if (signals.isEmpty()) {
            Log.i(TAG, "stopped")
            return
        }
        Log.i(TAG, "watching ${signals.joinToString()} at $hz Hz for $seconds s")

        val last = HashMap<String, Int?>()
        val until = SystemClock.elapsedRealtime() + seconds * 1_000L
        var lastBeat = 0L

        handler.post(object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val beat = now - lastBeat >= HEARTBEAT_MS
                if (beat) lastBeat = now

                signals.forEach { signal ->
                    val (group, field) = signal.split('.', limit = 2)
                    val value = BydFeatures.readInt(group, field)
                    // Both types every time: a signal reading "not fitted" as an int
                    // is often a double, and that is not visible from the name.
                    val asDouble = BydFeatures.readDouble(group, field)
                    if (beat || !last.containsKey(signal) || last[signal] != value) {
                        last[signal] = value
                        Log.i(TAG, "$signal = $value  (double $asDouble)")
                    }
                }

                if (now < until) handler.postDelayed(this, 1_000L / hz) else Log.i(TAG, "done")
            }
        })
    }
}
