package com.example.esmerui.cluster

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.esmerui.launcher.window.HiddenApi

/**
 * Casts an app onto the instrument cluster, and mirrors it back onto the head unit.
 *
 * On this "1for2" unit the cluster is a second OS on the same chip, and the
 * container service publishes its projected background as display 1, a public
 * 1920x720 presentation display. Anything placed on that display reaches the
 * dial once the cluster has been switched into projection mode, which is a
 * control message the container only takes from an allowlisted caller. The
 * steps, each a shell command over [AdbLink] unless noted:
 *
 *  1. `service call AutoContainer 2 i32 1000 i32 16` - projection on. The cluster
 *     hands its surface over and shows display 1 instead of its own dials.
 *  2. Stop the app, then `am start --display 1` it fresh, so an app already open
 *     on the main screen does not just come forward there.
 *  3. [ClusterDaemon] mirrors display 1's layer stack into the preview on the
 *     settings screen and injects the preview's touches onto display 1.
 *  4. Stop: mirror off, app stopped, `18` (projection off) then `0` (cluster
 *     resumes its own stream).
 *
 * The codes and their order are DashCast's, confirmed on DiLink 3; see its
 * README for the ones this does not use (half-screen, the 12.3" profile).
 *
 * All of the work runs on one background thread; state for the UI is published
 * on the main thread.
 */
object ClusterProjection {

    private const val TAG = "ClusterProjection"

    const val CLUSTER_DISPLAY_ID = 1
    const val CLUSTER_WIDTH = 1920
    const val CLUSTER_HEIGHT = 720

    private const val CONTAINER_TYPE = 1000
    private const val CODE_PROJECTION_ON = 16
    private const val CODE_PROJECTION_OFF = 18
    private const val CODE_RESUME_CLUSTER = 0
    private const val CODE_CREATE_DISPLAY = 35

    /** The container needs a moment between codes; DashCast measured about three seconds. */
    private const val CODE_SETTLE_MS = 3_000L
    private const val DISPLAY_WAIT_MS = 8_000L
    private const val TASK_WAIT_MS = 6_000L

    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN and _FREEFORM. */
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    /**
     * The cluster display's windowing mode before projection changed it. The
     * ROM keeps that display in freeform (its own split-screen window lives
     * there, caption bar and all), so it is put back on stop.
     */
    private var displayModeBefore: Int? = null
    private const val DAEMON_WAIT_MS = 8_000L

    enum class Phase { IDLE, BUSY, PROJECTING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var status by mutableStateOf("Not projecting.")
        private set
    var projected: ComponentName? by mutableStateOf(null)
        private set
    var linkReady by mutableStateOf(false)
        private set
    /** Whether the preview is drawing the cluster right now. */
    var mirrorLive by mutableStateOf(false)
        private set

    private val worker = Handler(HandlerThread("cluster-projection").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var daemon: IBinder? = null
    private var receiver: BroadcastReceiver? = null

    // The preview surface, kept so a mirror can be (re)started whenever the
    // daemon or the projection arrives after the view did.
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    // ── Public entry points ──────────────────────────────────────────────────

    /** Opens the head unit link and starts the daemon without projecting anything. */
    fun connect(context: Context) {
        val app = context.applicationContext
        worker.post {
            if (phase == Phase.BUSY) return@post
            publish(Phase.BUSY, "Connecting to the head unit…")
            runCatching {
                ensureDaemon(app)
                publish(if (projected != null) Phase.PROJECTING else Phase.IDLE, "Head unit link ready.")
            }.onFailure { fail(it) }
        }
    }

    fun start(context: Context, component: ComponentName) {
        val app = context.applicationContext
        worker.post {
            if (phase == Phase.BUSY) return@post
            publish(Phase.BUSY, "Connecting to the head unit…")
            runCatching {
                ensureDaemon(app)
                publish(Phase.BUSY, "Switching the cluster to projection…")
                sendCode(app, CODE_PROJECTION_ON)
                if (!waitForDisplay(app)) {
                    // A cluster that has never projected since boot has no display
                    // yet; this is the code that makes the container create one.
                    sendCode(app, CODE_CREATE_DISPLAY)
                    if (!waitForDisplay(app)) error("The cluster did not open its projection display.")
                }
                publish(Phase.BUSY, "Starting ${component.packageName} on the cluster…")
                claimDisplay()
                shell(app, "am force-stop ${ClusterDaemon.quote(component.packageName)}")
                val launched = shell(
                    app,
                    "am start --display $CLUSTER_DISPLAY_ID -n " +
                        ClusterDaemon.quote(component.flattenToString()),
                )
                if (launched.contains("Error", ignoreCase = true)) error(launched.trim())
                fillDisplay(app, component.packageName)
                layerStackCached = layerStack(app)
                main.post { projected = component }
                publish(Phase.PROJECTING, "Projecting to the cluster.")
                refreshMirror()
            }.onFailure {
                fail(it)
                runCatching { sendCode(app, CODE_PROJECTION_OFF); SystemClock.sleep(500); sendCode(app, CODE_RESUME_CLUSTER) }
            }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        worker.post {
            if (phase == Phase.BUSY) return@post
            publish(Phase.BUSY, "Handing the cluster back…")
            stopMirror()
            val current = projected
            runCatching {
                if (current != null) shell(app, "am force-stop ${ClusterDaemon.quote(current.packageName)}")
                releaseDisplay()
                sendCode(app, CODE_PROJECTION_OFF)
                SystemClock.sleep(CODE_SETTLE_MS / 3)
                sendCode(app, CODE_RESUME_CLUSTER)
                main.post { projected = null }
                publish(Phase.IDLE, "Not projecting.")
            }.onFailure { fail(it) }
        }
    }

    /** The preview is up (or resized): mirror into it when there is something to show. */
    fun attachPreview(surface: Surface, width: Int, height: Int) {
        worker.post {
            previewSurface?.takeIf { it !== surface }?.release()
            previewSurface = surface
            previewWidth = width
            previewHeight = height
            refreshMirror()
        }
    }

    fun detachPreview() {
        worker.post {
            stopMirror()
            previewSurface?.release()
            previewSurface = null
        }
    }

    /**
     * A touch on the preview, re-targeted at the cluster. The preview letterboxes
     * the 1920x720 image exactly as the daemon does, so the same arithmetic maps
     * a finger back onto it.
     */
    fun forwardTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val binder = daemon ?: return false
        if (phase != Phase.PROJECTING || viewWidth <= 0 || viewHeight <= 0) return false
        val scale = minOf(viewWidth.toFloat() / CLUSTER_WIDTH, viewHeight.toFloat() / CLUSTER_HEIGHT)
        val offX = (viewWidth - CLUSTER_WIDTH * scale) / 2f
        val offY = (viewHeight - CLUSTER_HEIGHT * scale) / 2f
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply {
            postTranslate(-offX, -offY)
            postScale(1f / scale, 1f / scale)
        })
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(CLUSTER_DISPLAY_ID)
            copy.writeToParcel(data, 0)
            binder.transact(ClusterDaemon.TXN_INJECT_MOTION, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "touch forwarding failed: $e")
            false
        } finally {
            data.recycle()
            copy.recycle()
        }
    }

    /** A key press (Back, say) delivered to whatever is on the cluster. */
    fun sendKey(keyCode: Int) {
        val binder = daemon ?: return
        val now = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(now, now, action, keyCode, 0).apply {
                source = InputDevice.SOURCE_KEYBOARD
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
                data.writeInt(CLUSTER_DISPLAY_ID)
                event.writeToParcel(data, 0)
                binder.transact(ClusterDaemon.TXN_INJECT_KEY, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: Exception) {
                Log.w(TAG, "key forwarding failed: $e")
            } finally {
                data.recycle()
            }
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private fun shell(context: Context, command: String): String {
        val out = AdbLink.shell(context, command)
        Log.i(TAG, "$ $command\n${out.trim()}")
        return out
    }

    private fun sendCode(context: Context, code: Int) {
        val out = shell(context, "service call AutoContainer 2 i32 $CONTAINER_TYPE i32 $code s16 ''")
        if (!out.contains("Parcel")) error("The cluster container did not answer code $code: ${out.trim()}")
    }

    private fun clusterDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java).getDisplay(CLUSTER_DISPLAY_ID)
            ?.takeIf { it.isValid }

    private fun waitForDisplay(context: Context): Boolean {
        val until = SystemClock.elapsedRealtime() + DISPLAY_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (clusterDisplay(context) != null) return true
            SystemClock.sleep(250)
        }
        return false
    }

    /**
     * Puts the cluster display into fullscreen windowing before anything launches
     * there. The ROM's default for it is freeform, which gives every task a
     * caption bar with minimise and close buttons - fine for the head unit's own
     * floating window, wrong on a dial. A task launched after this switch comes
     * up fullscreen with no caption at all.
     */
    private fun claimDisplay() {
        val before = displayMode() ?: return
        if (displayModeBefore == null) displayModeBefore = before
        if (before != WINDOWING_MODE_FULLSCREEN) setDisplayMode(WINDOWING_MODE_FULLSCREEN)
    }

    /** Hands the display's windowing mode back; freeform if nothing better is known. */
    private fun releaseDisplay() {
        val restore = displayModeBefore ?: WINDOWING_MODE_FREEFORM
        if (displayMode() != restore) setDisplayMode(restore)
        displayModeBefore = null
    }

    private fun displayMode(): Int? {
        val binder = daemon ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(CLUSTER_DISPLAY_ID)
            binder.transact(ClusterDaemon.TXN_GET_DISPLAY_MODE, data, reply, 0)
            reply.readException()
            reply.readInt().takeIf { it >= 0 }
        } catch (e: Exception) {
            Log.w(TAG, "display mode read failed: $e")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setDisplayMode(mode: Int): Boolean {
        val binder = daemon ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(CLUSTER_DISPLAY_ID)
            data.writeInt(mode)
            binder.transact(ClusterDaemon.TXN_SET_DISPLAY_MODE, data, reply, 0)
            reply.readException()
            (reply.readInt() == 1).also { Log.i(TAG, "display $CLUSTER_DISPLAY_ID windowing mode $mode: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "display mode change failed: $e")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Makes the launched task fill the cluster. The display's default windowing
     * mode is freeform here, which gives the task a caption bar and whatever
     * bounds the ROM last remembered; fullscreen mode plus an explicit resize
     * fixes both. Best effort - a task that stays freeform still projects.
     */
    private fun fillDisplay(context: Context, packageName: String) {
        val until = SystemClock.elapsedRealtime() + TASK_WAIT_MS
        var taskId: Int? = null
        while (taskId == null && SystemClock.elapsedRealtime() < until) {
            taskId = clusterTask(AdbLink.shell(context, "am stack list"), packageName)
            if (taskId == null) SystemClock.sleep(250)
        }
        if (taskId == null) {
            Log.w(TAG, "no task for $packageName on display $CLUSTER_DISPLAY_ID yet")
            return
        }
        val fullscreen = daemon?.let { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
                data.writeInt(taskId)
                data.writeInt(WINDOWING_MODE_FULLSCREEN)
                binder.transact(ClusterDaemon.TXN_SET_TASK_MODE, data, reply, 0)
                reply.readException()
                reply.readInt() == 1
            } catch (e: Exception) {
                Log.w(TAG, "windowing mode change failed: $e")
                false
            } finally {
                reply.recycle()
                data.recycle()
            }
        } ?: false
        Log.i(TAG, "task $taskId fullscreen: $fullscreen")
        // A fullscreen task refuses resizing, and no longer needs it.
        if (!fullscreen) shell(context, "am task resize $taskId 0 0 $CLUSTER_WIDTH $CLUSTER_HEIGHT")
    }

    /**
     * The id of [packageName]'s task on the cluster display, from `am stack list`:
     * a `Stack id=… displayId=N` line heads each stack, and its tasks follow as
     * `taskId=N: pkg/activity …` lines.
     */
    internal fun clusterTask(stackList: String, packageName: String): Int? {
        var onCluster = false
        for (line in stackList.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Stack id=")) {
                onCluster = Regex("displayId=([0-9]+)").find(trimmed)?.groupValues?.get(1) ==
                    CLUSTER_DISPLAY_ID.toString()
                continue
            }
            if (!onCluster) continue
            val match = Regex("^taskId=([0-9]+): ([^/ ]+)/").find(trimmed) ?: continue
            if (match.groupValues[2] == packageName) return match.groupValues[1].toInt()
        }
        return null
    }

    /** The cluster display's layer stack: what the mirror must composite. */
    private fun layerStack(context: Context): Int {
        val display = clusterDisplay(context) ?: return CLUSTER_DISPLAY_ID
        HiddenApi.exempted
        return runCatching {
            Display::class.java.getMethod("getLayerStack").invoke(display) as Int
        }.getOrDefault(CLUSTER_DISPLAY_ID)
    }

    /**
     * Finds a live daemon of the current protocol, or starts one. The lookup is
     * the service manager first; if the ROM refused the registration the daemon's
     * broadcast fills [daemon] instead.
     */
    private fun ensureDaemon(context: Context) {
        daemon?.let { if (it.isBinderAlive && ping(it) == ClusterDaemon.PROTOCOL) return }
        daemon = null
        AdbLink.connect(context)
        listenForDaemon(context)

        lookup()?.let { found ->
            val protocol = ping(found)
            if (protocol == ClusterDaemon.PROTOCOL) {
                daemon = found
                main.post { linkReady = true }
                return
            }
            Log.i(TAG, "daemon speaks protocol $protocol, want ${ClusterDaemon.PROTOCOL}; restarting it")
        }
        // Any daemon still around is stale or unreachable; replace it.
        shell(context, "for p in \$(pidof ${ClusterDaemon.PROCESS_NAME}); do kill \$p; done; true")
        val apk = context.applicationInfo.sourceDir
        val started = shell(context, ClusterDaemon.startCommand(apk))
        if (!started.contains("STARTED")) error("Could not start the cluster helper: ${started.trim()}")

        val until = SystemClock.elapsedRealtime() + DAEMON_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            val found = daemon ?: lookup()
            if (found != null && ping(found) == ClusterDaemon.PROTOCOL) {
                daemon = found
                main.post { linkReady = true }
                return
            }
            SystemClock.sleep(250)
        }
        val log = runCatching { shell(context, "tail -n 20 ${ClusterDaemon.LOG_PATH}") }.getOrDefault("")
        error("The cluster helper did not come up. Its log says:\n${log.trim()}")
    }

    private fun lookup(): IBinder? = runCatching {
        HiddenApi.exempted
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, ClusterDaemon.SERVICE_NAME) as? IBinder
    }.getOrNull()

    private fun ping(binder: IBinder): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            if (!binder.transact(ClusterDaemon.TXN_PING, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun listenForDaemon(context: Context) {
        if (receiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val binder = intent.extras?.getBinder(ClusterDaemon.EXTRA_BINDER) ?: return
                Log.i(TAG, "daemon announced itself")
                daemon = binder
            }
        }
        ContextCompat.registerReceiver(
            context,
            listener,
            IntentFilter(ClusterDaemon.ACTION_READY),
            ClusterDaemon.PERMISSION_READY,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = listener
    }

    /** Worker thread only. */
    private fun refreshMirror() {
        val binder = daemon ?: return
        val surface = previewSurface ?: return
        if (phase != Phase.PROJECTING || !surface.isValid) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val ok = try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(layerStackCached)
            data.writeInt(CLUSTER_WIDTH)
            data.writeInt(CLUSTER_HEIGHT)
            data.writeInt(previewWidth)
            data.writeInt(previewHeight)
            surface.writeToParcel(data, 0)
            binder.transact(ClusterDaemon.TXN_MIRROR_START, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Exception) {
            Log.w(TAG, "mirror start failed: $e")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
        main.post { mirrorLive = ok }
    }

    private var layerStackCached = CLUSTER_DISPLAY_ID

    /** Worker thread only. */
    private fun stopMirror() {
        main.post { mirrorLive = false }
        val binder = daemon ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            binder.transact(ClusterDaemon.TXN_MIRROR_STOP, data, reply, 0)
        } catch (e: Exception) {
            Log.w(TAG, "mirror stop failed: $e")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun publish(next: Phase, text: String) {
        Log.i(TAG, "$next: $text")
        main.post {
            phase = next
            status = text
        }
    }

    private fun fail(error: Throwable) {
        Log.w(TAG, "failed", error)
        val text = error.message ?: error.javaClass.simpleName
        main.post {
            phase = if (projected != null) Phase.PROJECTING else Phase.IDLE
            status = text
            if (error is AdbLink.LinkException) linkReady = false
        }
    }
}
