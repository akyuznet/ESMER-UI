package com.example.esmerui.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.esmerui.launcher.LauncherWidgets

/**
 * Picks a widget and walks it through the platform's two-step handshake.
 *
 * Adding a widget is never just "place it": the id has to be bound to the provider
 * (which needs a permission a normal app cannot hold, so the system asks the user
 * instead), and some providers then demand a setup screen before they will draw.
 * Either step can be refused, and a refused id has to be handed back or it leaks
 * for the life of the install.
 */
@Composable
fun WidgetPickerDialog(
    widgets: LauncherWidgets,
    columns: Int,
    rows: Int,
    onDismiss: () -> Unit,
    /** Hands back a bound, configured widget and the span it asked for. */
    onPlaced: (appWidgetId: Int, w: Int, h: Int) -> Unit,
) {
    val context = LocalContext.current
    val providers = remember {
        widgets.providers().sortedBy { it.loadLabel(context.packageManager)?.lowercase() ?: "" }
    }

    // Carried across the bind and configure round trips.
    var pendingId by remember { mutableStateOf(-1) }
    var pendingInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }

    fun abandon() {
        if (pendingId != -1) widgets.deleteId(pendingId)
        pendingId = -1
        pendingInfo = null
    }

    fun place() {
        val info = pendingInfo
        val id = pendingId
        pendingId = -1
        pendingInfo = null
        if (info == null || id == -1) return
        // Span comes from the provider's own preference, clamped to the grid. Where
        // it goes is the grid's business, not this dialog's.
        val (w, h) = widgets.defaultSpan(info, CELL_HINT_DP, CELL_HINT_DP)
        onPlaced(id, w.coerceIn(1, columns), h.coerceIn(1, rows))
        onDismiss()
    }

    val configureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) place() else abandon()
    }

    fun configureOrPlace() {
        val info = pendingInfo ?: return
        val intent = widgets.configureIntent(pendingId, info)
        if (intent == null) place() else configureLauncher.launch(intent)
    }

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) configureOrPlace() else abandon()
    }

    fun start(info: AppWidgetProviderInfo) {
        val id = widgets.allocateId()
        pendingId = id
        pendingInfo = info
        if (widgets.bind(id, info.provider, info.profile)) {
            configureOrPlace()
        } else {
            bindLauncher.launch(widgets.bindIntent(id, info.provider))
        }
    }

    AlertDialog(
        onDismissRequest = { abandon(); onDismiss() },
        title = { Text("Add a widget") },
        confirmButton = { TextButton(onClick = { abandon(); onDismiss() }) { Text("Cancel") } },
        text = {
            if (providers.isEmpty()) {
                Text("No app on this unit offers a widget.")
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    items(providers, key = { it.provider.flattenToString() }) { info ->
                        ProviderRow(info, widgets, columns, rows) { start(info) }
                    }
                }
            }
        },
    )
}

@Composable
private fun ProviderRow(
    info: AppWidgetProviderInfo,
    widgets: LauncherWidgets,
    columns: Int,
    rows: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val label = remember(info) { info.loadLabel(pm) ?: info.provider.shortClassName }
    val appName = remember(info) {
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        }.getOrDefault(info.provider.packageName)
    }
    val (w, h) = remember(info) { widgets.defaultSpan(info, CELL_HINT_DP, CELL_HINT_DP) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${w.coerceAtMost(columns)}×${h.coerceAtMost(rows)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = appName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A stand-in cell size for working out spans before the grid has been measured.
 * Close enough to this unit's real cell to pick a sane default; the layout clamps
 * whatever comes back to the grid anyway.
 */
private const val CELL_HINT_DP = 70f
