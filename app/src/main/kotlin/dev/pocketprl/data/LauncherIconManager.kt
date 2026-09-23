package dev.pocketprl.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.pocketprl.R

/**
 * Launcher icon choices. Each maps to an `<activity-alias>` in the manifest, so
 * the launcher entry can be moved between icons at runtime.
 *
 * [iconRes] is the same adaptive icon the alias declares. It is kept here so the
 * chosen icon can also be rendered onto the system surfaces the alias cannot
 * reach — the task-switcher entry and the notification large icon — which read
 * the activity/application icon rather than the launcher alias.
 */
enum class AppIcon(
    val id: String,
    val component: String,
    @DrawableRes val iconRes: Int,
) {
    DEFAULT("default", "dev.pocketprl.LauncherDefault", R.mipmap.ic_launcher),
    /** Single-colour mark that flips with the system theme. */
    MONO("mono", "dev.pocketprl.LauncherMono", R.mipmap.ic_launcher_mono),
    LIGHT("light", "dev.pocketprl.LauncherLight", R.mipmap.ic_launcher_light),
    DARK("dark", "dev.pocketprl.LauncherDark", R.mipmap.ic_launcher_dark),
    ;

    companion object {
        fun fromId(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object LauncherIconManager {
    private const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    private const val DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** Enables the chosen alias and disables the rest, without killing the process. */
    fun apply(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        // Enable the target first, so the app is never briefly left without a launcher entry.
        set(pm, context, icon, ENABLED)
        for (other in AppIcon.entries) if (other != icon) set(pm, context, other, DISABLED)
    }

    /**
     * Applies [icon] only when it is not already the enabled alias. A fresh
     * install has no explicit component state (DEFAULT), so this runs the first
     * time and is a no-op afterwards.
     */
    fun applyIfNeeded(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        val already = stateOf(pm, context, icon) == ENABLED &&
            AppIcon.entries.all { it == icon || stateOf(pm, context, it) == DISABLED }
        if (!already) apply(context, icon)
    }

    /**
     * The chosen icon as a square bitmap for the surfaces that read the
     * application/activity icon: the task switcher ([android.app.Activity.setTaskDescription])
     * and the notification large icon. Null when the drawable cannot be read.
     */
    fun bitmap(context: Context, icon: AppIcon, sizePx: Int): Bitmap? = runCatching {
        val drawable = ContextCompat.getDrawable(context, icon.iconRes) ?: return@runCatching null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    private fun stateOf(pm: PackageManager, context: Context, icon: AppIcon): Int =
        runCatching { pm.getComponentEnabledSetting(ComponentName(context.packageName, icon.component)) }.getOrDefault(0)

    private fun set(pm: PackageManager, context: Context, icon: AppIcon, state: Int) {
        runCatching { pm.setComponentEnabledSetting(ComponentName(context.packageName, icon.component), state, PackageManager.DONT_KILL_APP) }
    }
}
