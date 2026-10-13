package com.example.esmerui.ui

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.esmerui.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.HomeRoleController
import androidx.compose.ui.text.style.TextOverflow
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.car.CarStatus
import com.example.esmerui.launcher.ConfigTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.esmerui.launcher.NavAction
import com.example.esmerui.launcher.NavActionGroup
import com.example.esmerui.launcher.NavLayout
import com.example.esmerui.navbar.NavActionIcons
import com.example.esmerui.launcher.NavBarConfig
import com.example.esmerui.launcher.NavItem
import com.example.esmerui.launcher.NavPosition
import com.example.esmerui.launcher.WindowMode
import com.example.esmerui.launcher.findByKey
import com.example.esmerui.launcher.sameAs
import com.example.esmerui.launcher.window.FreeformWindow
import com.example.esmerui.navbar.AccessibilityGuard
import com.example.esmerui.navbar.NavAccessibilityService
import com.example.esmerui.navbar.NavBarService
import com.example.esmerui.navbar.SystemBarController
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which tab is showing. Deliberately not rememberSaveable: settings is reached
    // from the home screen and left by the Back button, and coming back to where
    // you were last week is more surprising than starting at the top.
    var tab by remember { mutableStateOf(SettingsTab.HOME) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = "ESMER·UI settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { settings.resetToDefaults() }) {
                Text("Reset")
            }
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    // Taller than the default: this is reached for while parked, but
                    // still from a driving position, and the default tab is a thin
                    // strip to hit.
                    modifier = Modifier.height(56.dp),
                    text = {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        // Each tab scrolls on its own: a fresh state per tab means switching to a
        // short one does not land you halfway down it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(remember(tab) { ScrollState(0) }),
        ) {
            when (tab) {
                SettingsTab.HOME -> {
                    SettingsCard(title = "Grid") {
                        StepperSetting(
                            label = "Columns",
                            value = settings.columns,
                            range = LauncherSettings.COLUMNS_RANGE,
                            onValueChange = { settings.columns = it },
                        )
                        StepperSetting(
                            label = "Rows per page",
                            value = settings.rows,
                            range = LauncherSettings.ROWS_RANGE,
                            onValueChange = { settings.rows = it },
                        )
                        Text(
                            text = "${settings.appsPerPage} apps per page",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    SettingsCard(title = "Icons") {
                        SliderSetting(
                            label = "Icon size",
                            value = settings.iconSizeDp,
                            range = LauncherSettings.ICON_SIZE_RANGE,
                            valueLabel = { "$it dp" },
                            onValueChange = { settings.iconSizeDp = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        IconPreview(sizeDp = settings.iconSizeDp)
                    }

                    SettingsCard(title = "Preview") {
                        GridPreview(columns = settings.columns, rows = settings.rows)
                    }
                }

                SettingsTab.BARS -> NavBarSettingsCard(settings = settings, apps = apps)

                SettingsTab.WINDOWS -> WindowSettingsCard(settings = settings, apps = apps)

                SettingsTab.CLUSTER -> ClusterSettingsCard(settings = settings, apps = apps)

                SettingsTab.HOME_ASSISTANT -> HomeAssistantCard(settings = settings)

                SettingsTab.SYSTEM -> {
                    DefaultLauncherCard(settings = settings)
                    BackupCard(settings = settings)
                }
            }

            // Clears the head unit's own dock, which overlays the bottom of the
            // screen without reporting a window inset.
            Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * The settings screen's sections.
 *
 * Split because the single column had grown to a few screens of scrolling, and
 * the thing you came to change was rarely the thing on screen. Ordered by how
 * often each is wanted rather than by how the code is laid out.
 */
private enum class SettingsTab(val label: String) {
    HOME("Home"),
    BARS("Bars"),
    WINDOWS("Windows"),
    CLUSTER("Cluster"),
    HOME_ASSISTANT("Home Assistant"),
    SYSTEM("System"),
}

/**
 * Save the whole configuration to a file and restore it later.
 *
 * Worth having in its own right, and it also makes the settings recoverable after
 * an experiment goes wrong - restoring is one tap rather than rebuilding every bar
 * by hand.
 */
@Composable
private fun BackupCard(settings: LauncherSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }

    // File I/O must not run on the main thread, short though it is.
    fun run(work: () -> ConfigTransfer.Result) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { work() }
            status = when (result) {
                is ConfigTransfer.Result.Success ->
                    "${result.entries} settings - ${result.path}"

                is ConfigTransfer.Result.Failure -> result.reason
            }
            if (result is ConfigTransfer.Result.Success) NavBarService.sync(context)
        }
    }

    SettingsCard(title = "Backup") {
        Text(
            text = "Saves every setting - bars, items, grid, window - to a file you " +
                "can copy off the unit and restore later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { run { ConfigTransfer.exportConfig(context) } }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Export",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { confirmImport = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Import",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = status ?: ConfigTransfer.displayPath(context),
            style = MaterialTheme.typography.labelSmall,
            color = if (status == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Replace current settings?") },
            text = {
                Text(
                    "Importing overwrites every setting with the contents of the " +
                        "saved file. Export first if you want to keep what you have."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    run { ConfigTransfer.importConfig(context) }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Home-app role. There is no public API to take it, so this reports the current
 * holder and hands the user to the system picker when we cannot take it directly.
 */
@Composable
private fun DefaultLauncherCard(settings: LauncherSettings) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(HomeRoleController.isDefault(context)) }
    val currentHome = remember(isDefault) { HomeRoleController.currentHomeLabel(context) }

    // Recheck when the screen is resumed, so returning from the picker updates this.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = HomeRoleController.isDefault(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCard(title = "Default launcher") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !isDefault) {
                    isDefault = HomeRoleController.requestDefault(context)
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isDefault) "ESMER is your home app" else "Not the home app",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isDefault) {
                        "The Home button opens this launcher."
                    } else {
                        "Currently ${currentHome ?: "another launcher"}. Tap to change."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
            if (!isDefault) {
                Text(
                    text = "Set",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = "Check on every start",
            subtitle = "Show a reminder on the home screen when another launcher holds the role",
            checked = settings.checkDefaultOnStart,
            onCheckedChange = { settings.checkDefaultOnStart = it },
        )
    }
}

/**
 * Navigation bar controls.
 *
 * Every screen edge is an independent bar with its own items, so this card edits
 * one edge at a time rather than showing four sets of controls at once.
 *
 * Two platform facts are surfaced rather than hidden: Back and Recents need the
 * accessibility service, and taking the system bar's strip needs
 * WRITE_SECURE_SETTINGS granted over adb.
 */
@Composable
private fun NavBarSettingsCard(settings: LauncherSettings, apps: List<AppEntry>) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(NavPosition.BOTTOM) }
    var showPicker by remember { mutableStateOf(false) }

    // Another app on this unit rewrites the enabled-services list, so this is
    // re-read on demand rather than cached for the life of the screen.
    var a11yEnabled by remember { mutableStateOf(NavAccessibilityService.isEnabled(context)) }
    val canHideSystemBar = remember(settings.navHideSystemBar) {
        SystemBarController.canControl(context)
    }

    val config = settings.navBar(editing)

    // Putting a change on screen means the service tearing down and re-adding
    // every overlay window, so it is only asked to when something changed.
    fun edit(transform: (NavBarConfig) -> NavBarConfig) {
        val before = settings.navBar(editing)
        settings.updateNavBar(editing, transform)
        if (settings.navBar(editing) != before) NavBarService.sync(context)
    }

    SettingsCard(title = "Navigation bars") {
        Text(
            text = "Each edge is a separate bar. Pick an edge to edit it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavPosition.entries.forEach { position ->
                val bar = settings.navBar(position)
                val isEditing = position == editing
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isEditing) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { editing = position }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = position.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isEditing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            bar.isRenderable -> "On - ${bar.items.size} items"
                            bar.enabled -> "On - empty"
                            else -> "Off"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bar.isRenderable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SwitchRow(
            title = "Enable ${config.position.label.lowercase()} bar",
            subtitle = if (config.items.isEmpty()) {
                "Add at least one item below for this bar to appear"
            } else {
                "${config.items.size} items on this edge"
            },
            checked = config.enabled,
            onCheckedChange = { on -> edit { it.copy(enabled = on) } },
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Layout",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        NavLayout.entries.forEach { layout ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { edit { it.copy(layout = layout) } }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = config.layout == layout,
                    onClick = { edit { it.copy(layout = layout) } },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = layout.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = layout.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SliderSetting(
            label = "Bar thickness",
            value = config.thicknessDp,
            range = LauncherSettings.NAV_THICKNESS_RANGE,
            valueLabel = { "$it dp" },
            onValueChange = { thickness -> edit { it.copy(thicknessDp = thickness) } },
        )

        SliderSetting(
            label = "Icon size",
            value = config.iconSizeDp,
            range = LauncherSettings.NAV_ICON_SIZE_RANGE,
            valueLabel = { "$it dp" },
            onValueChange = { size -> edit { it.copy(iconSizeDp = size) } },
        )
        if (config.iconSizeDp > config.thicknessDp - 12) {
            Text(
                text = "Icons are capped by the bar thickness - raise it to go bigger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Items on the ${config.position.label.lowercase()} bar",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (config.items.isEmpty()) {
                "Nothing yet - add buttons or apps below."
            } else {
                "They appear in this order. Drag a row by its handle to move it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Which row is being dragged, and how far it has travelled. Held here rather
        // than in the row so the rows it passes can step aside as it goes.
        var dragFrom by remember(editing) { mutableStateOf<Int?>(null) }
        var dragOffset by remember(editing) { mutableFloatStateOf(0f) }
        val rowPx = with(LocalDensity.current) { NAV_ROW_HEIGHT.toPx() }

        // Rows are a fixed height, so the slot under the finger is just a division -
        // no need to measure or track each row's position.
        val lastIndex = config.items.lastIndex
        fun slotFor(from: Int) =
            (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, lastIndex)

        // Where the row at [index] sits right now, relative to its resting place.
        // Everything between where the dragged row was picked up and where it now
        // hovers shifts one slot, so the gap follows the finger. Snapped rather than
        // animated: on the drop the list relayouts into the new order, and an
        // in-flight slide would be animating from a position that no longer means
        // anything.
        //
        // Called from each row's draw pass rather than here: the offset changes on
        // every frame of a drag, and reading it in composition recomposed the whole
        // card - every edge, slider and button - for each of those frames.
        fun shiftFor(index: Int): Float {
            val from = dragFrom ?: return 0f
            val dropAt = slotFor(from)
            return when {
                index == from -> dragOffset
                index in (from + 1)..dropAt -> -rowPx
                index in dropAt until from -> rowPx
                else -> 0f
            }
        }

        // Reads its values through the state delegates rather than from captured
        // locals: pointerInput keeps the lambda it was handed when the gesture
        // started, so anything captured by value would be the state at drag start.
        val stopDrag: (Boolean) -> Unit = { committed ->
            val from = dragFrom
            if (committed && from != null) {
                val to = slotFor(from)
                if (to != from) {
                    settings.moveNavItemTo(editing, from, to)
                    NavBarService.sync(context)
                }
            }
            dragFrom = null
            dragOffset = 0f
        }

        config.items.forEachIndexed { index, item ->
            NavItemRow(
                item = item,
                apps = apps,
                dragging = dragFrom == index,
                offsetY = { shiftFor(index) },
                onDragStart = { dragFrom = index; dragOffset = 0f },
                onDrag = { delta -> dragOffset += delta },
                onDragStop = stopDrag,
                onRemove = { settings.toggleNavItem(editing, item); NavBarService.sync(context) },
            )
        }

        Spacer(Modifier.height(16.dp))

        val carAvailable = CarStatus.available

        Text(
            text = "Add buttons",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!carAvailable) {
            Text(
                text = "No connection to the car yet - it answers once the vehicle is " +
                    "awake. Only navigation and volume are offered until then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Grouped, because a flat list of 16 buttons is unreadable at a glance.
        NavActionGroup.entries.forEach { group ->
            // A car without a head-up display gets no HUD section at all, rather
            // than six buttons that can only ever say the car refused.
            if (group == NavActionGroup.HUD && CarStatus.hudFitted == false) return@forEach
            val actions = NavAction.entries
                .filter { it.group == group && (carAvailable || !it.needsCar) }
                // A car can have the blind without the glass, or windows without a
                // powered tailgate, so the travelling panels are filtered one by
                // one rather than as a section.
                .filter { CarStatus.roofFitted(it) != false }
            if (actions.isEmpty()) return@forEach

            Spacer(Modifier.height(12.dp))
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            actions.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { action ->
                        val present = config.items.any { it.sameAs(NavItem.Action(action)) }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (present) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    settings.toggleNavItem(editing, NavItem.Action(action))
                                    NavBarService.sync(context)
                                }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(NavActionIcons.of(action)),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (present) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (present) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Keep the last, partly-filled row aligned with the ones above.
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Add apps to this bar",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(16.dp))

        SwitchRow(
            title = "Replace the head unit's bar",
            subtitle = when {
                !canHideSystemBar -> "Needs adb: ${SystemBarController.GRANT_COMMAND}"
                // Only offer the pull-down as the way back to BYD's climate while it
                // is actually reachable - hiding the status bar takes it away too.
                settings.navHideSystemBar && !settings.navHideStatusBar ->
                    "BYD's bar is covered by yours - climate moves to the top pull-down"
                settings.navHideSystemBar ->
                    "BYD's bar is covered by yours - with the status bar covered too, its " +
                        "climate panel is only reachable as an app shortcut"
                else -> "Puts your bottom bar over BYD's, including its climate controls"
            },
            subtitleHighlighted = !canHideSystemBar,
            enabled = canHideSystemBar,
            checked = settings.navHideSystemBar,
            // The bar service owns the platform setting: whether BYD's bar is hidden
            // or merely covered depends on which host is drawing ours right now.
            onCheckedChange = {
                settings.navHideSystemBar = it
                NavBarService.sync(context)
            },
        )

        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = "Replace the head unit's status bar",
            subtitle = when {
                !canHideSystemBar -> "Needs adb: ${SystemBarController.GRANT_COMMAND}"
                settings.navHideStatusBar ->
                    "BYD's status bar is covered by yours - a top bar of your own has the strip"
                else -> "Puts your top bar over BYD's, including the notification pull-down"
            },
            subtitleHighlighted = !canHideSystemBar,
            enabled = canHideSystemBar,
            checked = settings.navHideStatusBar,
            onCheckedChange = {
                settings.navHideStatusBar = it
                NavBarService.sync(context)
            },
        )

        Spacer(Modifier.height(8.dp))

        // Listed and connected are different things on this unit. The head unit
        // refuses to start our service while we are not running unless its own
        // "Disable Autostart" list allows us, and being in the enabled list does
        // nothing about that - so a third state, with the one fix that works.
        var a11yConnected by remember { mutableStateOf(NavAccessibilityService.isConnected) }
        val a11yBlocked = a11yEnabled && !a11yConnected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    if (!AccessibilityGuard.ensureEnabled(context)) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else if (!NavAccessibilityService.isConnected) {
                        AccessibilityGuard.openAutostartManager(context)
                    }
                    a11yEnabled = NavAccessibilityService.isEnabled(context)
                    a11yConnected = NavAccessibilityService.isConnected
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Back and Recents",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        a11yBlocked ->
                            "Enabled but not running. Tap to open the car's Disable Autostart " +
                                "list and switch ESMER·UI off there, so the system may start it"
                        a11yEnabled ->
                            "Active. Re-applied automatically if another app disables it."
                        else ->
                            "Inactive - needs the accessibility service. Tap to fix."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a11yEnabled && !a11yBlocked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
            if (!a11yEnabled || a11yBlocked) {
                Text(
                    text = if (a11yBlocked) "Allow" else "Fix",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            selectedComponents = config.items
                .filterIsInstance<NavItem.App>()
                .map { it.component }
                .toSet(),
            multiSelect = true,
            onPick = { app ->
                settings.toggleNavItem(editing, NavItem.App(app.component))
                NavBarService.sync(context)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Height of one row, fixed so the drag can work out the slot from the offset alone. */
private val NAV_ROW_HEIGHT = 56.dp

/**
 * One row of the ordered item list, with its drag handle and remove control.
 *
 * [offsetY] is a lambda so the row can read it while drawing: the value moves
 * with every frame of a drag, and only the layer needs to know.
 */
@Composable
private fun NavItemRow(
    item: NavItem,
    apps: List<AppEntry>,
    dragging: Boolean,
    offsetY: () -> Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStop: (committed: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val app = (item as? NavItem.App)?.let { apps.findByKey(it.key) }
    val label = when (item) {
        is NavItem.Action -> item.action.label
        is NavItem.App -> app?.label ?: "Not installed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_ROW_HEIGHT)
            // The dragged row rides above the others, and is lifted visually so it
            // is obvious which one the finger has hold of.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offsetY() }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (item) {
                is NavItem.Action -> Icon(
                    painter = painterResource(navActionIcon(item.action)),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )

                is NavItem.App -> if (app?.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Dragging is on the handle, not the whole row: the list sits inside a
        // scrolling page, and a row-wide grab would fight it for every swipe.
        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(item) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragStop(true) },
                        onDragCancel = { onDragStop(false) },
                        onDrag = { change, amount ->
                            // Claim the gesture so the page underneath stops scrolling.
                            change.consume()
                            onDrag(amount.y)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_drag),
                contentDescription = "Drag to reorder",
                modifier = Modifier.size(22.dp),
                tint = if (dragging) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Remove",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun navActionIcon(action: NavAction): Int = NavActionIcons.of(action)

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitleHighlighted: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleHighlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * The floating-window controls. Deliberately states up front which backend the
 * device actually supports, because whether an arbitrary app can be windowed is a
 * property of the ROM, not of this launcher.
 */
@Composable
private fun WindowSettingsCard(settings: LauncherSettings, apps: List<AppEntry>) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    val freeformSupported = remember(context) { FreeformWindow.isSupported(context) }
    val selected = remember(settings.windowComponent, apps) {
        apps.findByKey(settings.windowComponent)
    }

    SettingsCard(title = "Floating app window") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Enable window",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (freeformSupported) {
                        "Freeform is available on this device"
                    } else {
                        "Freeform is OFF - run: ${FreeformWindow.ENABLE_COMMAND}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (freeformSupported) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Switch(
                checked = settings.windowEnabled,
                onCheckedChange = { settings.windowEnabled = it },
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "App in window",
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

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Mode",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        WindowMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { settings.windowMode = mode }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = settings.windowMode == mode,
                    onClick = { settings.windowMode = mode },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SliderSetting(
            label = "Window width",
            value = settings.windowWidthPercent,
            range = LauncherSettings.WINDOW_SIZE_RANGE,
            valueLabel = { "$it%" },
            onValueChange = { settings.windowWidthPercent = it },
        )
        SliderSetting(
            label = "Window height",
            value = settings.windowHeightPercent,
            range = LauncherSettings.WINDOW_SIZE_RANGE,
            valueLabel = { "$it%" },
            onValueChange = { settings.windowHeightPercent = it },
        )
        Text(
            text = "Drag the window's title bar to move it, or its bottom-right " +
                "corner to resize.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            onPick = {
                settings.windowComponent = it.key
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
internal fun AppPickerDialog(
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    selectedComponents: Set<ComponentName> = emptySet(),
    multiSelect: Boolean = false,
) {
    var showHidden by remember { mutableStateOf(false) }
    val visible = remember(apps, showHidden) {
        if (showHidden) apps else apps.filterNot { it.isHiddenScreen }
    }
    val hiddenCount = remember(apps) { apps.count { it.isHiddenScreen } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (multiSelect) "Apps on the navigation bar" else "Choose an app")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = if (hiddenCount > 0) {
            {
                TextButton(onClick = { showHidden = !showHidden }) {
                    Text(
                        if (showHidden) "Hide built-in screens"
                        else "Built-in screens ($hiddenCount)"
                    )
                }
            }
        } else null,
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(visible, key = { it.key }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(app) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        app.icon?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Several hidden screens share one app label (Media
                            // Center is main, radio and BT music), so name the screen.
                            if (app.isHiddenScreen) {
                                Text(
                                    text = app.component.className.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (multiSelect) {
                            Checkbox(
                                checked = app.component in selectedComponents,
                                onCheckedChange = { onPick(app) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Plus/minus stepper - easier to hit than a slider while driving. */
@Composable
private fun StepperSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onValueChange(value - 1) },
            enabled = value > range.first,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = "Decrease $label",
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = { onValueChange(value + 1) },
            enabled = value < range.last,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = "Increase $label",
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * A slider over a whole-number setting, drawn by hand.
 *
 * Material's slider recomposes, re-measures and re-announces itself on every
 * frame of a drag, and on this unit each of those costs milliseconds before the
 * page around it is counted: the page is one accessibility tree, and with the
 * accessibility services this unit has switched on (the launcher's own, Dudu's,
 * OverDrive's, the ROM's) Compose re-walks and diffs the whole of it whenever any
 * node's semantics change. Measured on the bars tab, a drag of Material's slider
 * ran at five to ten frames a second.
 *
 * This one moves nothing but pixels while the finger is down. The thumb, the
 * track and the value label are drawn from a state only the draw pass reads, in a
 * layer of their own, so a frame of drag is one small re-record; the setting
 * hears the number once, when the finger lifts. Same look as Material's: a 16 dp
 * track, a 4 x 44 dp bar thumb with a 6 dp gap either side, a stop dot at the
 * far end.
 */
@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    valueLabel: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyLarge
        .copy(color = MaterialTheme.colorScheme.onSurface)
    val valueStyle = MaterialTheme.typography.bodyLarge
        .copy(color = MaterialTheme.colorScheme.primary)
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.secondaryContainer

    // Where the finger is while it is down, as a fraction of the track; null
    // otherwise, so the thumb follows the setting whenever something else (Reset,
    // an import) changes it. Read by the draw pass and the gesture, never by
    // composition.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val span = (range.last - range.first).toFloat()
    fun fractionOf(v: Int) = (v - range.first) / span
    fun valueAt(fraction: Float) = range.first + (fraction * span).roundToInt()

    fun settle(commit: Boolean) {
        val fraction = dragFraction ?: return
        dragFraction = null
        val settled = valueAt(fraction)
        if (commit && settled != currentValue) currentOnValueChange(settled)
    }

    Spacer(
        Modifier
            .fillMaxWidth()
            .height(SLIDER_LABEL_HEIGHT + SLIDER_THUMB_HEIGHT)
            // Its own layer: without one, each frame re-recorded the draw list of
            // the whole card around it.
            .graphicsLayer()
            .progressSemantics(value.toFloat(), range.first.toFloat()..range.last.toFloat())
            .semantics {
                contentDescription = label
                setProgress { target ->
                    onValueChange(target.roundToInt())
                    true
                }
            }
            .pointerInput(range) {
                val thumbWidth = SLIDER_THUMB_WIDTH.toPx()
                fun fractionAt(x: Float) =
                    ((x - thumbWidth / 2) / (size.width - thumbWidth)).coerceIn(0f, 1f)

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragFraction = fractionAt(down.position.x)
                    // Only a sideways move is a drag: the page underneath scrolls on
                    // a vertical one and takes the pointer with it, and then the
                    // thumb goes back where it was.
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                        dragFraction = fractionAt(change.position.x)
                    }
                    if (start != null) {
                        val finished = horizontalDrag(start.id) { change ->
                            change.consume()
                            dragFraction = fractionAt(change.position.x)
                        }
                        settle(commit = finished)
                    } else {
                        // Lifted without moving: a tap, which lands the thumb there.
                        val lifted = currentEvent.changes.none { it.id == down.id && it.pressed }
                        settle(commit = lifted)
                    }
                }
            }
            .drawWithCache {
                val trackHeight = SLIDER_TRACK_HEIGHT.toPx()
                val thumbWidth = SLIDER_THUMB_WIDTH.toPx()
                val thumbHeight = SLIDER_THUMB_HEIGHT.toPx()
                val gap = SLIDER_THUMB_GAP.toPx()
                val outer = CornerRadius(trackHeight / 2)
                val inner = CornerRadius(2.dp.toPx())
                val stopRadius = 2.dp.toPx()
                val trackCenterY = SLIDER_LABEL_HEIGHT.toPx() + thumbHeight / 2
                val trackTop = trackCenterY - trackHeight / 2
                val labelLayout = textMeasurer.measure(label, labelStyle)
                // Laying out a value's text costs a few milliseconds here, and a drag
                // shows a new one most frames; each is laid out once per size.
                val valueLayouts = HashMap<Int, TextLayoutResult>()
                val path = Path()

                // One piece of track, round at its outer end and nearly square at
                // the end that faces the thumb.
                fun DrawScope.segment(
                    left: Float,
                    right: Float,
                    leftRadius: CornerRadius,
                    rightRadius: CornerRadius,
                    color: Color,
                ) {
                    if (right <= left) return
                    path.rewind()
                    path.addRoundRect(
                        RoundRect(
                            left, trackTop, right, trackTop + trackHeight,
                            leftRadius, rightRadius, rightRadius, leftRadius,
                        )
                    )
                    drawPath(path, color)
                }

                onDrawBehind {
                    val fraction = dragFraction ?: fractionOf(currentValue)
                    val thumbCenter = thumbWidth / 2 + fraction * (size.width - thumbWidth)

                    segment(0f, thumbCenter - thumbWidth / 2 - gap, outer, inner, activeColor)
                    segment(thumbCenter + thumbWidth / 2 + gap, size.width, inner, outer, inactiveColor)
                    // The stop marks the far end until the thumb itself is there.
                    if (fraction < 1f) {
                        drawCircle(
                            activeColor,
                            stopRadius,
                            Offset(size.width - trackHeight / 2, trackCenterY),
                        )
                    }
                    drawRoundRect(
                        activeColor,
                        Offset(thumbCenter - thumbWidth / 2, trackCenterY - thumbHeight / 2),
                        Size(thumbWidth, thumbHeight),
                        CornerRadius(thumbWidth / 2),
                    )

                    drawText(labelLayout)
                    val shown = valueAt(fraction)
                    val valueLayout = valueLayouts.getOrPut(shown) {
                        textMeasurer.measure(valueLabel(shown), valueStyle)
                    }
                    drawText(valueLayout, topLeft = Offset(size.width - valueLayout.size.width, 0f))
                }
            }
    )
}

/** The label line above the track: bodyLarge's line height. */
private val SLIDER_LABEL_HEIGHT = 24.dp
private val SLIDER_TRACK_HEIGHT = 16.dp
private val SLIDER_THUMB_WIDTH = 4.dp
private val SLIDER_THUMB_HEIGHT = 44.dp
private val SLIDER_THUMB_GAP = 6.dp

/** Shows the chosen icon size at 1:1 so the number means something. */
@Composable
private fun IconPreview(sizeDp: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(sizeDp.dp / 4))
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Actual size",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scaled-down sketch of one page, so the row/column numbers are visible at a glance. */
@Composable
private fun GridPreview(columns: Int, rows: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height((rows * 26).dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(columns) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        }
    }
}
