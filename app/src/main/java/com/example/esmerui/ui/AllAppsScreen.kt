package com.example.esmerui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.esmerui.R
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.HomeCell
import com.example.esmerui.launcher.HomeItem
import com.example.esmerui.launcher.HomeLayout
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.findByKey

/**
 * The drawer: everything on the unit, in one scrolling grid.
 *
 * Unlike the home screen this is not arranged and not filtered - it lists the
 * launchable apps and then the eighty-odd MAIN activities with no LAUNCHER category
 * (BYD's climate panel, radio and camera screens), which no launcher shows and which
 * are some of the most useful things on the unit.
 *
 * It doubles as the way things get onto the home screen: in [pickMode] a tap adds
 * or removes rather than launching, which is where the "+" tile leads.
 */
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    pickMode: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember(pickMode) { mutableStateOf(pickMode) }

    val columns = settings.columns
    val rows = settings.rows
    val stored = settings.homeCells
    val cells = remember(stored, apps, columns, rows) {
        stored ?: HomeLayout.fromList(
            apps.filterNot { it.isHiddenScreen }.map { HomeItem.App(it.component) } +
                HomeItem.Settings,
            columns, rows,
        )
    }
    val onHome = remember(cells) { cells.map { it.item.key }.toSet() }

    fun toggle(item: HomeItem) {
        if (cells.any { it.item.key == item.key }) {
            settings.setHomeCells(cells.filterNot { it.item.key == item.key })
            return
        }
        // Dropped into the first gap on the existing pages; if they are full, one
        // more page is added rather than refusing the add.
        val pages = maxOf(settings.homePages, (cells.maxOfOrNull { it.page } ?: 0) + 1)
        val spot = HomeLayout.firstFree(cells, columns, rows, pages)
            ?: run {
                settings.homePages = pages + 1
                Triple(pages, 0, 0)
            }
        settings.setHomeCells(cells + HomeCell(spot.first, spot.second, spot.third, 1, 1, item))
    }

    val visible = remember(apps) { apps.filterNot { it.isHiddenScreen } }
    val hidden = remember(apps) { apps.filter { it.isHiddenScreen } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "All apps",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (picking) {
                        "Tap to add or remove from the home screen"
                    } else {
                        "${visible.size} apps · ${hidden.size} built-in screens"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (picking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (picking) "Done" else "Edit home",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { picking = !picking }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Settings first, so it is always reachable even if its home tile was
            // removed - otherwise taking it off the grid would strand the settings.
            item(key = HomeItem.SETTINGS_KEY) {
                DrawerTile(
                    label = "Settings",
                    icon = null,
                    glyph = R.drawable.ic_settings,
                    iconSizeDp = settings.iconSizeDp,
                    onHome = HomeItem.SETTINGS_KEY in onHome,
                    picking = picking,
                    onClick = {
                        if (picking) toggle(HomeItem.Settings) else onOpenSettings()
                    },
                    onLongClick = {},
                )
            }

            items(visible, key = { it.key }) { app ->
                DrawerTile(
                    label = app.label,
                    icon = app.icon,
                    glyph = null,
                    iconSizeDp = settings.iconSizeDp,
                    onHome = app.key in onHome,
                    picking = picking,
                    onClick = {
                        if (picking) toggle(HomeItem.App(app.component)) else onLaunch(app)
                    },
                    onLongClick = { onAppInfo(app) },
                )
            }

            if (hidden.isNotEmpty()) {
                item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                    SectionHeader("Built-in screens")
                }
                items(hidden, key = { it.key }) { app ->
                    DrawerTile(
                        // Several hidden screens share one app label (Media Center is
                        // main, radio and BT music), so the class names them apart.
                        label = app.component.className.substringAfterLast('.'),
                        icon = app.icon,
                        glyph = null,
                        iconSizeDp = settings.iconSizeDp,
                        onHome = app.key in onHome,
                        picking = picking,
                        onClick = {
                            if (picking) toggle(HomeItem.App(app.component)) else onLaunch(app)
                        },
                        onLongClick = { onAppInfo(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun DrawerTile(
    label: String,
    icon: ImageBitmap?,
    glyph: Int?,
    iconSizeDp: Int,
    onHome: Boolean,
    picking: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(iconSizeDp.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (glyph != null) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    glyph != null -> Icon(
                        painter = painterResource(glyph),
                        contentDescription = null,
                        modifier = Modifier.size((iconSizeDp * 0.55f).dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )

                    icon != null -> Image(
                        bitmap = icon,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        // Only while picking: outside that mode it is noise on every tile.
        if (picking && onHome) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = "On the home screen",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
