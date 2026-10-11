package com.example.esmerui.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.esmerui.R
import com.example.esmerui.launcher.LauncherSettings
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * The floating mirror: a window over whatever is on the head unit that shows the
 * cluster live and takes touches for it, so the app on the dial can be driven
 * without leaving the app on the screen. Drag it by its handle bar, resize it
 * by the grip in its corner; Back goes to the cluster app, X hides the window.
 * Where it was last left is remembered.
 *
 * An application overlay window, so it needs no accessibility service and
 * survives the launcher activity going away; it sits under the launcher's own
 * bars, which are accessibility overlays, and can be dragged out from beneath
 * them. Plain views rather than Compose: the mirror is a TextureView either
 * way, and a view tree is cheap to keep in a service.
 */
object ClusterMirrorWindow {

    private const val TAG = "ClusterMirrorWindow"

    /** The 1920x720 cluster, under a handle bar of this height. */
    private const val HANDLE_DP = 36
    private const val MIN_WIDTH_DP = 240
    private const val DEFAULT_WIDTH_FRACTION = 0.42f

    var isShown by mutableStateOf(false)
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var surface: Surface? = null

    fun toggle(context: Context) {
        if (isShown) hide() else show(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (root != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density
        val screen = app.resources.displayMetrics
        val handlePx = (HANDLE_DP * density).roundToInt()

        val remembered = LauncherSettings.get(app).clusterFloatRect
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 }
        val width = remembered?.get(2) ?: (screen.widthPixels * DEFAULT_WIDTH_FRACTION).roundToInt()
        val height = remembered?.get(3) ?: (heightFor(width, handlePx))
        val x = remembered?.get(0) ?: (screen.widthPixels - width - (16 * density).roundToInt())
        val y = remembered?.get(1) ?: (screen.heightPixels - height - (120 * density).roundToInt())

        val layout = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        val frame = FrameLayout(app).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 12 * density
                setStroke((1.5f * density).roundToInt(), Color.argb(160, 255, 255, 255))
            }
            clipToOutline = true
        }

        // The mirror itself, below the handle bar.
        val texture = TextureView(app).apply {
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(texture).also {
                        ClusterProjection.attachPreview(ClusterProjection.SLOT_FLOAT, it, w, h)
                    }
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                    val current = surface ?: Surface(texture).also { surface = it }
                    ClusterProjection.attachPreview(ClusterProjection.SLOT_FLOAT, current, w, h)
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    ClusterProjection.detachPreview(ClusterProjection.SLOT_FLOAT)
                    surface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            setOnTouchListener { view, event ->
                ClusterProjection.forwardTouch(event, view.width, view.height)
                true
            }
        }
        frame.addView(
            texture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = handlePx },
        )

        // The handle bar: drag anywhere on it, Back and hide at its end.
        val handle = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(230, 30, 30, 34))
            setPadding((8 * density).roundToInt(), 0, (4 * density).roundToInt(), 0)
        }
        handle.addView(
            ImageView(app).apply {
                setImageResource(R.drawable.ic_drag)
                setColorFilter(Color.WHITE)
            },
            LinearLayout.LayoutParams((24 * density).roundToInt(), (24 * density).roundToInt()),
        )
        handle.addView(
            TextView(app).apply {
                text = "Cluster"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding((8 * density).roundToInt(), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        handle.addView(
            iconButton(app, R.drawable.ic_arrow_back, density) {
                ClusterProjection.sendKey(KeyEvent.KEYCODE_BACK)
            },
        )
        handle.addView(iconButton(app, R.drawable.ic_close, density) { hide() })
        handle.setOnTouchListener(DragListener(app, layout, manager))
        frame.addView(
            handle,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, handlePx),
        )

        // The resize grip in the bottom-right corner.
        val gripPx = (40 * density).roundToInt()
        val grip = ImageView(app).apply {
            setImageResource(R.drawable.ic_resize)
            setColorFilter(Color.argb(220, 255, 255, 255))
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setPadding((8 * density).roundToInt(), (8 * density).roundToInt(), (8 * density).roundToInt(), (8 * density).roundToInt())
            setOnTouchListener(ResizeListener(app, layout, manager, handlePx, density))
        }
        frame.addView(
            grip,
            FrameLayout.LayoutParams(gripPx, gripPx, Gravity.BOTTOM or Gravity.END),
        )

        runCatching { manager.addView(frame, layout) }
            .onFailure {
                Log.w(TAG, "could not show the floating mirror: $it")
                return
            }
        windowManager = manager
        root = frame
        params = layout
        isShown = true
        listeners.forEach { it() }
    }

    fun hide() {
        val frame = root ?: return
        runCatching { windowManager?.removeView(frame) }
        root = null
        params = null
        windowManager = null
        isShown = false
        listeners.forEach { it() }
    }

    private fun heightFor(width: Int, handlePx: Int): Int =
        (width * ClusterProjection.CLUSTER_HEIGHT / ClusterProjection.CLUSTER_WIDTH) + handlePx

    private fun iconButton(context: Context, icon: Int, density: Float, onClick: () -> Unit): View =
        ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((6 * density).roundToInt(), 0, (6 * density).roundToInt(), 0)
            layoutParams = LinearLayout.LayoutParams((36 * density).roundToInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }

    private fun remember(context: Context, layout: WindowManager.LayoutParams) {
        LauncherSettings.get(context).clusterFloatRect =
            "${layout.x},${layout.y},${layout.width},${layout.height}"
    }

    /** Moves the window with the finger; a tap without movement is left to the buttons. */
    private class DragListener(
        private val context: Context,
        private val layout: WindowManager.LayoutParams,
        private val manager: WindowManager,
    ) : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x; startY = layout.y
                    touchX = event.rawX; touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - touchX).roundToInt()
                    layout.y = startY + (event.rawY - touchY).roundToInt()
                    runCatching { manager.updateViewLayout(view.parent as View, layout) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> remember(context, layout)
            }
            return true
        }
    }

    /** Resizes from the corner, keeping the mirror at the cluster's own shape. */
    private class ResizeListener(
        private val context: Context,
        private val layout: WindowManager.LayoutParams,
        private val manager: WindowManager,
        private val handlePx: Int,
        private val density: Float,
    ) : View.OnTouchListener {
        private var startWidth = 0
        private var touchX = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = layout.width
                    touchX = event.rawX
                }
                MotionEvent.ACTION_MOVE -> {
                    val width = (startWidth + (event.rawX - touchX).roundToInt())
                        .coerceAtLeast((MIN_WIDTH_DP * density).roundToInt())
                    layout.width = width
                    layout.height = heightFor(width, handlePx)
                    runCatching { manager.updateViewLayout(view.parent as View, layout) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> remember(context, layout)
            }
            return true
        }
    }
}
