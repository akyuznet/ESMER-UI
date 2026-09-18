package com.example.esmerui.ui

import android.content.ComponentName
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.esmerui.R
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.WindowMode
import com.example.esmerui.launcher.window.FreeformWindow
import com.example.esmerui.launcher.window.SystemDisplayWindow
import com.example.esmerui.launcher.window.VirtualDisplayHost
import com.example.esmerui.launcher.window.WindowResult
import kotlin.math.roundToInt

/**
 * The floating app window.
 *
 * Laid out as a fraction of its parent so the size settings mean the same thing on
 * any head unit. What goes inside depends on which backend actually worked:
 * a live surface for virtual-display mode, a status panel for freeform (where the
 * system draws the real window above us at the same bounds).
 */
@Composable
fun AppWindow(
    component: ComponentName,
    appLabel: String,
    settings: LauncherSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentW = maxWidth
        val parentH = maxHeight

        // Percentages are the persisted form, but a drag needs sub-percent precision
        // or slow drags round to a standstill. Track floats locally, commit on release.
        var widthPct by remember { mutableFloatStateOf(settings.windowWidthPercent.toFloat()) }
        var heightPct by remember { mutableFloatStateOf(settings.windowHeightPercent.toFloat()) }
        var xPct by remember { mutableFloatStateOf(settings.windowXPercent.toFloat()) }
        var yPct by remember { mutableFloatStateOf(settings.windowYPercent.toFloat()) }

        // Pick up edits made on the settings screen while the window was hidden.
        LaunchedEffect(settings.windowWidthPercent, settings.windowHeightPercent) {
            widthPct = settings.windowWidthPercent.toFloat()
            heightPct = settings.windowHeightPercent.toFloat()
        }

        // Bumped only when a drag or resize ends, so the hosted app is relaunched
        // once at the new bounds instead of on every frame of the gesture.
        var boundsVersion by remember { mutableIntStateOf(0) }

        val windowW = parentW * (widthPct / 100f)
        val windowH = parentH * (heightPct / 100f)

        val maxX = (parentW - windowW).coerceAtLeast(0.dp)
        val maxY = (parentH - windowH).coerceAtLeast(0.dp)
        val offsetX = maxX * (xPct / 100f)
        val offsetY = maxY * (yPct / 100f)

        Column(
            modifier = Modifier
                .offset(x = offsetX, y = offsetY)
                .size(width = windowW, height = windowH)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(16.dp),
                )
        ) {
            WindowTitleBar(
                title = appLabel,
                onClose = onClose,
                onDrag = { dx, dy ->
                    if (maxX > 0.dp) {
                        xPct = (xPct + with(density) { dx.toDp() } / maxX * 100f)
                            .coerceIn(0f, 100f)
                    }
                    if (maxY > 0.dp) {
                        yPct = (yPct + with(density) { dy.toDp() } / maxY * 100f)
                            .coerceIn(0f, 100f)
                    }
                },
                onDragEnd = {
                    settings.windowXPercent = xPct.roundToInt()
                    settings.windowYPercent = yPct.roundToInt()
                    boundsVersion++
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                WindowContent(
                    component = component,
                    appLabel = appLabel,
                    mode = settings.windowMode,
                    boundsVersion = boundsVersion,
                    widthPx = with(density) { windowW.roundToPx() },
                    heightPx = with(density) { windowH.roundToPx() },
                    originX = with(density) { offsetX.roundToPx() },
                    originY = with(density) { offsetY.roundToPx() },
                )

                ResizeHandle(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onResize = { dx, dy ->
                        val min = LauncherSettings.WINDOW_SIZE_RANGE.first.toFloat()
                        val max = LauncherSettings.WINDOW_SIZE_RANGE.last.toFloat()
                        widthPct = (widthPct + with(density) { dx.toDp() } / parentW * 100f)
                            .coerceIn(min, max)
                        heightPct = (heightPct + with(density) { dy.toDp() } / parentH * 100f)
                            .coerceIn(min, max)
                    },
                    onResizeEnd = {
                        settings.windowWidthPercent = widthPct.roundToInt()
                        settings.windowHeightPercent = heightPct.roundToInt()
                        boundsVersion++
                    },
                )
            }
        }
    }
}

@Composable
private fun WindowTitleBar(
    title: String,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_drag),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Close window",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WindowContent(
    component: ComponentName,
    appLabel: String,
    mode: WindowMode,
    boundsVersion: Int,
    widthPx: Int,
    heightPx: Int,
    originX: Int,
    originY: Int,
) {
    val context = LocalContext.current
    var result by remember(component, mode) { mutableStateOf<WindowResult?>(null) }

    // Freeform is system-drawn, so it needs no surface of ours - just a launch
    // with the bounds this window currently occupies.
    val freeformSupported = remember(context) { FreeformWindow.isSupported(context) }
    val useFreeform = mode == WindowMode.FREEFORM ||
        (mode == WindowMode.AUTO && freeformSupported)

    if (useFreeform) {
        // Keyed on boundsVersion, not the live bounds, so a drag does not relaunch
        // the hosted app on every frame.
        LaunchedEffect(component, boundsVersion) {
            val bounds = Rect(originX, originY, originX + widthPx, originY + heightPx)
            result = FreeformWindow.launch(context, component, bounds)
        }
    }

    if (mode == WindowMode.SYSTEM_DISPLAY) {
        LaunchedEffect(component, boundsVersion) {
            result = SystemDisplayWindow.launch(context, component)
        }
    }

    val host = remember { VirtualDisplayHost(context) }
    val needsSurface = mode != WindowMode.SYSTEM_DISPLAY &&
        (!useFreeform || (mode == WindowMode.AUTO && result is WindowResult.Failure))

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (needsSurface) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                result = host.start(
                                    surface = holder.surface,
                                    widthPx = width,
                                    heightPx = height,
                                    densityDpi = resources.displayMetrics.densityDpi,
                                    component = component,
                                )
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                w: Int,
                                h: Int,
                            ) {
                                host.resize(w, h, resources.displayMetrics.densityDpi)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                host.release()
                            }
                        })
                        setOnTouchListener { _, event -> host.forwardTouch(event) }
                    }
                },
            )
            DisposableEffect(Unit) { onDispose { host.release() } }
        }

        when (val outcome = result) {
            is WindowResult.Failure -> WindowMessage(
                title = "Cannot window $appLabel",
                body = outcome.reason,
                remedy = outcome.remedy,
            )

            is WindowResult.Success -> if (outcome.mode == WindowMode.FREEFORM) {
                WindowMessage(
                    title = "$appLabel is running in a system window",
                    body = "Freeform windows are drawn by the system above the " +
                        "launcher, at this window's bounds.",
                    remedy = null,
                )
            } else if (outcome.mode == WindowMode.SYSTEM_DISPLAY) {
                WindowMessage(
                    title = "$appLabel is on the head unit's display",
                    body = "BYD's container service owns that window, so its size " +
                        "and position are set by the head unit, not here.",
                    remedy = null,
                )
            } else if (!outcome.interactive) {
                ViewOnlyBadge()
            }

            null -> Unit
        }
    }
}

/** Shown when the app renders but cannot receive our touches. */
@Composable
private fun ViewOnlyBadge() {
    Box(
        Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = "View only - touch needs INJECT_EVENTS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WindowMessage(title: String, body: String, remedy: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (remedy != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = remedy,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onResizeEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onResize(dragAmount.x, dragAmount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_resize),
            contentDescription = "Resize window",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
