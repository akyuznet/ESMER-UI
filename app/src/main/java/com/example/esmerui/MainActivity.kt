package com.example.esmerui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.example.esmerui.ui.AllAppsScreen
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.esmerui.launcher.AppEntry
import com.example.esmerui.launcher.AppRepository
import com.example.esmerui.car.BydFeatures
import com.example.esmerui.car.CarStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.example.esmerui.launcher.LauncherSettings
import com.example.esmerui.launcher.NavPosition
import com.example.esmerui.launcher.findByKey
import com.example.esmerui.navbar.NavBarService
import com.example.esmerui.navbar.OverscanController
import com.example.esmerui.ui.AppWindow
import com.example.esmerui.ui.HomeScreen
import com.example.esmerui.ui.SettingsScreen
import com.example.esmerui.ui.theme.ESMERUITheme

class MainActivity : ComponentActivity() {

    /**
     * Which screen the intent that brought us here asked for, and a token that
     * changes with every such intent.
     *
     * The activity is singleTask, so the bar's buttons re-enter this same instance
     * through onNewIntent rather than creating another. The token is what lets a
     * second press of the same button be told apart from no press at all - without
     * it, pressing Home while already on Home would not navigate back.
     */
    private val route = mutableStateOf<String?>(null)
    private val routeToken = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readRoute(intent)
        setContent {
            ESMERUITheme {
                LauncherApp(route.value, routeToken.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRoute(intent)
    }

    private fun readRoute(intent: Intent?) {
        route.value = intent?.getStringExtra(EXTRA_SCREEN)
        routeToken.intValue++
    }

    companion object {
        const val EXTRA_SCREEN = "com.example.esmerui.SCREEN"
        const val SCREEN_ALL_APPS = "all_apps"
    }
}

private enum class Screen { HOME, ALL_APPS, SETTINGS }

/** Long enough to outlast a slider drag, short enough not to register as a wait. */
private const val RELOAD_SETTLE_MS = 400L

/**
 * Space to leave clear on [position], or zero when no bar is drawn there.
 *
 * Overscan has already shrunk this window by whatever it reserved on that edge, so
 * only the part of the bar it did not cover needs padding here. The two differ
 * per edge: where an app would reserve the system bar's own strip regardless, we
 * ask overscan for the excess only, which leaves the rest for the window to avoid.
 * Padding the full thickness on a covered edge would inset the grid twice; padding
 * nothing on an uncovered one puts the grid under the bar.
 */
private fun navInset(settings: LauncherSettings, position: NavPosition, density: Float): Dp {
    val bar = settings.navBar(position)
    // A folded bar leaves only a tab in the corner, which the grid can sit beside.
    if (!settings.navBarOccupiesEdge(position)) return 0.dp
    val covered = (OverscanController.reservedPx(position) / density).toInt()
    val uncovered = bar.thicknessDp - covered
    return if (uncovered <= 0) 0.dp else (uncovered + 24).dp
}

@Composable
private fun LauncherApp(route: String?, routeToken: Int) {
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Whether the drawer should open ready to add to the home screen. Only set by
    // the "+" tile; arriving from the bar's app button is just browsing.
    var pickOnOpen by remember { mutableStateOf(false) }

    // Every intent into this activity lands here: the app button asks for the
    // drawer, the Home button asks for nothing and so means home.
    LaunchedEffect(routeToken) {
        screen = if (route == MainActivity.SCREEN_ALL_APPS) Screen.ALL_APPS else Screen.HOME
        // Always browsing: only the "+" tile asks for the adding mode, and it gets
        // there without an intent. Left set, the app button would open the drawer
        // still in whatever mode the last "+" put it in.
        pickOnOpen = false
    }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // Bumped whenever the installed set changes, to re-trigger the load.
    var reloadToken by remember { mutableIntStateOf(0) }

    // Keyed on the icon size as well, since the icons are rasterised at exactly the
    // size the grid draws. A slider drag restarts this on every step; the pause
    // means only the step it settles on is rasterised.
    val density = LocalDensity.current.density
    val iconSizeDp = settings.iconSizeDp
    LaunchedEffect(reloadToken, iconSizeDp) {
        if (apps.isNotEmpty()) delay(RELOAD_SETTLE_MS)
        apps = AppRepository.loadApps(context, gridIconPx = (iconSizeDp * density).roundToInt())
        isLoading = false
    }

    // Keep the grid honest when apps are installed, removed or updated.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                reloadToken++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Package broadcasts are protected system broadcasts, so NOT_EXPORTED is correct
        // here and still satisfies the API 34+ flag requirement.
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // The bar is a separate overlay window, so it has to be (re)started whenever
    // the launcher comes up - after a reboot this is what brings it back.
    LaunchedEffect(Unit) {
        NavBarService.sync(context)
        // Network call - must not run on the main thread.
        withContext(Dispatchers.IO) {
            // The feature bus must be up before the first refresh, which reads
            // airflow from it.
            BydFeatures.init(context)
            CarStatus.refresh()
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    // Each bar is an overlay window and reports no window inset, so every occupied
    // edge has to be reserved by hand or a bar sits on top of the content.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(
                bottom = navInset(settings, NavPosition.BOTTOM, density),
                top = navInset(settings, NavPosition.TOP, density),
                start = navInset(settings, NavPosition.LEFT, density),
                end = navInset(settings, NavPosition.RIGHT, density),
            )
    ) {
        when (screen) {
            // The home grid is arranged by hand, so it gets the whole list and
            // picks out what was put on it - hidden screens included, if they were.
            Screen.HOME -> HomeScreen(
                apps = apps,
                settings = settings,
                isLoading = isLoading,
                onLaunch = { AppRepository.launch(context, it) },
                onAppInfo = { AppRepository.openAppInfo(context, it) },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenAllApps = { pick ->
                    pickOnOpen = pick
                    screen = Screen.ALL_APPS
                },
            )

            Screen.ALL_APPS -> AllAppsScreen(
                apps = apps,
                settings = settings,
                pickMode = pickOnOpen,
                onLaunch = { AppRepository.launch(context, it) },
                onAppInfo = { AppRepository.openAppInfo(context, it) },
                onOpenSettings = { screen = Screen.SETTINGS },
                onBack = { screen = Screen.HOME },
            )

            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                apps = apps,
                onBack = { screen = Screen.HOME },
            )
        }

        // The window floats over the home screen only - settings stays unobstructed
        // so the window can always be reconfigured or switched off.
        val windowed = apps.findByKey(settings.windowComponent)
        if (screen == Screen.HOME && settings.windowEnabled && windowed != null) {
            AppWindow(
                component = windowed.component,
                appLabel = windowed.label,
                settings = settings,
                onClose = { settings.windowEnabled = false },
            )
        }
    }
}
