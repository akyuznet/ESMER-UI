package com.example.esmerui.cluster

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * A shell on the head unit itself, over the unit's own wireless ADB.
 *
 * Three things the cluster projection needs are closed to an ordinary app on this
 * ROM: the container's control messages (`AutoContainerService` checks the caller
 * against a package allowlist), SurfaceFlinger's mirror displays
 * (ACCESS_SURFACE_FLINGER) and input injection onto another display
 * (INJECT_EVENTS). All three are open to the shell user, and the unit keeps adbd
 * listening on port 5555 (`persist.sys.adb.wiress.enable`). So the launcher does
 * what openbyd and OverDrive do: it speaks the ADB protocol to 127.0.0.1:5555 with
 * its own RSA key, and runs what it needs as uid 2000.
 *
 * The first connection raises the unit's "Allow USB debugging?" prompt for this
 * key; once allowed with "Always allow" ticked it is silent from then on. The key
 * lives in the app's private files, so an uninstall forgets it and the prompt
 * comes back once.
 *
 * Everything here blocks on the network and must stay off the main thread.
 */
object AdbLink {

    private const val TAG = "AdbLink"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** Long enough to walk the Allow prompt, short enough that a dead unit shows. */
    private const val SOCKET_TIMEOUT_MS = 20_000

    class LinkException(message: String, val mayNeedAllow: Boolean = false) : IOException(message)

    @Volatile
    private var link: Dadb? = null
    private var keyPair: AdbKeyPair? = null

    val isConnected: Boolean get() = link != null

    /** Where the daemon's key lives, so a settings screen can say whether one exists. */
    fun hasKey(context: Context): Boolean = File(context.filesDir, "adb.key").exists()

    @Synchronized
    private fun keys(context: Context): AdbKeyPair {
        keyPair?.let { return it }
        val private = File(context.filesDir, "adb.key")
        val public = File(context.filesDir, "adb.pub")
        if (!private.exists() || !public.exists()) {
            AdbKeyPair.generate(private, public)
            Log.i(TAG, "generated a new ADB key; the head unit will ask once")
        }
        return AdbKeyPair.read(private, public).also { keyPair = it }
    }

    /**
     * The open connection, opened if there is none. The handshake is where the
     * Allow prompt happens, so a first call can take as long as the person does.
     */
    @Synchronized
    fun connect(context: Context): Dadb {
        link?.let { live ->
            if (alive(live)) return live
            close()
        }
        val fresh = try {
            Dadb.create(
                HOST, PORT, keys(context.applicationContext),
                CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS, true,
            )
        } catch (e: Exception) {
            throw classify(e)
        }
        try {
            // create() is lazy: the first command is what drives the handshake.
            val reply = fresh.shell("echo esmer-link").allOutput
            if (!reply.contains("esmer-link")) throw LinkException("unexpected reply: $reply")
        } catch (e: Exception) {
            runCatching { fresh.close() }
            throw classify(e)
        }
        link = fresh
        Log.i(TAG, "connected to the head unit's adbd")
        return fresh
    }

    /** Runs one command, returning its combined output. */
    fun shell(context: Context, command: String): String {
        val live = connect(context)
        return try {
            live.shell(command).allOutput
        } catch (e: Exception) {
            close()
            throw classify(e)
        }
    }

    private fun alive(live: Dadb): Boolean =
        runCatching { live.shell("echo ok").allOutput.contains("ok") }.getOrDefault(false)

    @Synchronized
    fun close() {
        link?.let { runCatching { it.close() } }
        link = null
    }

    private fun classify(e: Exception): LinkException {
        if (e is LinkException) return e
        Log.w(TAG, "link failed: ${e.javaClass.simpleName}: ${e.message}")
        return when (e) {
            is ConnectException -> LinkException(
                "The head unit's ADB over TCP is off (port 5555 refused).",
            )
            is SocketTimeoutException -> LinkException(
                "No answer from the head unit. If it is showing an \"Allow USB " +
                    "debugging?\" prompt, tap Allow with \"Always allow\" ticked, then try again.",
                mayNeedAllow = true,
            )
            else -> {
                val auth = e.javaClass.simpleName.contains("Auth", ignoreCase = true)
                LinkException(
                    if (auth) {
                        "The head unit refused this launcher's ADB key. Allow it on the " +
                            "unit's prompt, or revoke old authorisations in developer options."
                    } else {
                        "Head unit link failed: ${e.message ?: e.javaClass.simpleName}"
                    },
                    mayNeedAllow = auth,
                )
            }
        }
    }
}
