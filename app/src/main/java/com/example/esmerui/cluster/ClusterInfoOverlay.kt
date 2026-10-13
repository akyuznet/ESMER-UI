package com.example.esmerui.cluster

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.esmerui.car.CarInfo
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.NavAction
import com.example.esmerui.navbar.NavActionIcons
import com.example.esmerui.navbar.NavPalette
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * The launcher's readouts drawn on the instrument cluster: a clock, the gear,
 * tyre pressures, range - whichever the user picked - each at its own place
 * and size, beside the app being projected.
 *
 * It is a [Presentation] on the cluster's projection display, so it needs no
 * permission and no helper: the cluster composites it like the app, under its
 * own top bar, warning lamps and readouts and over its background. The window
 * covers the display but is transparent and takes no touches, so the app under
 * it keeps every tap the mirror sends. It is visible on the dial only while
 * the cluster is projecting, but it is kept up all the time so that nothing
 * has to be rebuilt when a projection starts.
 *
 * Values come from [CarInfo] the same way the bars' do; whatever is picked here
 * joins the set the bar service polls for.
 */
object ClusterInfoOverlay {

    private const val TAG = "ClusterInfoOverlay"

    /** One readout's size at 100 %, in cluster pixels. */
    const val CHIP_WIDTH = 420
    const val CHIP_HEIGHT = 120

    /** Size as a percentage of that. */
    const val MIN_SIZE = 25
    const val MAX_SIZE = 400
    const val DEFAULT_SIZE = 100

    /**
     * The readouts that make sense on the dial. The radios are the head unit's,
     * and the indicators are already the cluster's own lamps.
     */
    val choices: List<NavAction> = listOf(
        NavAction.INFO_CLOCK_DATE, NavAction.INFO_CLOCK, NavAction.INFO_DATE,
        NavAction.INFO_GEAR, NavAction.INFO_SPEED, NavAction.INFO_LIGHTS,
        NavAction.INFO_TYRES, NavAction.INFO_TYRES_PSI, NavAction.INFO_TYRES_KPA,
        NavAction.INFO_RANGE_EV, NavAction.INFO_RANGE_FUEL,
        NavAction.INFO_TEMP_IN, NavAction.INFO_TEMP_OUT,
        NavAction.INFO_PM25, NavAction.INFO_SLOPE,
    )

    /** Where one readout sits (its top-left corner, in cluster pixels) and how big it is. */
    data class Placement(val action: NavAction, val x: Int, val y: Int, val size: Int) {
        val width: Int get() = CHIP_WIDTH * size / 100
        val height: Int get() = CHIP_HEIGHT * size / 100
        val rect: Rect get() = Rect(x, y, x + width, y + height)

        /** The same readout, kept on the cluster. */
        fun clamped(): Placement {
            val s = size.coerceIn(MIN_SIZE, MAX_SIZE)
            val w = CHIP_WIDTH * s / 100
            val h = CHIP_HEIGHT * s / 100
            return Placement(
                action,
                x.coerceIn(0, (ClusterGeometry.WIDTH - w).coerceAtLeast(0)),
                y.coerceIn(0, (ClusterGeometry.HEIGHT - h).coerceAtLeast(0)),
                s,
            )
        }
    }

    private val DEFAULT_ITEMS = listOf(NavAction.INFO_CLOCK_DATE)

    /** Down the left, beside the dial's telltales and clear of its top bar. */
    private const val DEFAULT_LEFT = 40
    private const val DEFAULT_TOP = 170

    var isShown by mutableStateOf(false)
        private set

    /** Called on the main thread when the set of readouts to poll for changes. */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var announced: Set<NavAction> = emptySet()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private val main = Handler(Looper.getMainLooper())
    private var presentation: Presentation? = null
    private var view: InfoView? = null
    private var watching = false
    private var app: Context? = null

    /** A move not yet applied; a drag sends many a second and one per frame is plenty. */
    private var pending: Placement? = null
    private val flush = Runnable {
        val moved = pending ?: return@Runnable
        pending = null
        app?.let { apply(it, moved) }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /** The picked readouts in order; the clock and date until anything is picked. */
    fun items(settings: LauncherSettings): List<NavAction> {
        val saved = settings.clusterInfoItems ?: return DEFAULT_ITEMS
        return saved.split(',').mapNotNull { name ->
            choices.firstOrNull { it.name == name.trim() }
        }
    }

    /**
     * Every picked readout with its place and size. One never placed by hand
     * takes the next spot down the left.
     */
    fun placements(settings: LauncherSettings): List<Placement> {
        val saved = parse(settings.clusterInfoPlaces)
        return items(settings).mapIndexed { index, action ->
            saved[action]?.let { Placement(action, it[0], it[1], it[2]) }
                ?: Placement(action, DEFAULT_LEFT, DEFAULT_TOP + index * CHIP_HEIGHT, DEFAULT_SIZE)
        }.map { it.clamped() }
    }

    /** `NAME:x,y,size;NAME:x,y,size` - only what the user has set by hand. */
    private fun parse(saved: String?): Map<NavAction, List<Int>> =
        saved.orEmpty().split(';').mapNotNull { entry ->
            val name = entry.substringBefore(':').trim()
            val numbers = entry.substringAfter(':', "").split(',').mapNotNull { it.trim().toIntOrNull() }
            val action = choices.firstOrNull { it.name == name } ?: return@mapNotNull null
            if (numbers.size != 3) null else action to numbers
        }.toMap()

    private fun save(settings: LauncherSettings, places: Map<NavAction, List<Int>>) {
        settings.clusterInfoPlaces = places.entries
            .joinToString(";") { (action, n) -> "${action.name}:${n[0]},${n[1]},${n[2]}" }
            .ifEmpty { null }
    }

    private fun current(settings: LauncherSettings, action: NavAction): Placement =
        placements(settings).firstOrNull { it.action == action }
            ?: Placement(action, DEFAULT_LEFT, DEFAULT_TOP, DEFAULT_SIZE)

    /** Puts a readout's top-left corner at [x],[y] and keeps it there. */
    fun place(context: Context, action: NavAction, x: Int, y: Int) {
        val settings = LauncherSettings.get(context)
        val was = current(settings, action)
        val next = Placement(action, x, y, was.size).clamped()
        save(settings, parse(settings.clusterInfoPlaces) + (action to listOf(next.x, next.y, next.size)))
        sync(context)
    }

    /** Sets a readout's size, as a percentage of its natural size. */
    fun resize(context: Context, action: NavAction, size: Int) {
        val settings = LauncherSettings.get(context)
        val was = current(settings, action)
        val next = Placement(action, was.x, was.y, size).clamped()
        save(settings, parse(settings.clusterInfoPlaces) + (action to listOf(next.x, next.y, next.size)))
        sync(context)
    }

    /** Forgets every hand-set place and size. */
    fun resetPlaces(context: Context) {
        LauncherSettings.get(context).clusterInfoPlaces = null
        sync(context)
    }

    /** What the bar service should poll for on the overlay's behalf. */
    fun wanted(context: Context): Set<NavAction> {
        val settings = LauncherSettings.get(context)
        return if (settings.clusterInfoEnabled) items(settings).toSet() else emptySet()
    }

    // ── Showing ──────────────────────────────────────────────────────────────

    /** Brings the overlay in line with the settings: up, moved, refilled or down. */
    fun sync(context: Context) {
        val app = context.applicationContext.also { app = it }
        main.removeCallbacks(flush)
        pending = null
        main.post { apply(app, null) }
    }

    /** Moves one readout to [x],[y] without saving, so a drag on the preview shows live. */
    fun preview(context: Context, action: NavAction, x: Int, y: Int) {
        app = context.applicationContext
        pending = Placement(action, x, y, DEFAULT_SIZE)
        main.removeCallbacks(flush)
        main.post(flush)
    }

    private fun announce(wanted: Set<NavAction>) {
        if (wanted == announced) return
        announced = wanted
        listeners.forEach { it() }
    }

    private fun apply(app: Context, moved: Placement?) {
        watch(app)
        val settings = LauncherSettings.get(app)
        val display = app.getSystemService(DisplayManager::class.java)
            ?.getDisplay(ClusterProjection.CLUSTER_DISPLAY_ID)
        if (!settings.clusterInfoEnabled || display == null) {
            dismiss()
            announce(emptySet())
            return
        }
        val placements = placements(settings).map {
            if (moved != null && it.action == moved.action) it.copy(x = moved.x, y = moved.y).clamped() else it
        }
        val current = presentation
        if (current == null || !current.isShowing || current.display.displayId != display.displayId) {
            dismiss()
            show(app, display)
        }
        view?.set(placements)
        announce(placements.map { it.action }.toSet())
    }

    private fun show(app: Context, display: Display) {
        runCatching {
            val shown = Presentation(app, display, android.R.style.Theme_Translucent_NoTitleBar)
            shown.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                )
            }
            val info = InfoView(shown.context)
            val holder = FrameLayout(shown.context).apply {
                addView(
                    info,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            shown.setContentView(holder)
            shown.show()
            presentation = shown
            view = info
            isShown = true
            Log.i(TAG, "shown on display ${display.displayId}")
        }.onFailure { Log.w(TAG, "could not show: $it") }
    }

    private fun dismiss() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        view = null
        isShown = false
    }

    /** The cluster display comes and goes with the container; follow it. */
    private fun watch(app: Context) {
        if (watching) return
        watching = true
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    if (displayId == ClusterProjection.CLUSTER_DISPLAY_ID) sync(app)
                }

                override fun onDisplayRemoved(displayId: Int) {
                    if (displayId == ClusterProjection.CLUSTER_DISPLAY_ID) sync(app)
                }

                override fun onDisplayChanged(displayId: Int) = Unit
            },
            main,
        )
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /**
     * The readouts, each drawn in a [CHIP_WIDTH] x [CHIP_HEIGHT] box at its own
     * place and scale. Repainted once a second: the clock needs that, and the
     * car's values arrive about as often.
     */
    private class InfoView(context: Context) : View(context) {

        private var placements: List<Placement> = emptyList()
        private val icons = HashMap<Int, Drawable>()

        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(10f, 0f, 0f, Color.argb(220, 0, 0, 0))
        }
        private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setShadowLayer(8f, 0f, 0f, Color.argb(220, 0, 0, 0))
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = 40f
            setShadowLayer(8f, 0f, 0f, Color.argb(220, 0, 0, 0))
        }
        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, 1_000L)
            }
        }

        fun set(placements: List<Placement>) {
            if (this.placements == placements) return
            this.placements = placements
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(tick)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            placements.forEach { p ->
                canvas.save()
                canvas.translate(p.x.toFloat(), p.y.toFloat())
                val scale = p.size / 100f
                canvas.scale(scale, scale)
                drawChip(canvas, p.action)
                canvas.restore()
            }
        }

        private fun drawChip(canvas: Canvas, action: NavAction) {
            when (action) {
                NavAction.INFO_TYRES, NavAction.INFO_TYRES_PSI, NavAction.INFO_TYRES_KPA ->
                    drawTyres(canvas, action)
                else -> drawValue(canvas, action)
            }
        }

        private fun drawValue(canvas: Canvas, action: NavAction) {
            val colour = NavPalette.infoColour(action)
            var x = 12f
            // The same rule as the bars: the clock, date, gear and speed read as
            // what they are and carry no glyph.
            if (NavActionIcons.onBar(action) != 0) {
                icon(NavActionIcons.current(action))?.let { glyph ->
                    glyph.setTint(colour)
                    glyph.setBounds(x.toInt(), 24, x.toInt() + 72, 96)
                    glyph.draw(canvas)
                    x += 84f
                }
            }
            val text = CarInfo.valueFor(action)
            val under = CarInfo.captionFor(action)
            val emphasised = action == NavAction.INFO_CLOCK || action == NavAction.INFO_DATE ||
                action == NavAction.INFO_CLOCK_DATE || action == NavAction.INFO_GEAR ||
                action == NavAction.INFO_SPEED
            value.color = colour
            value.textSize = if (emphasised) 66f else 54f
            caption.color = NavPalette.CAPTION
            caption.textSize = 32f
            if (text.isEmpty()) return
            if (under == null) {
                canvas.drawText(text, x, 60f + value.textSize * 0.36f, value)
            } else {
                canvas.drawText(text, x, 66f, value)
                canvas.drawText(under, x, 106f, caption)
            }
        }

        /** The four pressures where the wheels are, around a tyre in the middle. */
        private fun drawTyres(canvas: Canvas, action: NavAction) {
            val kpa = CarInfo.tyrePressures
            icon(NavActionIcons.of(action))?.let { glyph ->
                glyph.setTint(NavPalette.ICON)
                glyph.setBounds(178, 28, 242, 92)
                glyph.draw(canvas)
            }
            // Index order is the car's: front left, front right, rear left, rear right.
            val spots = listOf(110f to 54f, 310f to 54f, 110f to 104f, 310f to 104f)
            spots.forEachIndexed { index, (cx, baseline) ->
                val reading = kpa?.getOrNull(index)?.takeIf { it > 0 }
                val text = when {
                    reading == null -> CarInfo.NO_VALUE
                    action == NavAction.INFO_TYRES_PSI -> (reading / KPA_PER_PSI).roundToInt().toString()
                    action == NavAction.INFO_TYRES_KPA -> reading.toString()
                    else -> String.format(Locale.US, "%.1f", reading / KPA_PER_BAR)
                }
                corner.color = NavPalette.tyreColour(reading)
                canvas.drawText(text, cx, baseline, corner)
            }
        }

        private fun icon(res: Int): Drawable? {
            icons[res]?.let { return it }
            val loaded = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
            icons[res] = loaded
            return loaded
        }

        private companion object {
            const val KPA_PER_PSI = 6.894757f
            const val KPA_PER_BAR = 100f
        }
    }
}
