package com.example.esmerui.ui

import android.appwidget.AppWidgetManager
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.esmerui.R
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.HomeCell
import com.example.esmerui.launcher.HomeItem
import com.example.esmerui.launcher.HomeLayout
import com.example.esmerui.launcher.HomeRoleController
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.LauncherWidgets
import com.example.esmerui.launcher.findByKey
import com.example.esmerui.navbar.NavBarService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The home screen: a grid of freely placed apps and widgets, across as many pages
 * as were asked for.
 *
 * Every item carries its own page and cell rather than an index into a packed list,
 * so an icon put in a corner stays in that corner with empty space around it. Long
 * press to arrange; drag anything anywhere; add apps, widgets and pages from the
 * bar that appears.
 */
@Composable
fun HomeScreen(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    isLoading: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the drawer; [pick] starts it in the mode where a tap adds to home. */
    onOpenAllApps: (pick: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val columns = settings.columns
    val rows = settings.rows

    val widgets = remember { LauncherWidgets(context) }
    // A listening host keeps every widget's app awake pushing updates, so it runs
    // only while this screen is actually in front of someone.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> widgets.startListening()
                Lifecycle.Event.ON_STOP -> widgets.stopListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            widgets.stopListening()
        }
    }

    val stored = settings.homeCells
    // Nothing arranged yet: lay out what is installed. Hidden screens are left out -
    // there are eighty-odd of them and no launcher shows them by default; they are
    // in the drawer, and can be added from there.
    val defaults = remember(apps, columns, rows) {
        HomeLayout.fromList(
            apps.filterNot { it.isHiddenScreen }.map { HomeItem.App(it.component) } +
                HomeItem.Settings,
            columns, rows,
        )
    }
    val raw = stored ?: defaults

    // Pages: at least what the setting asks for, always enough to reach the
    // furthest-out item, so a layout can never hide part of itself.
    val pages = maxOf(settings.homePages, (raw.maxOfOrNull { it.page } ?: 0) + 1)
    val cells = remember(raw, columns, rows, pages) {
        HomeLayout.normalise(raw, columns, rows, pages)
    }

    var editing by remember { mutableStateOf(false) }
    var showWidgetPicker by remember { mutableStateOf(false) }
    var showActionPicker by remember { mutableStateOf(false) }

    fun commit(next: List<HomeCell>) = settings.setHomeCells(next)

    Column(modifier = modifier.fillMaxSize()) {
        if (editing) {
            ArrangeBar(
                onAddApp = { onOpenAllApps(true) },
                onAddInfo = { showActionPicker = true },
                onAddWidget = { showWidgetPicker = true },
                onAddPage = { settings.homePages = pages + 1 },
                canRemovePage = pages > 1,
                onRemovePage = {
                    // Only ever drops a trailing page, and only an empty one, so
                    // nothing can be deleted out from under an icon.
                    if (cells.none { it.page == pages - 1 }) settings.homePages = pages - 1
                },
                onDone = { editing = false },
            )
        } else if (settings.checkDefaultOnStart) {
            DefaultLauncherReminder()
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            else -> HomePager(
                cells = cells,
                apps = apps,
                widgets = widgets,
                columns = columns,
                rows = rows,
                pages = pages,
                iconSizeDp = settings.iconSizeDp,
                editing = editing,
                onStartArranging = { editing = true },
                onCommit = ::commit,
                onLaunch = onLaunch,
                onAppInfo = onAppInfo,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showActionPicker) {
        ActionPickerDialog(
            onDismiss = { showActionPicker = false },
            onPick = { action ->
                // A stepper renders as [-] value [+], so it needs the width up front.
                val w = if (action.isGroup) 2 else 1
                var spot = HomeLayout.firstFree(cells, columns, rows, pages, w, 1)
                if (spot == null) {
                    settings.homePages = pages + 1
                    spot = Triple(pages, 0, 0)
                }
                commit(cells + HomeCell(spot.first, spot.second, spot.third, w, 1, HomeItem.Action(action)))
                // The poller only reads what something is showing, and this is now
                // something showing it.
                NavBarService.sync(context)
            },
        )
    }

    if (showWidgetPicker) {
        WidgetPickerDialog(
            widgets = widgets,
            columns = columns,
            rows = rows,
            onDismiss = { showWidgetPicker = false },
            onPlaced = { id, w, h ->
                // A widget needs a run of free cells, not just any gap. If the
                // pages as they stand cannot hold it, one more is added rather
                // than silently dropping a widget the user just set up.
                var spot = HomeLayout.firstFree(cells, columns, rows, pages, w, h)
                if (spot == null) {
                    settings.homePages = pages + 1
                    spot = Triple(pages, 0, 0)
                }
                commit(cells + HomeCell(spot.first, spot.second, spot.third, w, h, HomeItem.Widget(id)))
            },
        )
    }
}

@Composable
private fun ArrangeBar(
    onAddApp: () -> Unit,
    onAddInfo: () -> Unit,
    onAddWidget: () -> Unit,
    onAddPage: () -> Unit,
    canRemovePage: Boolean,
    onRemovePage: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Hold to move, anywhere on the page  ·  tap a widget to resize it  ·  " +
                "X removes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        BarAction("App", onAddApp)
        BarAction("Info", onAddInfo)
        BarAction("Widget", onAddWidget)
        BarAction("Page +", onAddPage)
        if (canRemovePage) BarAction("Page −", onRemovePage)
        BarAction("Done", onDone, emphasised = true)
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit, emphasised: Boolean = false) {
    Text(
        text = label,
        style = if (emphasised) MaterialTheme.typography.titleMedium
        else MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Shown when another launcher holds the home role. Rechecked on every resume, so
 * it disappears the moment the role is granted without needing a restart.
 */
@Composable
private fun DefaultLauncherReminder() {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(HomeRoleController.isDefault(context)) }

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

    if (isDefault) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { isDefault = HomeRoleController.requestDefault(context) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ESMER is not your home app - the Home button opens " +
                "${HomeRoleController.currentHomeLabel(context) ?: "another launcher"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Set as default",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HomePager(
    cells: List<HomeCell>,
    apps: List<AppEntry>,
    widgets: LauncherWidgets,
    columns: Int,
    rows: Int,
    pages: Int,
    iconSizeDp: Int,
    editing: Boolean,
    onStartArranging: () -> Unit,
    onCommit: (List<HomeCell>) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current

    LaunchedEffect(pages) {
        if (pagerState.currentPage >= pages) pagerState.scrollToPage(pages - 1)
    }

    // Which item the finger has hold of, by key rather than by position - the
    // position is exactly what a drag is about to change.
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var cellW by remember { mutableStateOf(0f) }
    var cellH by remember { mutableStateOf(0f) }

    // Which widget is showing its resize handle, and how far that handle has been
    // pulled. Only one at a time - a grid of handles would be unreadable.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var resizeOffset by remember { mutableStateOf(Offset.Zero) }
    var resizing by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (!editing) {
            dragKey = null; dragOffset = Offset.Zero
            selectedKey = null; resizeOffset = Offset.Zero; resizing = false
        }
    }

    /**
     * The cell [cell] would land on, given how far it has been dragged.
     *
     * Dragging well past a side aims at the neighbouring page, which is the only way
     * to move between them. Note what counts as "past": the item has to clear the
     * last valid column by [PAGE_EDGE] of a cell. Rounding to the nearest cell and
     * testing that made the right-hand half of the last column mean "next page", so
     * the far corner could not be landed on at all.
     *
     * Taken as a parameter rather than read from the enclosing scope so the same
     * arithmetic serves both the live highlight and the drop, with no chance of the
     * two disagreeing.
     */
    fun targetFor(cell: HomeCell, offset: Offset): Triple<Int, Int, Int>? {
        if (cellW <= 0f || cellH <= 0f) return null
        val exactX = cell.x + offset.x / cellW
        val lastX = (columns - cell.w).coerceAtLeast(0)
        val y = (cell.y + (offset.y / cellH).roundToInt()).coerceIn(0, rows - cell.h)
        return when {
            exactX < -PAGE_EDGE && cell.page > 0 -> Triple(cell.page - 1, lastX, y)
            exactX > lastX + PAGE_EDGE && cell.page < pages - 1 -> Triple(cell.page + 1, 0, y)
            else -> Triple(cell.page, exactX.roundToInt().coerceIn(0, lastX), y)
        }
    }

    val dragged = cells.firstOrNull { it.item.key == dragKey }

    /** Where the held item would land right now - drawn under it as a highlight. */
    val dropAt: Triple<Int, Int, Int>? = dragged?.let { targetFor(it, dragOffset) }

    /**
     * Applies the move. Nothing is moved until the finger lifts, because reordering
     * mid-drag re-composes the cell the gesture belongs to and Compose cancels the
     * drag along with it.
     *
     * Everything here is read from state at the moment of the drop rather than
     * captured when this function was built: the gesture handler holds whichever
     * closure existed when the drag began, so a captured item and target are a
     * snapshot from before the drag - which is exactly how a perfectly good drop
     * ended up being thrown away with a null item.
     */
    fun drop() {
        val cell = cells.firstOrNull { it.item.key == dragKey }
        val target = cell?.let { targetFor(it, dragOffset) }
        dragKey = null
        dragOffset = Offset.Zero
        if (cell == null || target == null) return
        val next = HomeLayout.move(cells, cell, target.first, target.second, target.third, columns, rows)
            ?: return
        onCommit(next)
        if (target.first != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(target.first) }
        }
    }

    val onDrop by rememberUpdatedState(::drop)

    val selected = cells.firstOrNull { it.item.key == selectedKey }

    /** The span the handle is currently asking for, in whole cells. */
    val resizeSpan: Pair<Int, Int>? = if (selected == null || !resizing || cellW <= 0f) {
        null
    } else {
        val w = (selected.w + (resizeOffset.x / cellW).roundToInt())
            .coerceIn(1, columns - selected.x)
        val h = (selected.h + (resizeOffset.y / cellH).roundToInt())
            .coerceIn(1, rows - selected.y)
        w to h
    }

    fun endResize() {
        val cell = selected
        val span = resizeSpan
        resizing = false
        resizeOffset = Offset.Zero
        if (cell == null || span == null) return
        HomeLayout.resize(cells, cell, span.first, span.second, columns, rows)?.let(onCommit)
    }

    val onResizeEnd by rememberUpdatedState(::endResize)

    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            pageSpacing = 8.dp,
            userScrollEnabled = dragKey == null,
        ) { page ->
            HomeGridPage(
                page = page,
                cells = cells.filter { it.page == page },
                apps = apps,
                widgets = widgets,
                columns = columns,
                rows = rows,
                iconSizeDp = iconSizeDp,
                editing = editing,
                dragKey = dragKey,
                dragOffset = dragOffset,
                dropAt = dropAt,
                selectedKey = selectedKey,
                resizeSpan = resizeSpan,
                onCellSize = { w, h -> cellW = w; cellH = h },
                onDragStart = { key -> dragKey = key; dragOffset = Offset.Zero },
                onDrag = { amount -> dragOffset += amount },
                onDragStop = { onDrop() },
                onResizeStart = { resizing = true; resizeOffset = Offset.Zero },
                onResize = { amount -> resizeOffset += amount },
                onResizeStop = { onResizeEnd() },
                onTap = { cell ->
                    val app = (cell.item as? HomeItem.App)?.let { apps.findByKey(it.key) }
                    when {
                        // Tapping a widget while arranging picks it up for resizing
                        // rather than asking about it - a widget has no app info
                        // page worth showing.
                        editing && cell.item is HomeItem.Widget ->
                            selectedKey = if (selectedKey == cell.item.key) null else cell.item.key
                        editing -> { selectedKey = null; app?.let(onAppInfo) }
                        cell.item is HomeItem.Settings -> onOpenSettings()
                        // A readout has nothing to press; a control does its thing.
                        cell.item is HomeItem.Action ->
                            if (!cell.item.action.isReadout && !cell.item.action.isGroup) {
                                NavBarService.perform(appContext, cell.item.action)
                            }
                        else -> app?.let(onLaunch)
                    }
                },
                onLongPress = onStartArranging,
                onRemove = { cell ->
                    // A widget's id belongs to the platform, so hand it back or it
                    // leaks for the life of the install.
                    (cell.item as? HomeItem.Widget)?.let { widgets.deleteId(it.appWidgetId) }
                    onCommit(cells.filterNot { it.item.key == cell.item.key })
                },
            )
        }

        if (pages > 1) {
            PageDots(
                pageCount = pages,
                current = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HomeGridPage(
    page: Int,
    cells: List<HomeCell>,
    apps: List<AppEntry>,
    widgets: LauncherWidgets,
    columns: Int,
    rows: Int,
    iconSizeDp: Int,
    editing: Boolean,
    dragKey: String?,
    dragOffset: Offset,
    dropAt: Triple<Int, Int, Int>?,
    selectedKey: String?,
    resizeSpan: Pair<Int, Int>?,
    onCellSize: (Float, Float) -> Unit,
    onDragStart: (String) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragStop: () -> Unit,
    onResizeStart: () -> Unit,
    onResize: (Offset) -> Unit,
    onResizeStop: () -> Unit,
    onTap: (HomeCell) -> Unit,
    onLongPress: () -> Unit,
    onRemove: (HomeCell) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / columns
        val cellHeight = maxHeight / rows
        val wPx = with(density) { cellWidth.toPx() }
        val hPx = with(density) { cellHeight.toPx() }
        LaunchedEffect(wPx, hPx) { onCellSize(wPx, hPx) }

        // Where the held item will land. Drawn under everything, so a widget being
        // carried does not hide its own destination.
        if (dropAt != null && dropAt.first == page) {
            val held = cells.firstOrNull { it.item.key == dragKey }
            Box(
                Modifier
                    .offset(x = cellWidth * dropAt.second, y = cellHeight * dropAt.third)
                    .size(cellWidth * (held?.w ?: 1), cellHeight * (held?.h ?: 1))
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        // The span a resize is currently asking for, drawn over the grid so the new
        // size is visible before the finger lifts and the widget actually reflows.
        val selectedCell = cells.firstOrNull { it.item.key == selectedKey }
        if (selectedCell != null && resizeSpan != null) {
            Box(
                Modifier
                    .offset(x = cellWidth * selectedCell.x, y = cellHeight * selectedCell.y)
                    .size(cellWidth * resizeSpan.first, cellHeight * resizeSpan.second)
                    .padding(4.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.large,
                    )
            )
        }

        cells.forEach { cell ->
            val dragging = cell.item.key == dragKey
            key(cell.item.key) {
                Box(
                    Modifier
                        .offset(x = cellWidth * cell.x, y = cellHeight * cell.y)
                        .size(cellWidth * cell.w, cellHeight * cell.h)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            if (dragging) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = DRAG_SCALE
                                scaleY = DRAG_SCALE
                                alpha = DRAG_ALPHA
                            }
                        }
                ) {
                    HomeCellView(
                        cell = cell,
                        app = (cell.item as? HomeItem.App)?.let { apps.findByKey(it.key) },
                        widgets = widgets,
                        widthDp = (cellWidth.value * cell.w).roundToInt(),
                        heightDp = (cellHeight.value * cell.h).roundToInt(),
                        // The icon gets whatever the cell has left after the label.
                        // Rows lose height when the arrange bar appears, and a fixed
                        // icon size then pushes the label off the bottom of its cell.
                        iconSize = minOf(
                            iconSizeDp.dp,
                            (cellHeight - LABEL_ROOM).coerceAtLeast(24.dp),
                            cellWidth - 8.dp,
                        ),
                        editing = editing,
                        dragging = dragging,
                        selected = cell.item.key == selectedKey,
                        onTap = { onTap(cell) },
                        onLongPress = onLongPress,
                        onRemove = { onRemove(cell) },
                        onDragStart = { onDragStart(cell.item.key) },
                        onDrag = onDrag,
                        onDragStop = onDragStop,
                        onResizeStart = onResizeStart,
                        onResize = onResize,
                        onResizeStop = onResizeStop,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCellView(
    cell: HomeCell,
    app: AppEntry?,
    widgets: LauncherWidgets,
    widthDp: Int,
    heightDp: Int,
    iconSize: Dp,
    editing: Boolean,
    dragging: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragStop: () -> Unit,
    onResizeStart: () -> Unit,
    onResize: (Offset) -> Unit,
    onResizeStop: () -> Unit,
) {
    val gestures = if (editing) {
        // Arranging: a tap asks about the app, a hold picks the item up. Plain
        // clickable rather than combinedClickable, so the long press belongs to the
        // drag detector alone instead of firing twice.
        Modifier
            .clickable(onClick = onTap)
            .pointerInput(cell.item.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragStop() },
                    onDragCancel = { onDragStop() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                )
            }
    } else {
        Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress)
    }

    val cellContext = LocalContext.current

    Box(Modifier.fillMaxSize().padding(4.dp)) {
        when (val item = cell.item) {
            is HomeItem.Widget -> {
                WidgetView(widgets, item.appWidgetId, widthDp, heightDp)
                // A widget's own views would eat the gesture, so while arranging a
                // transparent sheet over it takes the touches instead.
                if (editing) Box(Modifier.fillMaxSize().then(gestures))
            }

            is HomeItem.Action -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .then(gestures)
            ) {
                ActionCell(
                    action = item.action,
                    iconSize = iconSize,
                    // Readouts ignore this; a control routes its press to the bar
                    // service, where the climate and accessibility logic already is.
                    onPress = { NavBarService.perform(cellContext, it) },
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .then(gestures),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (item is HomeItem.Settings) {
                    GlyphIcon(R.drawable.ic_settings, iconSize)
                } else {
                    AppIcon(app?.icon, iconSize)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (item) {
                        is HomeItem.Settings -> "Settings"
                        // An app uninstalled since it was placed keeps its slot, so
                        // it can be seen and removed rather than leaving a hole.
                        else -> app?.label ?: "Not installed"
                    },
                    // The font's own ascent and descent padding is several dp of
                    // nothing, which is the difference between fitting and clipping
                    // on a short row.
                    style = MaterialTheme.typography.labelLarge.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (editing && !dragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Remove from home screen",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // A selected widget gets a corner to pull. One handle rather than an edge
        // each: it reads both directions out of the same drag, and three separate
        // grab targets on a small widget is more than a fingertip can aim at.
        if (editing && selected && !dragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onResizeStart() },
                            onDragEnd = { onResizeStop() },
                            onDragCancel = { onResizeStop() },
                            onDrag = { change, amount -> change.consume(); onResize(amount) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_resize),
                        contentDescription = "Resize widget",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** A hosted widget. Rebuilt only when the id changes; resized when the cell does. */
@Composable
private fun WidgetView(widgets: LauncherWidgets, appWidgetId: Int, widthDp: Int, heightDp: Int) {
    if (widgets.isDead(appWidgetId)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Widget no longer installed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    key(appWidgetId) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    widgets.createView(ctx, appWidgetId, widthDp, heightDp)?.let { addView(it) }
                }
            },
            update = { frame ->
                val hosted = frame.getChildAt(0) as? android.appwidget.AppWidgetHostView
                hosted?.let { widgets.resize(it, appWidgetId, widthDp, heightDp) }
            },
        )
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: Dp) {
    val shape = MaterialTheme.shapes.medium
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/** A built-in tile drawn to the same weight as a real app icon. */
@Composable
private fun GlyphIcon(iconRes: Int, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(size * GLYPH_FRACTION),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PageDots(pageCount: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pageCount) { index ->
            val selected = index == current
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (selected) 20.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
        }
    }
}

/**
 * How far past the last column an item must be dragged before the page turns,
 * as a fraction of a cell. Large enough that the last column is comfortably
 * reachable, small enough that crossing pages stays an easy gesture.
 */
private const val PAGE_EDGE = 0.75f

/** Lifted and slightly translucent, so it is obvious which item is being carried. */
private const val DRAG_SCALE = 1.08f
private const val DRAG_ALPHA = 0.85f

/** A glyph tile's icon, as a fraction of the app-icon size it stands in for. */
private const val GLYPH_FRACTION = 0.55f

/**
 * Height a cell keeps back from its icon for what sits below and around it: the
 * label's own line box, the gap above it, and the cell's padding top and bottom.
 * Measured against the real thing rather than guessed - too small a reserve and
 * the label is clipped off the bottom of every short row.
 */
private val LABEL_ROOM = 34.dp
