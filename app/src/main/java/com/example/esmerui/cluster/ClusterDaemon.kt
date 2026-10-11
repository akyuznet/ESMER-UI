package com.example.esmerui.cluster

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.example.esmerui.launcher.window.HiddenApi
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The launcher's shell-side helper for the cluster: a small process that
 * [AdbLink] starts as uid 2000 with this APK on its classpath, and that the app
 * then reaches over binder.
 *
 * It holds the two things only the shell user may do: a SurfaceFlinger mirror of
 * the cluster's layer stack into a surface the app owns (the live preview on the
 * head unit), and input injection onto the cluster display (tapping that
 * preview). Everything else the projection needs - the container codes, launching
 * and stopping the app - is a plain shell command and goes over ADB directly.
 *
 * Started by [startCommand]; publishes its binder under [SERVICE_NAME] in the
 * service manager and, in case that is refused, broadcasts it to the app behind
 * a signature permission. Lives until [TXN_EXIT] or a reboot; a quickboot leaves
 * it running, which is fine - the app pings for [PROTOCOL] and restarts a stale one.
 *
 * Nothing in here may touch the launcher's UI classes: this process has no
 * application, no resources and no Compose.
 */
object ClusterDaemon {

    private const val TAG = "ClusterDaemon"

    const val SERVICE_NAME = "esmerui_cluster"
    const val DESCRIPTOR = "com.example.esmerui.cluster.IClusterDaemon"
    const val PROCESS_NAME = "esmerui.cluster"
    const val LOG_PATH = "/data/local/tmp/esmerui_cluster.log"
    const val ACTION_READY = "com.example.esmerui.CLUSTER_DAEMON_READY"
    const val PERMISSION_READY = "com.example.esmerui.permission.CLUSTER_DAEMON"
    const val EXTRA_BINDER = "binder"
    private const val APP_PACKAGE = "com.example.esmerui"

    /** Bumped whenever the wire contract below changes; a running daemon reports its own. */
    const val PROTOCOL = 9

    const val TXN_PING = 1
    const val TXN_MIRROR_START = 2
    const val TXN_MIRROR_STOP = 3
    const val TXN_INJECT_MOTION = 4
    const val TXN_INJECT_KEY = 5
    const val TXN_EXIT = 6
    /** Flips a task's windowing mode; the display defaults to freeform, which draws a caption. */
    const val TXN_SET_TASK_MODE = 7
    /** Reads or sets a display's default windowing mode (needs INTERNAL_SYSTEM_WINDOW). */
    const val TXN_GET_DISPLAY_MODE = 8
    const val TXN_SET_DISPLAY_MODE = 9
    /** Moves and resizes a freeform task (needs MANAGE_ACTIVITY_STACKS). */
    const val TXN_SET_TASK_BOUNDS = 10
    /** Docks a task as split-screen primary at given bounds: a sized window with no caption. */
    const val TXN_SET_TASK_SPLIT = 11
    /**
     * A slot: a window at given bounds on the cluster display hosting a virtual
     * display of that size, for a cast window that is neither the whole dial nor
     * a docked half. Replies with the virtual display's id.
     */
    const val TXN_SLOT_ATTACH = 12
    const val TXN_SLOT_DETACH = 13
    /** Moves a stack between displays (needs MANAGE_ACTIVITY_STACKS). */
    const val TXN_MOVE_STACK = 14
    /** Debug: writes what the cluster's layer stack shows to [CAPTURE_PATH]; replies 1 on success. */
    const val TXN_CAPTURE = 15
    /** Resizes the docked stack to arbitrary bounds, the way the split-screen divider does. */
    const val TXN_RESIZE_DOCKED = 16
    const val CAPTURE_PATH = "/data/local/tmp/esmerui_cluster.png"

    /** How long the daemon keeps re-announcing its binder for an app that started late. */
    private const val ANNOUNCE_MS = 60_000L
    private const val ANNOUNCE_INTERVAL_MS = 2_000L

    /**
     * The shell line that starts this process detached from the ADB session.
     * `setsid` keeps it alive when the link closes; the AOT flag is DashCast's
     * finding that app_process otherwise trips over the APK's oat file.
     */
    fun startCommand(apkPath: String): String {
        val child = "CLASSPATH=${quote(apkPath)} exec /system/bin/app_process64" +
            " -Xnoimage-dex2oat /system/bin --nice-name=$PROCESS_NAME" +
            " com.example.esmerui.cluster.ClusterDaemon" +
            " </dev/null >$LOG_PATH 2>&1"
        return "setsid sh -c ${quote(child)} </dev/null >/dev/null 2>&1 & echo STARTED"
    }

    /** Single-quotes a shell word; an embedded quote becomes the usual close-escape-reopen. */
    fun quote(value: String): String {
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    // ── Process entry ────────────────────────────────────────────────────────

    @Volatile private var appUid = -1
    @Volatile private var pinged = false

    /** The shell package's context: what the display manager wants a virtual display owner to carry. */
    @Volatile private var shellContext: Context? = null

    @JvmStatic
    fun main(args: Array<String>) {
        log("starting uid=${Process.myUid()} pid=${Process.myPid()} protocol=$PROTOCOL")
        runCatching {
            Process::class.java.getMethod("setArgV0", String::class.java).invoke(null, PROCESS_NAME)
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        log("hidden api exempted=${HiddenApi.exempted}")

        val context = systemContext()
        if (context == null) {
            log("no system context; giving up")
            return
        }
        shellContext = runCatching { context.createPackageContext("com.android.shell", 0) }
            .onFailure { log("shell package context failed: $it") }
            .getOrNull()
        appUid = runCatching { context.packageManager.getPackageUid(APP_PACKAGE, 0) }
            .onFailure { log("app uid lookup failed: $it") }
            .getOrDefault(-1)
        log("app uid $appUid")

        Input.init()
        val binder = DaemonBinder()
        register(binder)
        announce(context, binder)
        log("ready")
        Looper.loop()
    }

    /** What app_process gives a shell-started Java process instead of an Application. */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        thread.javaClass.getMethod("getSystemContext").invoke(thread) as? Context
    }.onFailure { log("system context failed: $it") }.getOrNull()

    private fun register(binder: IBinder) {
        runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            try {
                manager.getDeclaredMethod(
                    "addService",
                    String::class.java, IBinder::class.java,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(null, SERVICE_NAME, binder, false, 0)
            } catch (e: NoSuchMethodException) {
                manager.getDeclaredMethod("addService", String::class.java, IBinder::class.java)
                    .apply { isAccessible = true }.invoke(null, SERVICE_NAME, binder)
            }
            log("registered as $SERVICE_NAME")
        }.onFailure { log("addService refused (${it.cause ?: it}); relying on the broadcast") }
    }

    /**
     * Tells the app where the binder is. Repeated for a while because the app may
     * not be listening yet, and stops as soon as the app has pinged.
     */
    private fun announce(context: Context, binder: IBinder) {
        val handler = Handler(Looper.getMainLooper())
        val until = System.currentTimeMillis() + ANNOUNCE_MS
        val send = object : Runnable {
            override fun run() {
                if (pinged || System.currentTimeMillis() > until) return
                runCatching {
                    val intent = Intent(ACTION_READY)
                        .setPackage(APP_PACKAGE)
                        .putExtras(Bundle().apply { putBinder(EXTRA_BINDER, binder) })
                    context.sendBroadcast(intent, PERMISSION_READY)
                }.onFailure { log("announce failed: $it") }
                handler.postDelayed(this, ANNOUNCE_INTERVAL_MS)
            }
        }
        handler.post(send)
    }

    // ── Binder ───────────────────────────────────────────────────────────────

    private class DaemonBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val caller = getCallingUid()
            if (caller != appUid && caller != 0 && caller != 1000 && caller != 2000) {
                log("refused transaction $code from uid $caller")
                return false
            }
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                TXN_PING -> {
                    pinged = true
                    reply?.writeNoException()
                    reply?.writeInt(PROTOCOL)
                    reply?.writeInt(Process.myPid())
                    // The APK this process was started from, so the app can tell a
                    // helper left over from before an update and replace it.
                    reply?.writeString(System.getProperty("java.class.path") ?: "")
                    return true
                }
                TXN_MIRROR_START -> {
                    val slot = data.readInt()
                    val layerStack = data.readInt()
                    val source = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val target = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val ok = Mirror.start(slot, layerStack, source, target, surface)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_MIRROR_STOP -> {
                    Mirror.stop(data.readInt())
                    reply?.writeNoException()
                    return true
                }
                TXN_SET_TASK_BOUNDS -> {
                    val taskId = data.readInt()
                    val bounds = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val ok = Tasks.setBounds(taskId, bounds)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_SLOT_ATTACH -> {
                    val bounds = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val display = Slot.attach(bounds)
                    reply?.writeNoException()
                    reply?.writeInt(display)
                    return true
                }
                TXN_SLOT_DETACH -> {
                    Slot.detach()
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    return true
                }
                TXN_MOVE_STACK -> {
                    val stackId = data.readInt()
                    val display = data.readInt()
                    val ok = Tasks.moveStack(stackId, display)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_RESIZE_DOCKED -> {
                    val bounds = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val ok = Tasks.resizeDocked(bounds)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_CAPTURE -> {
                    val layerStack = data.readInt()
                    val ok = Mirror.capture(layerStack)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_SET_TASK_SPLIT -> {
                    val taskId = data.readInt()
                    val bounds = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                    val ok = Tasks.setSplitScreen(taskId, bounds)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_INJECT_MOTION -> {
                    val display = data.readInt()
                    val event = MotionEvent.CREATOR.createFromParcel(data)
                    Input.inject(event, display)
                    event.recycle()
                    return true
                }
                TXN_INJECT_KEY -> {
                    val display = data.readInt()
                    val event = KeyEvent.CREATOR.createFromParcel(data)
                    Input.inject(event, display)
                    return true
                }
                TXN_SET_TASK_MODE -> {
                    val taskId = data.readInt()
                    val mode = data.readInt()
                    val ok = Tasks.setWindowingMode(taskId, mode)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_GET_DISPLAY_MODE -> {
                    val display = data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(Tasks.displayWindowingMode(display) ?: -1)
                    return true
                }
                TXN_SET_DISPLAY_MODE -> {
                    val display = data.readInt()
                    val mode = data.readInt()
                    val ok = Tasks.setDisplayWindowingMode(display, mode)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TXN_EXIT -> {
                    reply?.writeNoException()
                    log("exit requested")
                    Handler(Looper.getMainLooper()).post {
                        Mirror.stopAll()
                        Slot.detach()
                        Process.killProcess(Process.myPid())
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // ── Mirror ───────────────────────────────────────────────────────────────

    /**
     * SurfaceFlinger displays that composite the cluster's layer stack into
     * surfaces the app owns - one per slot, so the settings preview and the
     * floating window can both be live. The same mechanism as screen mirroring;
     * the methods are hidden but present on this ROM, and need the shell's
     * ACCESS_SURFACE_FLINGER. The app sends the rectangles, so what it draws
     * and where it maps touches cannot disagree with what is composited.
     */
    private object Mirror {
        private val tokens = HashMap<Int, IBinder>()

        @Synchronized
        fun start(slot: Int, layerStack: Int, source: Rect, target: Rect, surface: Surface?): Boolean {
            stop(slot)
            if (surface == null || !surface.isValid) {
                log("mirror $slot: surface invalid")
                return false
            }
            if (source.isEmpty || target.isEmpty) {
                log("mirror $slot: bad geometry $source -> $target")
                return false
            }
            return runCatching {
                val surfaceControl = Class.forName("android.view.SurfaceControl")
                val create = surfaceControl.getDeclaredMethod(
                    "createDisplay", String::class.java, Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                val display = create.invoke(null, "esmerui_cluster_mirror_$slot", false) as? IBinder
                    ?: error("createDisplay returned null")
                tokens[slot] = display

                val transaction = SurfaceControl.Transaction()
                val type = transaction.javaClass
                type.getDeclaredMethod(
                    "setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(transaction, display, layerStack)
                type.getDeclaredMethod(
                    "setDisplaySurface", IBinder::class.java, Surface::class.java,
                ).apply { isAccessible = true }.invoke(transaction, display, surface)
                type.getDeclaredMethod(
                    "setDisplayProjection",
                    IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java,
                ).apply { isAccessible = true }.invoke(transaction, display, 0, source, target)
                transaction.apply()
                transaction.close()
                log("mirror $slot on: layer stack $layerStack, $source into $target")
                true
            }.onFailure {
                log("mirror $slot failed: ${it.cause ?: it}")
                stop(slot)
            }.getOrDefault(false)
        }

        @Synchronized
        fun stop(slot: Int) {
            val display = tokens.remove(slot) ?: return
            runCatching {
                Class.forName("android.view.SurfaceControl")
                    .getDeclaredMethod("destroyDisplay", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, display)
                log("mirror $slot off")
            }.onFailure { log("mirror $slot teardown failed: ${it.cause ?: it}") }
        }

        @Synchronized
        fun stopAll() = tokens.keys.toList().forEach { stop(it) }

        /**
         * A still of the layer stack, through a throwaway display token, written
         * as a PNG. Debug only: the one way to see the dial from a desk.
         */
        @Synchronized
        fun capture(layerStack: Int): Boolean = runCatching {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val create = surfaceControl.getDeclaredMethod(
                "createDisplay", String::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val token = create.invoke(null, "esmerui_cluster_capture", false) as? IBinder
                ?: error("createDisplay returned null")
            try {
                // SurfaceFlinger only knows a display once it has a surface, so the
                // token gets one (a reader nothing waits on) before the screenshot,
                // which renders into a second reader at once, dirty or not. An
                // ImageReader turns the frame into pixels without any GL here.
                val backing = android.media.ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 2)
                val transaction = SurfaceControl.Transaction()
                val type = transaction.javaClass
                type.getDeclaredMethod(
                    "setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(transaction, token, layerStack)
                type.getDeclaredMethod(
                    "setDisplaySurface", IBinder::class.java, Surface::class.java,
                ).apply { isAccessible = true }.invoke(transaction, token, backing.surface)
                type.getDeclaredMethod(
                    "setDisplayProjection",
                    IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java,
                ).apply { isAccessible = true }.invoke(transaction, token, 0, Rect(0, 0, 1920, 720), Rect(0, 0, 1920, 720))
                transaction.apply()
                transaction.close()
                Thread.sleep(150)
                val reader = android.media.ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 2)
                val shot = surfaceControl.getDeclaredMethod(
                    "screenshot", IBinder::class.java, Surface::class.java, Rect::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                shot.invoke(null, token, reader.surface, Rect(0, 0, 1920, 720), 1920, 720, false, 0)
                var image: android.media.Image? = null
                val until = System.currentTimeMillis() + 2_000
                while (image == null && System.currentTimeMillis() < until) {
                    image = reader.acquireLatestImage() ?: backing.acquireLatestImage()
                    if (image == null) Thread.sleep(50)
                }
                val frame = image ?: error("no frame from the screenshot")
                val plane = frame.planes[0]
                val stride = plane.rowStride / plane.pixelStride
                val bitmap = Bitmap.createBitmap(stride, frame.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, frame.width, frame.height)
                FileOutputStream(CAPTURE_PATH).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
                frame.close()
                reader.close()
                backing.close()
                log("captured layer stack $layerStack to $CAPTURE_PATH")
                true
            } finally {
                surfaceControl.getDeclaredMethod("destroyDisplay", IBinder::class.java)
                    .apply { isAccessible = true }.invoke(null, token)
            }
        }.onFailure { log("capture failed: ${Log.getStackTraceString(it)}") }.getOrDefault(false)
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    /**
     * The activity task manager, reached as the shell (MANAGE_ACTIVITY_STACKS).
     * The cluster display's default windowing mode is freeform on this ROM, so a
     * task launched there gets a caption bar; fullscreen is what a dial wants.
     */
    private object Tasks {
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3

        private fun service(): Any = Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService").invoke(null)
            ?: error("no activity task manager")

        fun setWindowingMode(taskId: Int, mode: Int): Boolean = runCatching {
            if (mode == WINDOWING_MODE_FULLSCREEN) undock(taskId)
            service().javaClass.getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).invoke(service(), taskId, mode, true)
            log("task $taskId windowing mode -> $mode")
            true
        }.onFailure { log("task $taskId windowing mode $mode failed: ${it.cause ?: it}") }
            .getOrDefault(false)

        /** The docked (split-screen primary) stack on a display, if one exists. */
        private fun dockedStackOn(displayId: Int): Int? = runCatching {
            val infos = service().javaClass.getMethod("getAllStackInfos").invoke(service()) as List<*>
            infos.firstOrNull { info ->
                info ?: return@firstOrNull false
                val type = info.javaClass
                val display = type.getField("displayId").get(info) as Int
                val configuration = type.getField("configuration").get(info)
                    ?: return@firstOrNull false
                // Configuration.windowConfiguration and its getter are hidden.
                val windowConfiguration = configuration.javaClass.getField("windowConfiguration").get(configuration)
                    ?: return@firstOrNull false
                val mode = windowConfiguration.javaClass.getMethod("getWindowingMode").invoke(windowConfiguration) as Int
                display == displayId && mode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
            }?.let { it.javaClass.getField("stackId").get(it) as Int }
        }.onFailure { log("stack listing failed: ${it.cause ?: it}") }.getOrNull()

        /**
         * A docked stack left on the cluster display outlives its last task on this
         * ROM, and while it exists every other stack there is laid out as the
         * docked secondary (the right half) and a second docking is refused. Moving
         * the task into it and switching that to fullscreen dissolves it.
         */
        private fun undock(taskId: Int) {
            val stale = dockedStackOn(1) ?: return
            runCatching {
                service().javaClass.getMethod(
                    "moveTaskToStack",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                ).invoke(service(), taskId, stale, true)
                service().javaClass.getMethod(
                    "setTaskWindowingMode",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                ).invoke(service(), taskId, WINDOWING_MODE_FULLSCREEN, true)
                log("stale docked stack $stale adopted by task $taskId and dissolved: ${dockedStackOn(1) == null}")
            }.onFailure { log("undock via stack $stale failed: ${it.cause ?: it}") }
        }

        /**
         * The docked stack's bounds, set the way SystemUI's divider sets them: not
         * snapped to a half, so a docked window can sit anywhere on the cluster.
         */
        fun resizeDocked(bounds: Rect): Boolean = runCatching {
            service().javaClass.getMethod(
                "resizeDockedStack", Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java,
            ).invoke(service(), bounds, null, null, null, null)
            log("docked stack -> $bounds")
            true
        }.onFailure { log("docked stack $bounds failed: ${it.cause ?: it}") }.getOrDefault(false)

        /** Moves a stack to another display; the ROM's `moveStackToDisplay` (Android 10). */
        fun moveStack(stackId: Int, displayId: Int): Boolean = runCatching {
            val service = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
                ?: error("no activity task manager")
            service.javaClass.getMethod(
                "moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(service, stackId, displayId)
            log("stack $stackId -> display $displayId")
            true
        }.onFailure { log("stack $stackId -> display $displayId failed: ${it.cause ?: it}") }
            .getOrDefault(false)

        /** Freeform tasks only: a fullscreen one refuses, which is why mode comes first. */
        fun setBounds(taskId: Int, bounds: Rect): Boolean = runCatching {
            val service = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
                ?: error("no activity task manager")
            service.javaClass.getMethod(
                "resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType,
            ).invoke(service, taskId, bounds, 0)
            log("task $taskId bounds -> $bounds")
            true
        }.onFailure { log("task $taskId bounds $bounds failed: ${it.cause ?: it}") }
            .getOrDefault(false)

        /**
         * Split-screen primary at explicit bounds. A freeform task carries the
         * decor caption (the ROM's blue bar with minimise and close); a docked one
         * does not, so this is tried first for any window smaller than the display.
         * Signature on Android 10: (taskId, createMode, toTop, animate, initialBounds, showRecents).
         */
        fun setSplitScreen(taskId: Int, bounds: Rect): Boolean = runCatching {
            undock(taskId)
            val service = service()
            val result = service.javaClass.getMethod(
                "setTaskWindowingModeSplitScreenPrimary",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Rect::class.java, Boolean::class.javaPrimitiveType,
            ).invoke(service, taskId, 0, true, false, bounds, false)
            log("task $taskId split-screen at $bounds -> $result")
            result == true
        }.onFailure { log("task $taskId split-screen $bounds failed: ${it.cause ?: it}") }
            .getOrDefault(false)

        private fun windowManager(): Any =
            Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)
                ?: error("no window manager service")

        /**
         * The display's own default windowing mode, the one a new task there
         * inherits and the one the decor consults when deciding on a caption.
         * This ROM has `IWindowManager.setWindowingMode(displayId, mode)`, which
         * the window manager persists per display.
         */
        fun displayWindowingMode(displayId: Int): Int? = runCatching {
            val service = windowManager()
            service.javaClass.getMethod("getWindowingMode", Int::class.javaPrimitiveType)
                .invoke(service, displayId) as Int
        }.onFailure { log("display $displayId windowing mode read failed: ${it.cause ?: it}") }
            .getOrNull()

        fun setDisplayWindowingMode(displayId: Int, mode: Int): Boolean = runCatching {
            val service = windowManager()
            service.javaClass.getMethod(
                "setWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(service, displayId, mode)
            log("display $displayId windowing mode -> $mode")
            true
        }.onFailure { log("display $displayId windowing mode $mode failed: ${it.cause ?: it}") }
            .getOrDefault(false)
    }

    // ── Slot ─────────────────────────────────────────────────────────────────

    /**
     * A window on the cluster display at chosen bounds, carrying a virtual
     * display of the same size. A task moved onto that virtual display is
     * fullscreen there - no caption, no docking rules - and the window shows it
     * on the dial exactly where the bounds say. DashCast's "fission slot".
     *
     * The window is not touchable: the app injects touches straight onto the
     * virtual display, offset by the slot's position. Views need the main looper,
     * so the binder thread hands the work over and waits for the surface.
     */
    private object Slot {
        private val main = Handler(Looper.getMainLooper())
        private var view: SurfaceView? = null
        private var manager: WindowManager? = null
        private var display: VirtualDisplay? = null

        @Synchronized
        fun attach(bounds: Rect): Int {
            detach()
            val context = shellContext ?: run {
                log("slot: no shell context")
                return -1
            }
            val latch = CountDownLatch(1)
            var result = -1
            main.post {
                runCatching {
                    // DisplayManager from a package context in this process has no
                    // context behind it (getDisplay and createVirtualDisplay both
                    // dereference null), so the global is used with ours passed in.
                    val globalType = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    val global = globalType.getMethod("getInstance").invoke(null)
                    val adjustmentsType = Class.forName("android.view.DisplayAdjustments")
                    val adjustments = adjustmentsType.getField("DEFAULT_DISPLAY_ADJUSTMENTS").get(null)
                    val cluster = globalType.getMethod(
                        "getCompatibleDisplay", Int::class.javaPrimitiveType, adjustmentsType,
                    ).invoke(global, 1, adjustments) as? android.view.Display
                        ?: error("no cluster display")
                    val onCluster = context.createDisplayContext(cluster)
                    val windows = onCluster.getSystemService(WindowManager::class.java)
                    val surface = SurfaceView(onCluster)
                    surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            runCatching {
                                val virtual = globalType.getMethod(
                                    "createVirtualDisplay",
                                    Context::class.java,
                                    Class.forName("android.media.projection.MediaProjection"),
                                    String::class.java,
                                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                                    Surface::class.java, Int::class.javaPrimitiveType,
                                    VirtualDisplay.Callback::class.java, Handler::class.java, String::class.java,
                                ).invoke(
                                    global, context, null, "esmerui_cluster_slot",
                                    bounds.width(), bounds.height(), SLOT_DPI, holder.surface,
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                                    null, null, null,
                                ) as VirtualDisplay
                                display = virtual
                                result = virtual.display.displayId
                                log("slot on: $bounds as display $result")
                            }.onFailure { log("slot: virtual display failed: ${Log.getStackTraceString(it)}") }
                            latch.countDown()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                    // A presentation window, the kind Presentation itself uses on a
                    // secondary display. Every alert-window type (application and
                    // system overlays included) is hidden by the window manager unless
                    // the draw-over-apps app-op allows the uid, and the shell's does
                    // not by default; a presentation window is not gated by it.
                    val params = WindowManager.LayoutParams(
                        bounds.width(), bounds.height(),
                        TYPE_PRESENTATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = bounds.left
                        y = bounds.top
                        title = "esmerui_cluster_slot"
                    }
                    try {
                        windows.addView(surface, params)
                    } catch (e: Exception) {
                        log("slot: presentation window refused (${e.message}); trying a system overlay")
                        @Suppress("DEPRECATION")
                        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                        windows.addView(surface, params)
                    }
                    view = surface
                    manager = windows
                }.onFailure {
                    log("slot: window failed: ${Log.getStackTraceString(it)}")
                    latch.countDown()
                }
            }
            latch.await(4, TimeUnit.SECONDS)
            if (result < 0) detach()
            return result
        }

        @Synchronized
        fun detach() {
            val virtual = display
            val surface = view
            val windows = manager
            display = null
            view = null
            manager = null
            if (virtual == null && surface == null) return
            val latch = CountDownLatch(1)
            main.post {
                runCatching { virtual?.release() }
                runCatching { if (surface != null) windows?.removeViewImmediate(surface) }
                log("slot off")
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
        }

        private const val SLOT_DPI = 240

        /** WindowManager.LayoutParams.TYPE_PRESENTATION, hidden from the SDK. */
        private const val TYPE_PRESENTATION = 2037
    }

    // ── Input ────────────────────────────────────────────────────────────────

    /** Injects touches and keys onto a given display; needs the shell's INJECT_EVENTS. */
    private object Input {
        private var manager: Any? = null
        private var inject: Method? = null
        private var motionDisplay: Method? = null
        private var keyDisplay: Method? = null
        private var firstLogged = false

        // The display routing setters are hidden; this process has the hidden-API
        // exemptions lifted and is the one place that may reach them.
        @android.annotation.SuppressLint("BlockedPrivateApi")
        fun init() {
            runCatching {
                val type = Class.forName("android.hardware.input.InputManager")
                manager = type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                inject = type.getDeclaredMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                motionDisplay = runCatching {
                    MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
                keyDisplay = runCatching {
                    KeyEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }.getOrNull()
                log("input ready; motion display routing=${motionDisplay != null}, key=${keyDisplay != null}")
            }.onFailure { log("input init failed: $it") }
        }

        fun inject(event: InputEvent, display: Int) {
            val target = manager ?: return
            val method = inject ?: return
            runCatching {
                when (event) {
                    is MotionEvent -> motionDisplay?.invoke(event, display)
                    is KeyEvent -> keyDisplay?.invoke(event, display)
                }
                // 0 = INJECT_INPUT_EVENT_MODE_ASYNC: never wait on the receiving app.
                val result = method.invoke(target, event, 0)
                if (!firstLogged) {
                    firstLogged = true
                    log("first injection onto display $display: $result")
                }
            }.onFailure { log("inject failed: ${it.cause ?: it}") }
        }
    }

    /** Both the redirected stdout (the log file) and logcat, so either tells the story. */
    private fun log(message: String) {
        println("[$TAG] $message")
        System.out.flush()
        Log.i(TAG, message)
    }
}
