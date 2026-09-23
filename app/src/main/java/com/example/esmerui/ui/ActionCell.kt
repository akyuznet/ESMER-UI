package com.example.esmerui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.esmerui.car.CarBus
import com.example.esmerui.car.CarInfo
import com.example.esmerui.car.CarStatus
import com.example.esmerui.launcher.NavAction
import com.example.esmerui.navbar.NavActionIcons
import com.example.esmerui.navbar.NavActionState
import com.example.esmerui.navbar.NavPalette
import kotlin.math.roundToInt

/**
 * A bar action drawn as a home-grid cell.
 *
 * The bar builds its cells from Android views inside the service; this is the
 * Compose counterpart for the same actions on the grid. Colours and state come from
 * [NavPalette] and [NavActionState] rather than being decided again here, so a gear
 * that reads red on the bar reads red here too.
 *
 * Live values recompose off [CarInfo.revision], which the poll bumps once per pass.
 */
@Composable
fun ActionCell(
    action: NavAction,
    iconSize: Dp,
    onPress: (NavAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        action.isGroup -> StepperCell(action, iconSize, onPress, modifier)
        action.isReadout -> ReadoutCell(action, iconSize, modifier)
        else -> ButtonCell(action, iconSize, onPress, modifier)
    }
}

@Composable
private fun ReadoutCell(action: NavAction, iconSize: Dp, modifier: Modifier = Modifier) {
    // The tyre block is four readings around a wheel, not one value.
    if (action.isTyres) {
        TyreCell(action, iconSize, modifier)
        return
    }

    // Subscribed here rather than in the wrapper above. The car's values are plain
    // volatile fields that Compose cannot see, so the poll's counter is what marks
    // them stale - and it has to be read in the same scope that reads the values,
    // or this composable is skipped on its unchanged arguments and the reading
    // simply never changes on screen.
    val poll = observePoll()

    val tint = Color(remember(poll, action) { NavPalette.infoColour(action) })
    val value = remember(poll, action) { CarInfo.valueFor(action) }
    val caption = remember(poll, action) { CarInfo.captionFor(action) }
    val iconRes = NavActionIcons.onBar(action)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize * 0.62f),
                tint = tint,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = tint,
                maxLines = 1,
            )
        }
        if (!caption.isNullOrEmpty()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Four corners around a wheel, so a soft tyre is identifiable at a glance. */
@Composable
private fun TyreCell(action: NavAction, iconSize: Dp, modifier: Modifier = Modifier) {
    val poll = observePoll()
    val kpa = remember(poll) { CarInfo.tyrePressures }

    @Composable
    fun corner(index: Int) {
        val v = kpa?.getOrNull(index)?.takeIf { it > 0 }
        Text(
            text = when {
                v == null -> CarInfo.NO_VALUE
                action == NavAction.INFO_TYRES_PSI -> (v / KPA_PER_PSI).roundToInt().toString()
                action == NavAction.INFO_TYRES_KPA -> v.toString()
                else -> String.format("%.1f", v / KPA_PER_BAR)
            },
            style = MaterialTheme.typography.labelLarge.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = Color(NavPalette.tyreColour(v)),
            maxLines = 1,
        )
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            corner(0); corner(2)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(NavActionIcons.of(action)),
            contentDescription = null,
            modifier = Modifier.size(iconSize * 0.5f),
            tint = Color(NavPalette.ICON),
        )
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            corner(1); corner(3)
        }
    }
}

/** A [-] value [+] cluster, the shape the head unit's own climate bar uses. */
@Composable
private fun StepperCell(
    action: NavAction,
    iconSize: Dp,
    onPress: (NavAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val poll = observePoll()
    val steps = action.steps ?: return
    // Each stepper follows the switch behind it: the climate pair go green with the
    // blower, the HUD pair with the display, so a level reads as live or idle.
    val tint = Color(
        remember(poll, action) {
            val live = if (action.isHud) CarStatus.hudOn else CarStatus.climateOn
            if (live) NavPalette.ACTIVE else NavPalette.ICON
        }
    )
    val reading = remember(poll, action) { CarStatus.stepperLabel(action) }
    val (down, up) = steps

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(com.example.esmerui.R.drawable.ic_step_remove, iconSize) { onPress(down) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = reading,
                style = MaterialTheme.typography.headlineSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = tint,
                maxLines = 1,
            )
            Text(
                text = action.stepperCaption,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = tint,
                maxLines = 1,
            )
        }
        StepButton(com.example.esmerui.R.drawable.ic_step_add, iconSize) { onPress(up) }
    }
}

@Composable
private fun StepButton(iconRes: Int, iconSize: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(iconSize * 0.62f)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(iconSize * 0.45f),
            tint = Color(NavPalette.ICON),
        )
    }
}

@Composable
private fun ButtonCell(
    action: NavAction,
    iconSize: Dp,
    onPress: (NavAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val poll = observePoll()
    val lit = remember(poll, action) {
        NavActionState.showsActiveColour(action) && NavActionState.isActive(action)
    }
    val iconRes = remember(poll, action) { NavActionIcons.current(action) }
    val tint = Color(if (lit) NavPalette.ACTIVE else NavPalette.ICON)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = action.label,
            modifier = Modifier.size(iconSize * 0.72f),
            tint = tint,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The poll's tick counter, for use as a [remember] key by anything reading the
 * car's values.
 *
 * It has to return the counter, and callers have to use what it returns. This was
 * a `Unit` function that just read the value, and that quietly froze every readout
 * on the grid: a composable returning `Unit` is restartable, so the compiler gives
 * it a recompose scope of its own and the read enrols *that* scope instead of the
 * caller's. Every tick then invalidated this function alone, which recomposed, read
 * the counter again and did nothing with it, while the cell holding the text was
 * never told anything had changed. A composable that returns a value cannot be
 * restarted on its own, so the read lands in the caller's scope, where it belongs.
 */
@Composable
private fun observePoll(): Int = CarInfo.revision

/** The tyre readouts share a layout but differ only in the unit they print. */
private val NavAction.isTyres: Boolean
    get() = this == NavAction.INFO_TYRES ||
        this == NavAction.INFO_TYRES_PSI ||
        this == NavAction.INFO_TYRES_KPA

private const val KPA_PER_PSI = 6.894757f
private const val KPA_PER_BAR = 100f
