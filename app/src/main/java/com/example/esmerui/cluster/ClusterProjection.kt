package com.example.esmerui.cluster

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
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
import com.example.esmerui.car.CarBus
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.window.HiddenApi
import java.util.concurrent.CopyOnWriteArraySet

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
 *  2. The display is put into fullscreen windowing (it defaults to freeform,
 *     which hangs a caption bar on every task), the app is stopped and then
 *     `am start --display 1` fresh, so one already open on the main screen does
 *     not just come forward there. A placement other than the whole cluster
 *     turns the task back to freeform with explicit bounds.
 *  3. [ClusterDaemon] mirrors display 1's layer stack into the settings preview
 *     and the floating [ClusterMirrorWindow], and injects their touches onto
 *     display 1.
 *  4. Stop: mirrors off, app stopped, display mode restored, `18` (projection
 *     off) then `0` (cluster resumes its own stream).
 *
 * The codes and their order are DashCast's, confirmed on DiLink 3.
 *
 * All of the work runs on one background thread; state for the UI is published
 * on the main thread, and [listeners] hear every change.
 */
object ClusterProjection {

    private const val TAG = "ClusterProjection"

    const val CLUSTER_DISPLAY_ID = 1
    const val CLUSTER_WIDTH = 1920
    const val CLUSTER_HEIGHT = 720

    /** The settings preview and the floating window each get their own mirror. */
    const val SLOT_PREVIEW = 0
    const val SLOT_FLOAT = 1

    private const val CONTAINER_TYPE = 1000
    private const val CODE_PROJECTION_ON = 16
    private const val CODE_PROJECTION_OFF = 18
    private const val CODE_RESUME_CLUSTER = 0
    private const val CODE_CREATE_DISPLAY = 35

    /** The container needs a moment between codes; DashCast measured about three seconds. */
    private const val CODE_SETTLE_MS = 3_000L
    private const val DISPLAY_WAIT_MS = 8_000L
    private const val DAEMON_WAIT_MS = 8_000L
    private const val TASK_WAIT_MS = 6_000L

    /** Auto-start waits for adbd and the car to settle after a power-on. */
    private const val AUTO_START_ATTEMPTS = 6
    private const val AUTO_START_RETRY_MS = 5_000L

    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN and _FREEFORM. */
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    enum class Phase { IDLE, BUSY, PROJECTING }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /**
     * The phase as the worker sees it. Compose state is for the UI: read from a
     * background thread it can lag behind a main-thread write, which once let an
     * auto-start retry run on top of a projection that had already succeeded.
     */
    @Volatile private var current = Phase.IDLE
    var status by mutableStateOf("Not projecting.")
        private set
    var projected: ComponentName? by mutableStateOf(null)
        private set
    var linkReady by mutableStateOf(false)
        private set
    /** Whether the settings preview is drawing the cluster right now. */
    var mirrorLive by mutableStateOf(false)
        private set

    /** Called on the main thread after any change to [phase] or [projected]. */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private val worker = Handler(HandlerThread("cluster-projection").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var daemon: IBinder? = null
    private var receiver: BroadcastReceiver? = null

    private class Preview(var surface: Surface? = null, var width: Int = 0, var height: Int = 0)
    private val previews = arrayOf(Preview(), Preview())

    /** The task on the cluster, once launched; what placements and bounds apply to. */
    private var taskId: Int? = null

    /**
     * A slot in use: the helper's virtual display and where its window sits on
     * the cluster. Touches inside those bounds go to that display instead.
     */
    @Volatile private var slotDisplay = -1
    @Volatile private var slotBounds: Rect? = null
    private var layerStackCached = CLUSTER_DISPLAY_ID

    /**
     * The cluster display's windowing mode before projection changed it. The
     * ROM keeps that display in freeform (its own split-screen window lives
     * there, caption bar and all), so it is put back on stop.
     */
    private var displayModeBefore: Int? = null

    /** What the mirrors use; read from settings on every start and on change. */
    @Volatile private var scaling = MirrorScaling.FIT

    // ── Public entry points ──────────────────────────────────────────────────

    /** Opens the head unit link and starts the daemon without projecting anything. */
    fun connect(context: Context) {
        val app = context.applicationContext
        worker.post {
            if (current == Phase.BUSY) return@post
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
            if (current != Phase.IDLE && current != Phase.PROJECTING) return@post
            publish(Phase.BUSY, "Connecting to the head unit…")
            val settings = LauncherSettings.get(app)
            settings.clusterComponent = component.flattenToShortString()
            scaling = MirrorScaling.from(settings.clusterScaling)
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
                clearCluster(app)
                launch(app, component)
                projected = component
                applyPlacement(app)
                layerStackCached = layerStack(app)
                publish(Phase.PROJECTING, "Projecting to the cluster.")
                // The display may have just been created; the overlay follows it.
                ClusterInfoOverlay.sync(app)
                refreshMirrors()
            }.onFailure {
                fail(it)
                runCatching {
                    releaseDisplay()
                    sendCode(app, CODE_PROJECTION_OFF)
                    SystemClock.sleep(500)
                    sendCode(app, CODE_RESUME_CLUSTER)
                }
            }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        worker.post {
            if (current == Phase.BUSY) return@post
            publish(Phase.BUSY, "Handing the cluster back…")
            stopMirror(SLOT_PREVIEW)
            stopMirror(SLOT_FLOAT)
            val current = projected
            runCatching {
                // The cluster display first, docked stacks dissolved before their apps
                // die; then whatever sits in a slot, stopped before the slot goes so
                // it cannot pop up on the main screen.
                clearCluster(app)
                if (current != null) shell(app, "am force-stop ${ClusterDaemon.quote(current.packageName)}")
                detachSlot()
                releaseDisplay()
                sendCode(app, CODE_PROJECTION_OFF)
                SystemClock.sleep(CODE_SETTLE_MS / 3)
                sendCode(app, CODE_RESUME_CLUSTER)
                taskId = null
                main.post { projected = null }
                publish(Phase.IDLE, "Not projecting.")
            }.onFailure { fail(it) }
        }
    }

    /** A bar press: stop what is projecting, or cast the last-used app. */
    fun toggle(context: Context) {
        if (current == Phase.PROJECTING) {
            stop(context)
            return
        }
        val component = lastUsed(context)
        if (component == null) {
            publish(Phase.IDLE, "Pick an app in Settings › Cluster first.")
            return
        }
        start(context, component)
    }

    /** The app most recently cast, as the settings file remembers it. */
    fun lastUsed(context: Context): ComponentName? =
        LauncherSettings.get(context).clusterComponent?.let { ComponentName.unflattenFromString(it) }

    /**
     * The bar service has just come up. After the car's quickboot that is the
     * first the launcher hears of a new session, so this is where auto-start
     * lands when the car is already on.
     */
    fun onServiceStart(context: Context) {
        val level = runCatching { CarBus.powerLevel() }.getOrNull()
        if (level != null && level != CarBus.carOff) autoStart(context, "service start, power level $level")
    }

    /**
     * Casts the last-used app if the setting asks for it. The unit's adbd and the
     * cluster both take a few seconds after a power-on, so a failed attempt is
     * retried a handful of times rather than given up on.
     */
    fun autoStart(context: Context, reason: String) {
        val app = context.applicationContext
        val settings = LauncherSettings.get(app)
        if (!settings.clusterAutoStart) return
        val component = lastUsed(app) ?: return
        if (current != Phase.IDLE) return
        Log.i(TAG, "auto-start ($reason): ${component.flattenToShortString()}")
        autoStartAttempt(app, component, attempt = 1)
    }

    private fun autoStartAttempt(app: Context, component: ComponentName, attempt: Int) {
        if (attempt > AUTO_START_ATTEMPTS) {
            Log.w(TAG, "auto-start gave up after ${attempt - 1} attempts")
            return
        }
        start(app, component)
        // start() runs on the worker; checking after it has had its turn tells
        // whether it got there: a failure leaves the phase at IDLE, success at
        // PROJECTING, and a retry only ever follows a failure.
        worker.postDelayed({
            if (current == Phase.IDLE && LauncherSettings.get(app).clusterAutoStart) {
                Log.i(TAG, "auto-start attempt $attempt did not take; retrying")
                autoStartAttempt(app, component, attempt + 1)
            }
        }, AUTO_START_RETRY_MS)
    }

    /**
     * The car is switching off: the ROM force-stops every third-party app in
     * about a second, so only the display mode is worth restoring, and quickly.
     * The cluster's own power cycle takes care of the rest.
     */
    fun onCarOff(context: Context) {
        if (current == Phase.IDLE) return
        worker.post {
            runCatching { releaseDisplay() }
            runCatching { detachSlot() }
            stopMirror(SLOT_PREVIEW)
            stopMirror(SLOT_FLOAT)
            taskId = null
            main.post { projected = null }
            publish(Phase.IDLE, "Not projecting.")
        }
    }

    /** A mirror view is up (or resized): mirror into it when there is something to show. */
    fun attachPreview(slot: Int, surface: Surface, width: Int, height: Int) {
        worker.post {
            val preview = previews[slot]
            preview.surface?.takeIf { it !== surface }?.release()
            preview.surface = surface
            preview.width = width
            preview.height = height
            refreshMirror(slot)
        }
    }

    fun detachPreview(slot: Int) {
        worker.post {
            stopMirror(slot)
            previews[slot].surface?.release()
            previews[slot].surface = null
        }
    }

    /**
     * A touch on a mirror view, re-targeted at the cluster through the same
     * rectangles the daemon composites with, so a finger lands where it points.
     */
    fun forwardTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val binder = daemon ?: return false
        if (current != Phase.PROJECTING || viewWidth <= 0 || viewHeight <= 0) return false
        val (source, target) = ClusterGeometry.mirrorRects(scaling, viewWidth, viewHeight)
        val copy = MotionEvent.obtain(event)
        copy.transform(ClusterGeometry.viewToCluster(source, target))
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        // A finger inside the slot belongs to the slot's own display, offset to it.
        var display = CLUSTER_DISPLAY_ID
        val slot = slotBounds
        if (slot != null && slotDisplay >= 0 && slot.contains(copy.x.toInt(), copy.y.toInt())) {
            copy.offsetLocation(-slot.left.toFloat(), -slot.top.toFloat())
            display = slotDisplay
        }
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(display)
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

    /** Changes how the mirrors fill their views; takes effect at once. */
    fun setScaling(context: Context, mode: MirrorScaling) {
        LauncherSettings.get(context).clusterScaling = mode.name
        worker.post {
            scaling = mode
            refreshMirrors()
        }
    }

    /** Moves the cast window; takes effect at once when something is projecting. */
    fun setPlacement(context: Context, placement: ClusterPlacement, custom: Rect? = null) {
        val settings = LauncherSettings.get(context)
        settings.clusterPlacement = placement.name
        if (custom != null) settings.clusterBounds = ClusterGeometry.encode(custom)
        val app = context.applicationContext
        worker.post { if (current == Phase.PROJECTING) applyPlacement(app) }
    }

    /** The bounds the current settings put the cast window at. */
    fun currentBounds(context: Context): Rect {
        val settings = LauncherSettings.get(context)
        return ClusterGeometry.bounds(
            ClusterPlacement.from(settings.clusterPlacement),
            ClusterGeometry.decode(settings.clusterBounds),
        )
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
     * floating window, wrong on a dial.
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

    private fun displayMode(): Int? = transactInt(ClusterDaemon.TXN_GET_DISPLAY_MODE) {
        writeInt(CLUSTER_DISPLAY_ID)
    }?.takeIf { it >= 0 }

    private fun setDisplayMode(mode: Int): Boolean {
        val ok = transactInt(ClusterDaemon.TXN_SET_DISPLAY_MODE) {
            writeInt(CLUSTER_DISPLAY_ID)
            writeInt(mode)
        } == 1
        Log.i(TAG, "display $CLUSTER_DISPLAY_ID windowing mode $mode: $ok")
        return ok
    }

    /** Stops the app and starts it fresh on the cluster display, then waits for its task. */
    private fun launch(context: Context, component: ComponentName) {
        shell(context, "am force-stop ${ClusterDaemon.quote(component.packageName)}")
        val launched = shell(
            context,
            "am start --display $CLUSTER_DISPLAY_ID -n " + ClusterDaemon.quote(component.flattenToString()),
        )
        if (launched.contains("Error", ignoreCase = true)) error(launched.trim())
        taskId = awaitTask(context, component.packageName)
    }

    /** Debug: asks the helper for a still of the cluster; true when the file was written. */
    fun capture(context: Context): Boolean {
        val app = context.applicationContext
        var ok = false
        val done = java.util.concurrent.CountDownLatch(1)
        worker.post {
            ok = runCatching {
                ensureDaemon(app)
                transactInt(ClusterDaemon.TXN_CAPTURE) { writeInt(layerStackCached) } == 1
            }.getOrDefault(false)
            done.countDown()
        }
        done.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    /** Waits for the launched task to show up on the cluster display. */
    private fun awaitTask(context: Context, packageName: String): Int? {
        val until = SystemClock.elapsedRealtime() + TASK_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            findTask(AdbLink.shell(context, "am stack list"), packageName)?.let { return it.taskId }
            SystemClock.sleep(250)
        }
        Log.w(TAG, "no task for $packageName on display $CLUSTER_DISPLAY_ID yet")
        return null
    }

    /**
     * Removes every stack on the cluster display. A window left there by an
     * earlier session would sit over or under the new one, and a stale docked
     * stack stops a fresh one from docking at all.
     */
    private fun clearCluster(context: Context, except: Int? = null) {
        val listing = AdbLink.shell(context, "am stack list")
        // Every package with a task on the cluster display, other than the one in
        // the stack being kept: a docked stack with a task in it survives
        // `am stack remove` on this ROM, and a new launch then joins that task.
        var stackId = -1
        var onCluster = false
        val packages = LinkedHashSet<String>()
        val tasks = ArrayList<Int>()
        val stacks = ArrayList<Int>()
        for (line in listing.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Stack id=")) {
                stackId = Regex("Stack id=([0-9]+)").find(trimmed)?.groupValues?.get(1)?.toInt() ?: -1
                onCluster = Regex("displayId=([0-9]+)").find(trimmed)?.groupValues?.get(1)?.toInt() == CLUSTER_DISPLAY_ID
                if (onCluster && stackId != except) stacks += stackId
                continue
            }
            if (!onCluster || stackId == except) continue
            Regex("^taskId=([0-9]+): ([^/ ]+)/").find(trimmed)?.let {
                tasks += it.groupValues[1].toInt()
                packages += it.groupValues[2]
            }
        }
        // Removing the last task of a docked stack on this display throws inside
        // system_server and leaves the stack behind, so each task is made
        // fullscreen (which also dissolves a docked stack) before its app is stopped.
        tasks.forEach { task ->
            transactInt(ClusterDaemon.TXN_SET_TASK_MODE) {
                writeInt(task)
                writeInt(WINDOWING_MODE_FULLSCREEN)
            }
        }
        packages.filter { it != context.packageName }
            .forEach { shell(context, "am force-stop ${ClusterDaemon.quote(it)}") }
        stacks.forEach { shell(context, "am stack remove $it") }
        if (stacks.isNotEmpty() || packages.isNotEmpty()) SystemClock.sleep(500)
    }

    /** The bounds `am stack list` reports for a task, or null. */
    internal fun taskBounds(stackList: String, taskId: Int): Rect? =
        Regex("taskId=$taskId: [^ ]+ bounds=\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]")
            .find(stackList)?.groupValues?.let { Rect(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt()) }

    /** Whether two rectangles agree to within a few pixels of platform rounding. */
    private fun close(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= 16 && kotlin.math.abs(a.top - b.top) <= 16 &&
            kotlin.math.abs(a.right - b.right) <= 16 && kotlin.math.abs(a.bottom - b.bottom) <= 16

    /** A stack id, task id and display from `am stack list`. */
    internal data class TaskLocation(val stackId: Int, val taskId: Int, val displayId: Int)

    /**
     * Where [packageName]'s task is, from `am stack list`: a `Stack id=…
     * displayId=N` line heads each stack, and its tasks follow as
     * `taskId=N: pkg/activity …` lines. The main screen is only reported when
     * nothing else has it, since a cast app may be open there too.
     */
    internal fun findTask(stackList: String, packageName: String): TaskLocation? {
        var stackId = -1
        var displayId = -1
        var onMain: TaskLocation? = null
        for (line in stackList.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Stack id=")) {
                stackId = Regex("Stack id=([0-9]+)").find(trimmed)?.groupValues?.get(1)?.toInt() ?: -1
                displayId = Regex("displayId=([0-9]+)").find(trimmed)?.groupValues?.get(1)?.toInt() ?: -1
                continue
            }
            val match = Regex("^taskId=([0-9]+): ([^/ ]+)/").find(trimmed) ?: continue
            if (match.groupValues[2] != packageName) continue
            val found = TaskLocation(stackId, match.groupValues[1].toInt(), displayId)
            if (displayId == 0) onMain = onMain ?: found else return found
        }
        return onMain
    }

    /**
     * Sizes the cast window to the placement in settings. Three ways, none of
     * which draws the ROM's freeform caption bar:
     *  - the whole cluster: a fullscreen task on the cluster display;
     *  - a left or right half: the task docked as split-screen primary, which
     *    this ROM anchors to that edge;
     *  - anything else: a slot - the helper's window at those bounds hosting a
     *    virtual display, with the task moved onto it and fullscreen there.
     * Freeform with explicit bounds remains the last resort, caption and all.
     * Worker thread only.
     */
    private fun applyPlacement(context: Context) {
        val component = projected ?: return
        val location = findTask(AdbLink.shell(context, "am stack list"), component.packageName)
        if (location == null) {
            Log.w(TAG, "placement: no task for ${component.packageName}")
            return
        }
        val task = location.taskId
        taskId = task
        val bounds = currentBounds(context)
        val whole = bounds == ClusterGeometry.full
        val half = bounds.top == 0 && bounds.bottom == CLUSTER_HEIGHT &&
            (bounds.left == 0 || bounds.right == CLUSTER_WIDTH) && bounds.width() < CLUSTER_WIDTH

        // A task coming out of a slot keeps the slot's bounds whatever mode it
        // is given next, so it is started again on the cluster display instead.
        if (location.displayId != CLUSTER_DISPLAY_ID) {
            launch(context, component)
            detachSlot()
            return applyPlacement(context)
        }
        detachSlot()
        if (whole) {
            val switched = transactInt(ClusterDaemon.TXN_SET_TASK_MODE) {
                writeInt(task)
                writeInt(WINDOWING_MODE_FULLSCREEN)
            } == 1
            Log.i(TAG, "task $task placement full: $switched")
            return
        }
        // Docked first: no caption, and stays on the cluster display, which any
        // app's own activities may use. Docking snaps to a half; the divider's
        // resize call then moves it to the exact rectangle.
        clearCluster(context, except = location.stackId)
        val docked = transactInt(ClusterDaemon.TXN_SET_TASK_SPLIT) {
            writeInt(task)
            writeInt(bounds.left); writeInt(bounds.top); writeInt(bounds.right); writeInt(bounds.bottom)
        } == 1
        if (docked) {
            if (half) {
                Log.i(TAG, "task $task placement $bounds: docked")
                return
            }
            val resized = transactInt(ClusterDaemon.TXN_RESIZE_DOCKED) {
                writeInt(bounds.left); writeInt(bounds.top); writeInt(bounds.right); writeInt(bounds.bottom)
            } == 1
            SystemClock.sleep(300)
            val landed = findTask(AdbLink.shell(context, "am stack list"), component.packageName)
                ?.let { taskBounds(AdbLink.shell(context, "am stack list"), it.taskId) }
            Log.i(TAG, "task $task placement $bounds: docked, resized=$resized, now $landed")
            if (resized && landed != null && close(landed, bounds)) return
        }
        // A slot: the helper's window with its own display. Only apps whose every
        // activity is started by a privileged caller may land there - an app that
        // starts a second activity of its own is refused by the platform.
        run {
            // Lets the helper's fallback overlay type show, should the presentation
            // window be refused; harmless where the op is already allowed or unknown.
            runCatching { shell(context, "appops set --uid 2000 SYSTEM_ALERT_WINDOW allow") }
            val display = transactInt(ClusterDaemon.TXN_SLOT_ATTACH) {
                writeInt(bounds.left); writeInt(bounds.top); writeInt(bounds.right); writeInt(bounds.bottom)
            } ?: -1
            if (display >= 0) {
                val moved = transactInt(ClusterDaemon.TXN_MOVE_STACK) {
                    writeInt(location.stackId)
                    writeInt(display)
                } == 1
                val fullscreen = transactInt(ClusterDaemon.TXN_SET_TASK_MODE) {
                    writeInt(task)
                    writeInt(WINDOWING_MODE_FULLSCREEN)
                } == 1
                slotDisplay = display
                slotBounds = Rect(bounds)
                Log.i(TAG, "task $task placement $bounds: slot display $display moved=$moved fullscreen=$fullscreen")
                if (moved) return
                detachSlot()
            }
        }

        // Last resort: freeform with explicit bounds, caption and all.
        if (location.displayId != CLUSTER_DISPLAY_ID) {
            transactInt(ClusterDaemon.TXN_MOVE_STACK) {
                writeInt(location.stackId)
                writeInt(CLUSTER_DISPLAY_ID)
            }
        }
        val switched = transactInt(ClusterDaemon.TXN_SET_TASK_MODE) {
            writeInt(task)
            writeInt(WINDOWING_MODE_FREEFORM)
        } == 1
        val sized = transactInt(ClusterDaemon.TXN_SET_TASK_BOUNDS) {
            writeInt(task)
            writeInt(bounds.left); writeInt(bounds.top); writeInt(bounds.right); writeInt(bounds.bottom)
        } == 1
        Log.i(TAG, "task $task placement $bounds: freeform=$switched bounds=$sized")
    }

    /** Worker thread only. Releases the slot, if any; the task must be elsewhere or stopped first. */
    private fun detachSlot() {
        if (slotDisplay < 0 && slotBounds == null) return
        slotDisplay = -1
        slotBounds = null
        transactInt(ClusterDaemon.TXN_SLOT_DETACH) {}
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
        val apk = context.applicationInfo.sourceDir
        daemon?.let { if (it.isBinderAlive && current(it, apk)) return }
        daemon = null
        AdbLink.connect(context)
        listenForDaemon(context)

        lookup()?.let { found ->
            if (current(found, apk)) {
                daemon = found
                main.post { linkReady = true }
                return
            }
            Log.i(TAG, "daemon is stale (protocol or APK); restarting it")
        }
        // Any daemon still around is stale or unreachable; replace it.
        shell(context, "for p in \$(pidof ${ClusterDaemon.PROCESS_NAME}); do kill \$p; done; true")
        val started = shell(context, ClusterDaemon.startCommand(apk))
        if (!started.contains("STARTED")) error("Could not start the cluster helper: ${started.trim()}")

        val until = SystemClock.elapsedRealtime() + DAEMON_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            val found = daemon ?: lookup()
            if (found != null && current(found, apk)) {
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

    /** Whether a daemon speaks this protocol and was started from this very APK. */
    private fun current(binder: IBinder, apk: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            if (!binder.transact(ClusterDaemon.TXN_PING, data, reply, 0)) return false
            reply.readException()
            val protocol = reply.readInt()
            reply.readInt()
            val classpath = runCatching { reply.readString() }.getOrNull()
            protocol == ClusterDaemon.PROTOCOL && classpath == apk
        } catch (e: Exception) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * One request to the daemon with an int reply, or null when it could not be
     * made. The body writes the arguments after the interface token.
     */
    private fun transactInt(code: Int, binder: IBinder? = daemon, body: Parcel.() -> Unit): Int? {
        val target = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.body()
            if (!target.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "transaction $code failed: $e")
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
    private fun refreshMirrors() {
        refreshMirror(SLOT_PREVIEW)
        refreshMirror(SLOT_FLOAT)
    }

    /** Worker thread only. */
    private fun refreshMirror(slot: Int) {
        val preview = previews[slot]
        val surface = preview.surface ?: return
        if (current != Phase.PROJECTING || !surface.isValid || daemon == null) return
        val (source, target) = ClusterGeometry.mirrorRects(scaling, preview.width, preview.height)
        val binder = daemon ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val ok = try {
            data.writeInterfaceToken(ClusterDaemon.DESCRIPTOR)
            data.writeInt(slot)
            data.writeInt(layerStackCached)
            data.writeInt(source.left); data.writeInt(source.top); data.writeInt(source.right); data.writeInt(source.bottom)
            data.writeInt(target.left); data.writeInt(target.top); data.writeInt(target.right); data.writeInt(target.bottom)
            surface.writeToParcel(data, 0)
            binder.transact(ClusterDaemon.TXN_MIRROR_START, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Exception) {
            Log.w(TAG, "mirror $slot start failed: $e")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
        if (slot == SLOT_PREVIEW) main.post { mirrorLive = ok }
    }

    /** Worker thread only. */
    private fun stopMirror(slot: Int) {
        if (slot == SLOT_PREVIEW) main.post { mirrorLive = false }
        transactInt(ClusterDaemon.TXN_MIRROR_STOP) { writeInt(slot) }
    }

    private fun publish(next: Phase, text: String) {
        Log.i(TAG, "$next: $text")
        current = next
        main.post {
            phase = next
            status = text
            listeners.forEach { it() }
        }
    }

    private fun fail(error: Throwable) {
        Log.w(TAG, "failed", error)
        val text = error.message ?: error.javaClass.simpleName
        current = if (projected != null) Phase.PROJECTING else Phase.IDLE
        main.post {
            phase = current
            status = text
            if (error is AdbLink.LinkException) linkReady = false
            listeners.forEach { it() }
        }
    }
}
