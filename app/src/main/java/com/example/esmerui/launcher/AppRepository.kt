package com.example.esmerui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Reads the set of launchable apps from the package manager. */
object AppRepository {

    /**
     * Hidden screens are only ever drawn in the settings picker, at this size. This
     * unit ships eighty-odd of them, and rasterising each at the grid's size was
     * holding a dozen megabytes of pixels for icons the home screen never shows.
     */
    private const val PICKER_ICON_DP = 32

    /**
     * [gridIconPx] is the size the home grid draws at. Icons are rasterised at that
     * size exactly - the same pixels the screen would end up with had they been
     * scaled down from something larger, without keeping the larger one around.
     */
    suspend fun loadApps(context: Context, gridIconPx: Int): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val pickerPx = (PICKER_ICON_DP * context.resources.displayMetrics.density).roundToInt()

        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        val launchableComponents = launchable.map { it.componentName() }.toSet()

        // MAIN activities with no LAUNCHER category. BYD ships its climate panel,
        // radio and camera screens this way, so nothing lists them by default.
        val hidden = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN), 0)
            .filterNot { it.componentName() in launchableComponents }
            .filter { it.activityInfo.exported && it.activityInfo.enabled }

        (launchable.map { it to false } + hidden.map { it to true })
            // Hide ourselves: the launcher is not an app you open from the launcher.
            .filterNot { (info, _) -> info.activityInfo.packageName == context.packageName }
            .map { (resolveInfo, isHidden) ->
                AppEntry(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = resolveInfo.activityInfo.packageName,
                    component = resolveInfo.componentName(),
                    icon = runCatching {
                        resolveInfo.loadIcon(pm).toImageBitmap(if (isHidden) pickerPx else gridIconPx)
                    }.getOrNull(),
                    isHiddenScreen = isHidden,
                )
            }
            .sortedWith(compareBy({ it.isHiddenScreen }, { it.label.lowercase() }))
            .also {
                // Every loadIcon opens the other app's APK - its resource table
                // mapped, its icon's full-size layers decoded - just to rasterise a
                // thumbnail from them. Across well over a hundred apps that is some
                // eighty megabytes, all garbage the moment the thumbnails exist,
                // and all held only weakly by the platform's resource caches. A
                // launcher allocates so little at rest that no collection would
                // ever come on its own, so one is run here, once, on the loader.
                //
                // Runtime.gc(), not System.gc(): on Android the latter is a no-op
                // unless runFinalization() was just called - it merely flags a
                // request - which is how the first attempt at this freed nothing.
                Runtime.getRuntime().gc()
            }
    }

    /** Starts [entry] as a fresh task, the way a home screen is expected to. */
    fun launch(context: Context, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // Component went away between load and tap - fall back to the package default.
                pmLaunchIntent(context, entry.packageName)?.let { context.startActivity(it) }
            }
    }

    /** Opens the system "App info" page, the usual long-press affordance. */
    fun openAppInfo(context: Context, entry: AppEntry) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun pmLaunchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ResolveInfo.componentName(): ComponentName =
        ComponentName(activityInfo.packageName, activityInfo.name)

    private fun Drawable.toImageBitmap(px: Int): ImageBitmap =
        toBitmap(width = px, height = px).asImageBitmap()
}
