package dev.pocketprl.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icon choices. Each maps to an `<activity-alias>` in the manifest, so
 * the launcher entry can be moved between icons at runtime.
 */
enum class AppIcon(val id: String, val component: String) {
    DEFAULT("default", "dev.pocketprl.LauncherDefault"),
    /** Single-colour mark that flips with the system theme. */
    MONO("mono", "dev.pocketprl.LauncherMono"),
    LIGHT("light", "dev.pocketprl.LauncherLight"),
    DARK("dark", "dev.pocketprl.LauncherDark"),
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

    private fun stateOf(pm: PackageManager, context: Context, icon: AppIcon): Int =
        runCatching { pm.getComponentEnabledSetting(ComponentName(context.packageName, icon.component)) }.getOrDefault(0)

    private fun set(pm: PackageManager, context: Context, icon: AppIcon, state: Int) {
        runCatching { pm.setComponentEnabledSetting(ComponentName(context.packageName, icon.component), state, PackageManager.DONT_KILL_APP) }
    }
}
