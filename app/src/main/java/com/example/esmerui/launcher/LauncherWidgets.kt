package com.example.esmerui.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Hosting for Android home-screen widgets.
 *
 * A launcher does not draw widgets itself: it asks [AppWidgetHost] for a view that
 * the owning app fills in over IPC. The host must be listening for a widget to
 * update, so start and stop it with the screen - a host left listening keeps every
 * widget's app awake for updates nobody is looking at.
 *
 * Binding is the awkward part. `BIND_APPWIDGET` is signature-or-privileged, so a
 * normal app cannot hold it and each bind raises a system confirmation instead.
 * That dialog can be skipped once per install with
 * `adb shell appwidget grantbind --package com.example.esmerui`, which this unit
 * supports; [canBindWithoutPrompt] reports which path is in play.
 */
class LauncherWidgets(context: Context) {

    private val app = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
    private val host = AppWidgetHost(app, HOST_ID)

    private var listening = false

    fun startListening() {
        if (listening) return
        runCatching { host.startListening() }
            .onFailure { Log.w(TAG, "startListening failed: $it") }
        listening = true
    }

    fun stopListening() {
        if (!listening) return
        runCatching { host.stopListening() }
        listening = false
    }

    /** Every widget offered by every installed app, grouped by their owning app. */
    fun providers(): List<AppWidgetProviderInfo> =
        runCatching { manager.installedProviders }.getOrDefault(emptyList())

    fun info(appWidgetId: Int): AppWidgetProviderInfo? =
        runCatching { manager.getAppWidgetInfo(appWidgetId) }.getOrNull()

    /** An id whose provider has been uninstalled; the grid shows it as removable. */
    fun isDead(appWidgetId: Int): Boolean = info(appWidgetId) == null

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun deleteId(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }

    /**
     * Binds without user interaction where permitted, returning false when the
     * caller must fall back to [bindIntent].
     */
    fun bind(appWidgetId: Int, provider: ComponentName, profile: android.os.UserHandle? = null): Boolean =
        runCatching {
            if (profile != null) {
                manager.bindAppWidgetIdIfAllowed(appWidgetId, profile, provider, null)
            } else {
                manager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
            }
        }.getOrDefault(false)

    val canBindWithoutPrompt: Boolean
        get() = runCatching {
            // There is no direct query, so a throwaway id is bound against a real
            // provider and immediately released. Cheaper than guessing.
            val probe = providers().firstOrNull() ?: return@runCatching false
            val id = allocateId()
            val ok = bind(id, probe.provider)
            deleteId(id)
            ok
        }.getOrDefault(false)

    /** The system's own bind confirmation, for when [bind] is refused. */
    fun bindIntent(appWidgetId: Int, provider: ComponentName): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }

    /** Some widgets insist on a setup screen before they will draw anything. */
    fun configureIntent(appWidgetId: Int, info: AppWidgetProviderInfo): Intent? {
        val configure = info.configure ?: return null
        return Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configure
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
    }

    /**
     * The hosted view for a widget, sized to the box it will occupy.
     *
     * The size has to be handed over explicitly: the widget's app chooses a layout
     * from it, and without it a widget given a wide cell still draws its narrowest
     * form.
     */
    fun createView(
        themed: Context,
        appWidgetId: Int,
        widthDp: Int,
        heightDp: Int,
    ): AppWidgetHostView? {
        val info = info(appWidgetId) ?: return null
        return runCatching {
            host.createView(themed, appWidgetId, info).apply {
                setAppWidget(appWidgetId, info)
                resize(this, appWidgetId, widthDp, heightDp)
            }
        }.onFailure { Log.w(TAG, "createView($appWidgetId) failed: $it") }.getOrNull()
    }

    fun resize(view: AppWidgetHostView, appWidgetId: Int, widthDp: Int, heightDp: Int) {
        runCatching {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            }
            manager.updateAppWidgetOptions(appWidgetId, options)
            view.updateAppWidgetSize(options, widthDp, heightDp, widthDp, heightDp)
        }
    }

    /**
     * How many cells a provider wants, given the size of one cell.
     *
     * API 31 added a provider's preferred span in cells; below that the only hint is
     * a minimum in dp, which has to be divided out. Rounding up rather than down -
     * a widget given less than its minimum draws clipped or blank.
     */
    fun defaultSpan(info: AppWidgetProviderInfo, cellWidthDp: Float, cellHeightDp: Float): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val w = info.targetCellWidth
            val h = info.targetCellHeight
            if (w > 0 && h > 0) return w to h
        }
        val w = Math.ceil((info.minWidth / cellWidthDp.coerceAtLeast(1f)).toDouble()).toInt()
        val h = Math.ceil((info.minHeight / cellHeightDp.coerceAtLeast(1f)).toDouble()).toInt()
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "LauncherWidgets"

        /**
         * Must never change: the platform keys every widget this launcher hosts to
         * it, and a new value would orphan every widget already placed.
         */
        private const val HOST_ID = 0x45534d52
    }
}
