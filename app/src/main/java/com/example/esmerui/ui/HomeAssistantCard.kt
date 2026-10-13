package com.example.esmerui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.esmerui.home.HomeAssistant
import com.example.esmerui.launcher.LauncherSettings
import java.text.DateFormat
import java.util.Date

/**
 * The Home Assistant tab: where the server is and how to get in, and which of
 * its entities fill the six "Home" readouts that the bars, the home grid and
 * the cluster can then carry.
 */
@Composable
internal fun HomeAssistantCard(settings: LauncherSettings) {
    val context = LocalContext.current
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    var showToken by remember { mutableStateOf(false) }

    SettingsCard(title = "Home Assistant") {
        Text(
            text = "Pulls readings from your Home Assistant server over its REST API. " +
                "Make a long-lived access token in Home Assistant under your profile, " +
                "Security, and paste it here. The unit needs a route to the server: " +
                "the car's own data connection for a server reachable from the " +
                "internet, or the same Wi-Fi at home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = settings.homeAssistantUrl.orEmpty(),
            onValueChange = { settings.homeAssistantUrl = it.ifBlank { null } },
            label = { Text("Server URL") },
            placeholder = { Text("http://homeassistant.local:8123") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.homeAssistantToken.orEmpty(),
            onValueChange = { settings.homeAssistantToken = it.trim().ifBlank { null } },
            label = { Text("Long-lived access token") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) {
                    Text(if (showToken) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { HomeAssistant.test(context) },
                enabled = !HomeAssistant.busy && HomeAssistant.configured(settings),
            ) {
                Text("Test connection")
            }
            Text(
                text = HomeAssistant.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (HomeAssistant.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "How often to ask the server while a Home readout is on screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeAssistant.intervals.forEach { seconds ->
                FilterChip(
                    selected = settings.homeAssistantInterval == seconds,
                    onClick = { settings.homeAssistantInterval = seconds },
                    label = { Text(if (seconds < 60) "$seconds s" else "${seconds / 60} min") },
                )
            }
        }
    }

    SettingsCard(title = "Readouts from home") {
        Text(
            text = "Six readouts, Home 1 to Home 6, each showing one entity's state. Put " +
                "them on a bar, the home grid or the cluster like any other readout; the " +
                "entity's name sits under its value.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        (1..HomeAssistant.SLOTS).forEach { slot ->
            val entityId = HomeAssistant.slotEntity(settings, slot)
            val live = HomeAssistant.current(slot)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { pickingSlot = slot }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Home $slot",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(96.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = live?.name ?: entityId ?: "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (entityId == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entityId != null) {
                        Text(
                            text = entityId + (live?.let { "  ·  ${it.display}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entityId != null) {
                    TextButton(onClick = { HomeAssistant.setSlot(context, slot, null) }) { Text("Clear") }
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { HomeAssistant.refreshNow(context) },
                enabled = HomeAssistant.configured(settings),
            ) {
                Text("Update now")
            }
            val updated = HomeAssistant.lastUpdated
            val error = HomeAssistant.lastError
            Text(
                text = when {
                    error != null -> "Last update failed: $error"
                    updated > 0L -> "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updated))
                    else -> "Not updated yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pickingSlot?.let { slot ->
        EntityPickerDialog(
            slot = slot,
            onPick = {
                HomeAssistant.setSlot(context, slot, it.id)
                pickingSlot = null
            },
            onDismiss = { pickingSlot = null },
        )
    }
}

/**
 * Every entity on the server, searchable by name or id, each with its state
 * so the right sensor can be told from its lookalikes.
 */
@Composable
private fun EntityPickerDialog(
    slot: Int,
    onPick: (HomeAssistant.Entity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (HomeAssistant.catalog.isEmpty()) HomeAssistant.loadCatalog(context)
    }
    val matches = remember(query, HomeAssistant.catalog) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) HomeAssistant.catalog
        else HomeAssistant.catalog.filter { it.name.lowercase().contains(needle) || it.id.contains(needle) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entity for Home $slot") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        dismissButton = {
            TextButton(onClick = { HomeAssistant.loadCatalog(context) }, enabled = !HomeAssistant.busy) {
                Text("Reload")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (matches.isEmpty()) {
                    Text(
                        text = HomeAssistant.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(matches, key = { it.id }) { entity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(entity) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entity.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entity.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = entity.display,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
}
