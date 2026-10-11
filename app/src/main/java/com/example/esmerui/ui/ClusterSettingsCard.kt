package com.example.esmerui.ui

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.esmerui.cluster.ClusterGeometry
import com.example.esmerui.cluster.ClusterMirrorWindow
import com.example.esmerui.cluster.ClusterPlacement
import com.example.esmerui.cluster.ClusterProjection
import com.example.esmerui.cluster.MirrorScaling
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.findByKey
import kotlin.math.roundToInt

/**
 * The Cluster tab: pick an app, cast it to the instrument cluster, place its
 * window on the dial, and drive it from a live preview here or from the
 * floating mirror that follows you into other apps.
 *
 * The preview is a TextureView the shell-side helper mirrors the cluster into;
 * its touches go back the same way. It sits inside the settings scroll, so a
 * swipe on it is claimed for the cluster app rather than for scrolling. In
 * resize mode the preview instead carries a frame that stands for the cast
 * window's bounds, dragged and stretched by hand.
 */
@Composable
internal fun ClusterSettingsCard(settings: LauncherSettings, apps: List<AppEntry>) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val selected = remember(settings.clusterComponent, apps) {
        apps.findByKey(settings.clusterComponent)
    }
    val phase = ClusterProjection.phase
    val busy = phase == ClusterProjection.Phase.BUSY
    val projecting = phase == ClusterProjection.Phase.PROJECTING

    // The frame being dragged in resize mode, in cluster pixels; null when not resizing.
    var draft by remember { mutableStateOf<Rect?>(null) }
    val placement = ClusterPlacement.from(settings.clusterPlacement)
    val scaling = MirrorScaling.from(settings.clusterScaling)

    SettingsCard(title = "Projection to cluster") {
        Text(
            text = "Casts an app onto the instrument cluster behind the wheel, and shows " +
                "a live mirror of it that you can tap and swipe to control it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !busy) { showPicker = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "App on the cluster",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = selected?.label ?: "Not selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Change",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { selected?.let { ClusterProjection.start(context, it.component) } },
                enabled = selected != null && !busy,
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (projecting) "Project again" else "Project to cluster")
            }
            OutlinedButton(
                onClick = { ClusterProjection.stop(context) },
                enabled = projecting && !busy,
                modifier = Modifier.height(56.dp),
            ) {
                Text("Stop")
            }
            OutlinedButton(
                onClick = { ClusterMirrorWindow.toggle(context) },
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (ClusterMirrorWindow.isShown) "Hide floating mirror" else "Float the mirror")
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { ClusterProjection.connect(context) },
                enabled = !busy,
            ) {
                Text(if (ClusterProjection.linkReady) "Link ready" else "Test link")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = ClusterProjection.status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ClusterProjection.CLUSTER_WIDTH.toFloat() / ClusterProjection.CLUSTER_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            val viewW = constraints.maxWidth
            val viewH = constraints.maxHeight
            ClusterPreview(Modifier.fillMaxSize(), touchable = draft == null)
            if (!ClusterProjection.mirrorLive) {
                Text(
                    text = if (projecting) "Mirror starting…" else "The cluster shows here while projecting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            draft?.let { frame ->
                ResizeFrame(
                    frame = frame,
                    scaling = scaling,
                    viewWidth = viewW,
                    viewHeight = viewH,
                    keepAspect = settings.clusterKeepAspect,
                    onChange = { draft = it },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { ClusterProjection.sendKey(KeyEvent.KEYCODE_BACK) },
                enabled = projecting,
            ) {
                Text("Back on cluster")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Tap and swipe the mirror to control the app on the cluster.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsCard(title = "Cluster window") {
        Text(
            text = "Where the cast app sits on the dial. Presets snap it to the usual " +
                "layouts; Resize lets you drag a frame on the preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClusterPlacement.entries.filter { it != ClusterPlacement.CUSTOM }.forEach { option ->
                FilterChip(
                    selected = draft == null && placement == option,
                    onClick = {
                        draft = null
                        ClusterProjection.setPlacement(context, option)
                    },
                    label = { Text(option.label) },
                )
            }
            FilterChip(
                selected = draft != null || placement == ClusterPlacement.CUSTOM,
                onClick = {
                    if (draft == null) draft = Rect(ClusterProjection.currentBounds(context))
                },
                label = { Text(if (draft == null) "Resize" else "Resizing…") },
            )
        }
        if (draft != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    draft?.let { ClusterProjection.setPlacement(context, ClusterPlacement.CUSTOM, it) }
                    draft = null
                }) { Text("Apply") }
                OutlinedButton(onClick = { draft = null }) { Text("Cancel") }
                OutlinedButton(onClick = { draft = Rect(ClusterGeometry.full) }) { Text("Reset") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Keep cluster aspect",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "The resize frame keeps the dial's own 8:3 shape.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.clusterKeepAspect,
                onCheckedChange = { settings.clusterKeepAspect = it },
            )
        }
    }

    SettingsCard(title = "Mirror scaling") {
        Text(
            text = "How the cluster mirror fills the preview and the floating window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MirrorScaling.entries.forEach { option ->
                FilterChip(
                    selected = scaling == option,
                    onClick = { ClusterProjection.setScaling(context, option) },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = scaling.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsCard(title = "Auto-start on power-on") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Cast the last-used app automatically",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Each time the car powers on, ${selected?.label ?: "the chosen app"} " +
                        "goes back onto the cluster. A \"Cast to cluster\" button on a bar " +
                        "does the same by hand, and \"Cluster mirror\" floats the mirror.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.clusterAutoStart,
                onCheckedChange = { settings.clusterAutoStart = it },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Uses the head unit's own wireless debugging link (port 5555). The first " +
                "time, the unit asks \"Allow USB debugging?\": tap Allow with \"Always allow " +
                "from this computer\" ticked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            onPick = {
                settings.clusterComponent = it.key
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** The mirror surface plus touch forwarding; the controller owns everything else. */
@Composable
private fun ClusterPreview(modifier: Modifier = Modifier, touchable: Boolean) {
    DisposableEffect(Unit) {
        onDispose { ClusterProjection.detachPreview(ClusterProjection.SLOT_PREVIEW) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                // Blended over the black box behind it: the mirror leaves empty
                // parts of the cluster transparent.
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also {
                            ClusterProjection.attachPreview(ClusterProjection.SLOT_PREVIEW, it, width, height)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        val current = surface ?: Surface(texture).also { surface = it }
                        ClusterProjection.attachPreview(ClusterProjection.SLOT_PREVIEW, current, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        ClusterProjection.detachPreview(ClusterProjection.SLOT_PREVIEW)
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.setOnTouchListener { v, event ->
                if (!touchable) return@setOnTouchListener false
                // Keep the settings scroll from stealing a swipe meant for the cluster.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                ClusterProjection.forwardTouch(event, v.width, v.height)
                true
            }
        },
    )
}

/**
 * The cast window's bounds drawn over the preview, movable by dragging inside
 * and resizable by its bottom-right corner, in cluster pixels throughout.
 */
@Composable
private fun ResizeFrame(
    frame: Rect,
    scaling: MirrorScaling,
    viewWidth: Int,
    viewHeight: Int,
    keepAspect: Boolean,
    onChange: (Rect) -> Unit,
) {
    val (source, target) = remember(scaling, viewWidth, viewHeight) {
        ClusterGeometry.mirrorRects(scaling, viewWidth, viewHeight)
    }
    // Preview pixels per cluster pixel, and back.
    val scaleX = target.width().toFloat() / source.width()
    val scaleY = target.height().toFloat() / source.height()
    fun toView(x: Int, y: Int) = IntOffset(
        (target.left + (x - source.left) * scaleX).roundToInt(),
        (target.top + (y - source.top) * scaleY).roundToInt(),
    )
    val minWidth = 320
    val minHeight = 180

    val origin = toView(frame.left, frame.top)
    val widthPx = (frame.width() * scaleX).roundToInt().coerceAtLeast(1)
    val heightPx = (frame.height() * scaleY).roundToInt().coerceAtLeast(1)
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = Modifier
            .offset { origin }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .pointerInput(frame, scaleX, scaleY) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dx = (drag.x / scaleX).roundToInt()
                    val dy = (drag.y / scaleY).roundToInt()
                    val moved = Rect(frame).apply { offset(dx, dy) }
                    // Keep it on the cluster.
                    if (moved.left < 0) moved.offset(-moved.left, 0)
                    if (moved.top < 0) moved.offset(0, -moved.top)
                    if (moved.right > ClusterGeometry.WIDTH) moved.offset(ClusterGeometry.WIDTH - moved.right, 0)
                    if (moved.bottom > ClusterGeometry.HEIGHT) moved.offset(0, ClusterGeometry.HEIGHT - moved.bottom)
                    onChange(moved)
                }
            },
    ) {
        Text(
            text = "${frame.width()} × ${frame.height()} at ${frame.left}, ${frame.top}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 12.dp))
                .pointerInput(frame, scaleX, scaleY, keepAspect) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        var width = frame.width() + (drag.x / scaleX).roundToInt()
                        var height = frame.height() + (drag.y / scaleY).roundToInt()
                        width = width.coerceIn(minWidth, ClusterGeometry.WIDTH - frame.left)
                        height = height.coerceIn(minHeight, ClusterGeometry.HEIGHT - frame.top)
                        if (keepAspect) {
                            height = (width * ClusterGeometry.HEIGHT / ClusterGeometry.WIDTH)
                                .coerceIn(minHeight, ClusterGeometry.HEIGHT - frame.top)
                            width = width.coerceAtMost(height * ClusterGeometry.WIDTH / ClusterGeometry.HEIGHT)
                        }
                        onChange(Rect(frame.left, frame.top, frame.left + width, frame.top + height))
                    }
                },
        )
    }
}
