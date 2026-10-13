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
import android.view.Gravity
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
 * tyre pressures, range - whichever the user picked - at a place and size of
 * their choosing, beside the app being projected.
 *
 * It is a [Presentation] on the cluster's projection display, so it needs no
 * permission and no helper: the cluster composites it like the app, under its
 * own top bar, warning lamps and readouts and over its background. The window
 * is transparent and takes no touches, so the app under it keeps every tap the
 * mirror sends. It is visible on the dial only while the cluster is projecting,
 * but it is kept up all the time so that nothing has to be rebuilt when a
 * projection starts.
 *
 * Values come from [CarInfo] the same way the bars' do; whatever is picked here
 * joins the set the bar service polls for.
 */
object ClusterInfoOverlay {

    private const val TAG = "ClusterInfoOverlay"

    /** One readout's natural size, in cluster pixels, before scaling. */
    const val CHIP_WIDTH = 420
    const val CHIP_HEIGHT = 120
    const val MIN_WIDTH = 320

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

    enum class Layout(val label: String) {
        COLUMN("Column"), ROW("Row");

        companion object {
            fun from(name: String?): Layout = entries.firstOrNull { it.name == name } ?: COLUMN
        }
    }

    private val DEFAULT_ITEMS = listOf(NavAction.INFO_CLOCK_DATE)

    /** Beside the dial's left telltales, clear of its top bar. */
    private const val DEFAULT_LEFT = 40
    private const val DEFAULT_TOP = 170

    var isShown by mutableStateOf(false)
        private set

    /** Called on the main thread after every change to the overlay. */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private val main = Handler(Looper.getMainLooper())
    private var presentation: Presentation? = null
    private var view: InfoView? = null
    private var watching = false

    // ── Settings ─────────────────────────────────────────────────────────────

    /** The picked readouts in order; the clock and date until anything is picked. */
    fun items(settings: LauncherSettings): List<NavAction> {
        val saved = settings.clusterInfoItems ?: return DEFAULT_ITEMS
        return saved.split(',').mapNotNull { name ->
            choices.firstOrNull { it.name == name.trim() }
        }
    }

    fun layout(settings: LauncherSettings): Layout = Layout.from(settings.clusterInfoLayout)

    /** Where the overlay sits on the cluster, sized to what it shows. */
    fun rect(settings: LauncherSettings): Rect {
        val saved = settings.clusterInfoRect?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }
        val raw = if (saved != null) {
            Rect(saved[0], saved[1], saved[2], saved[3])
        } else {
            Rect(DEFAULT_LEFT, DEFAULT_TOP, DEFAULT_LEFT + CHIP_WIDTH, DEFAULT_TOP + CHIP_HEIGHT)
        }
        return fit(raw, items(settings).size, layout(settings))
    }

    fun defaultRect(settings: LauncherSettings): Rect = fit(
        Rect(DEFAULT_LEFT, DEFAULT_TOP, DEFAULT_LEFT + CHIP_WIDTH, DEFAULT_TOP + CHIP_HEIGHT),
        items(settings).size, layout(settings),
    )

    /**
     * A frame's left, top and width are the user's; its height follows what it
     * holds, and the whole stays on the cluster. The width is what sets the size
     * of the readouts.
     */
    fun fit(frame: Rect, count: Int, layout: Layout): Rect {
        val width = frame.width().coerceIn(MIN_WIDTH, ClusterGeometry.WIDTH)
        val height = heightFor(width, count, layout).coerceAtMost(ClusterGeometry.HEIGHT)
        val left = frame.left.coerceIn(0, ClusterGeometry.WIDTH - width)
        val top = frame.top.coerceIn(0, ClusterGeometry.HEIGHT - height)
        return Rect(left, top, left + width, top + height)
    }

    fun heightFor(width: Int, count: Int, layout: Layout): Int {
        val n = count.coerceAtLeast(1)
        val scale = when (layout) {
            Layout.COLUMN -> width.toFloat() / CHIP_WIDTH
            Layout.ROW -> width.toFloat() / (CHIP_WIDTH * n)
        }
        val rows = if (layout == Layout.COLUMN) n else 1
        return (CHIP_HEIGHT * rows * scale).roundToInt().coerceAtLeast(1)
    }

    /** What the bar service should poll for on the overlay's behalf. */
    fun wanted(context: Context): Set<NavAction> {
        val settings = LauncherSettings.get(context)
        return if (settings.clusterInfoEnabled) items(settings).toSet() else emptySet()
    }

    // ── Showing ──────────────────────────────────────────────────────────────

    /** Brings the overlay in line with the settings: up, moved, refilled or down. */
    fun sync(context: Context) {
        val app = context.applicationContext
        main.post { apply(app, null) }
    }

    /** Moves it to [frame] without saving, so a drag on the preview shows live. */
    fun preview(context: Context, frame: Rect) {
        val app = context.applicationContext
        main.post { apply(app, frame) }
    }

    private fun apply(app: Context, override: Rect?) {
        watch(app)
        val settings = LauncherSettings.get(app)
        val display = app.getSystemService(DisplayManager::class.java)
            ?.getDisplay(ClusterProjection.CLUSTER_DISPLAY_ID)
        if (!settings.clusterInfoEnabled || display == null) {
            dismiss()
            listeners.forEach { it() }
            return
        }
        val items = items(settings)
        val layout = layout(settings)
        val rect = override?.let { fit(it, items.size, layout) } ?: rect(settings)
        val current = presentation
        if (current == null || !current.isShowing || current.display.displayId != display.displayId) {
            dismiss()
            show(app, display)
        }
        view?.set(items, layout)
        place(rect)
        listeners.forEach { it() }
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
                    FrameLayout.LayoutParams(CHIP_WIDTH, CHIP_HEIGHT, Gravity.TOP or Gravity.START),
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

    private fun place(rect: Rect) {
        val target = view ?: return
        val params = target.layoutParams as FrameLayout.LayoutParams
        if (params.width == rect.width() && params.height == rect.height() &&
            params.leftMargin == rect.left && params.topMargin == rect.top
        ) {
            return
        }
        params.width = rect.width()
        params.height = rect.height()
        params.leftMargin = rect.left
        params.topMargin = rect.top
        target.layoutParams = params
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
     * The readouts, each drawn in a [CHIP_WIDTH] x [CHIP_HEIGHT] box and scaled
     * to the view, stacked down or set in a row. Repainted once a second: the
     * clock needs that, and the car's values arrive about as often.
     */
    private class InfoView(context: Context) : View(context) {

        private var items: List<NavAction> = emptyList()
        private var layout = Layout.COLUMN
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

        fun set(items: List<NavAction>, layout: Layout) {
            if (this.items == items && this.layout == layout) return
            this.items = items
            this.layout = layout
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
            if (items.isEmpty() || width == 0) return
            val n = items.size
            val scale = when (layout) {
                Layout.COLUMN -> width.toFloat() / CHIP_WIDTH
                Layout.ROW -> width.toFloat() / (CHIP_WIDTH * n)
            }
            items.forEachIndexed { index, action ->
                canvas.save()
                when (layout) {
                    Layout.COLUMN -> canvas.translate(0f, index * CHIP_HEIGHT * scale)
                    Layout.ROW -> canvas.translate(index * CHIP_WIDTH * scale, 0f)
                }
                canvas.scale(scale, scale)
                drawChip(canvas, action)
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
