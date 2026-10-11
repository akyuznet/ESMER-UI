package com.example.esmerui.ui

import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.esmerui.cluster.ClusterProjection
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.findByKey

/**
 * The Cluster tab: pick an app, cast it to the instrument cluster, and drive it
 * from a live preview on this screen.
 *
 * The preview is a TextureView the shell-side helper mirrors the cluster into;
 * its touches go back the same way. It sits inside the settings scroll, so a
 * swipe on it is claimed for the cluster app rather than for scrolling.
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

    SettingsCard(title = "Projection to cluster") {
        Text(
            text = "Casts an app onto the instrument cluster behind the wheel, and shows " +
                "a live mirror of it here that you can tap and swipe to control it.",
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ClusterProjection.CLUSTER_WIDTH.toFloat() / ClusterProjection.CLUSTER_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            ClusterPreview(Modifier.fillMaxWidth())
            if (!ClusterProjection.mirrorLive) {
                Text(
                    text = if (projecting) "Mirror starting…" else "The cluster shows here while projecting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center),
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

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Uses the head unit's own wireless debugging link (port 5555). The first " +
                "time, the unit asks \"Allow USB debugging?\": tap Allow with \"Always allow " +
                "from this computer\" ticked. The cluster must have projection enabled in the " +
                "car's own settings for the dial to switch.",
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
private fun ClusterPreview(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        onDispose { ClusterProjection.detachPreview() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also { ClusterProjection.attachPreview(it, width, height) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        val current = surface ?: Surface(texture).also { surface = it }
                        ClusterProjection.attachPreview(current, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        ClusterProjection.detachPreview()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
                setOnTouchListener { view, event ->
                    // Keep the settings scroll from stealing a swipe meant for the cluster.
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    ClusterProjection.forwardTouch(event, view.width, view.height)
                    true
                }
            }
        },
    )
}
