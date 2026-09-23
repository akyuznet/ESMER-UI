package com.example.esmerui.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View

/**
 * Draws on the cluster's projection surface.
 *
 * The container service publishes the fission cluster's background as a public
 * presentation display (`fission_bg_xdjaVirtualSurface`, 1920x720, display 1):
 * whatever is presented on it is handed to the cluster OS as the projected
 * layer, which is how the OEM navigation puts its map on the dial. A
 * [Presentation] needs no permission on a public presentation display, so this
 * is the one cluster path open to an ordinary app.
 *
 * Debug-only for now: a probe that says whether the cluster shows the layer at
 * all, before anything is built on it.
 */
object ClusterCanvas {

    private const val TAG = "ClusterCanvas"

    private val main = Handler(Looper.getMainLooper())
    private var shown: Presentation? = null

    fun show(context: Context, displayId: Int, text: String, seconds: Int) {
        val app = context.applicationContext
        val manager = app.getSystemService(DisplayManager::class.java)
        val display = manager.getDisplay(displayId)
        if (display == null) {
            val have = manager.displays.joinToString { "${it.displayId}:${it.name}" }
            Log.w(TAG, "no display $displayId; have $have")
            return
        }
        main.removeCallbacksAndMessages(null)
        main.post {
            dismiss()
            runCatching {
                val presentation = Presentation(
                    app, display, android.R.style.Theme_Material_NoActionBar_Fullscreen,
                )
                presentation.setContentView(ArrowView(presentation.context, text))
                presentation.show()
                shown = presentation
                Log.i(TAG, "presenting on display $displayId (${display.name}) for $seconds s")
            }.onFailure { Log.w(TAG, "present failed: $it") }
            main.postDelayed({ dismiss() }, seconds * 1_000L)
        }
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) dismiss() else main.post { dismiss() }
    }

    private fun dismiss() {
        shown?.let { runCatching { it.dismiss() } }
        shown = null
    }

    /** A left-turn arrow and up to a few lines of text, white on black. */
    private class ArrowView(context: Context, private val text: String) : View(context) {

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 40f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 120f
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val cx = width * 0.25f
            val cy = height / 2f
            val shaft = Path().apply {
                moveTo(cx + 120f, cy + 220f)
                lineTo(cx + 120f, cy - 60f)
                lineTo(cx - 140f, cy - 60f)
            }
            canvas.drawPath(shaft, stroke)
            val head = Path().apply {
                moveTo(cx - 280f, cy - 60f)
                lineTo(cx - 110f, cy - 200f)
                lineTo(cx - 110f, cy + 80f)
                close()
            }
            canvas.drawPath(head, fill)
            var y = cy - 40f
            text.split('|').forEach { line ->
                canvas.drawText(line, width * 0.45f, y, label)
                y += 150f
            }
        }
    }
}
