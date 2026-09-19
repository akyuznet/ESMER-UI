package com.example.esmerui.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.esmerui.launcher.NavAction
import com.example.esmerui.launcher.NavActionGroup
import com.example.esmerui.navbar.NavActionIcons

/**
 * Picks a bar action to place on the home grid.
 *
 * Everything the bars can carry is offered, grouped the way the settings screen
 * groups it: the readouts first, since putting a speed or a tyre block in the middle
 * of a page is the reason this exists, then the controls.
 *
 * Actions that need the car are listed regardless - a readout that has nothing to
 * say draws a dash, which is more useful than pretending the option is absent.
 */
@Composable
fun ActionPickerDialog(
    onPick: (NavAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // Readouts lead; within a group, declaration order is already sensible.
    val ordered = remember {
        val groups = listOf(
            NavActionGroup.INFO,
            NavActionGroup.CLIMATE,
            NavActionGroup.SEATS,
            NavActionGroup.MEDIA,
            NavActionGroup.NAVIGATION,
        )
        groups.flatMap { group -> NavAction.entries.filter { it.group == group } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to the home screen") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(ordered, key = { it.name }) { action ->
                    ActionRow(action) { onPick(action); onDismiss() }
                }
            }
        },
    )
}

@Composable
private fun ActionRow(action: NavAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(NavActionIcons.of(action)),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append(action.group.label)
                    if (action.isReadout) append(" · live readout")
                    if (action.isGroup) append(" · stepper, 2 cells wide")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
