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

    private const val MAX_HZ = 20
    private const val MAX_SECONDS = 600
    private const val HEARTBEAT_MS = 1_000L

    private val handler by lazy { Handler(HandlerThread("bus-watch").apply { start() }.looper) }

    fun register(context: Context) {
        context.registerReceiver(this, IntentFilter().apply {
            addAction(ACTION)
            addAction(ACTION_SWEEP)
        })
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(this) }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onReceive(context: Context, intent: Intent) {
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
