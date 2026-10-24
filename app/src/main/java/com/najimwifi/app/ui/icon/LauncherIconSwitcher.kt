package com.najimwifi.app.ui.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * The icon displayed by the launcher is tied to the effective Compose theme,
 * not Android's independent system night resource qualifier.
 *
 * Exactly one alias is enabled. Neither alias contains a separate Activity,
 * and we never call recreate(), finish(), or startActivity() for icon changes.
 */
internal object LauncherIconSwitcher {
    private const val TAG = "NajimLauncherIcon"
    private const val DAY = ".LauncherDayAlias"
    private const val NIGHT = ".LauncherNightAlias"

    /** Serialized to prevent quick theme taps from interleaving component changes. */
    @Synchronized
    fun sync(context: Context, dark: Boolean) {
        try {
            val manager = context.packageManager
            val day = ComponentName(context.packageName, context.packageName + DAY)
            val night = ComponentName(context.packageName, context.packageName + NIGHT)

            val dayState = manager.getComponentEnabledSetting(day)
            val nightState = manager.getComponentEnabledSetting(night)
            val dayActive = dayState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            // Night is disabled by default in the manifest.
            val nightActive = nightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (dark == nightActive && dayActive != nightActive) return

            val enable = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val disable = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val flags = PackageManager.DONT_KILL_APP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: atomically update both aliases (no gap with zero icons).
                manager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(day, if (dark) disable else enable, flags),
                        PackageManager.ComponentEnabledSetting(night, if (dark) enable else disable, flags)
                    )
                )
            } else {
                // Android 11/12: enable the new launcher entry BEFORE disabling the old one.
                if (dark) {
                    if (!nightActive) manager.setComponentEnabledSetting(night, enable, flags)
                    if (dayActive) manager.setComponentEnabledSetting(day, disable, flags)
                } else {
                    if (!dayActive) manager.setComponentEnabledSetting(day, enable, flags)
                    if (nightActive) manager.setComponentEnabledSetting(night, disable, flags)
                }
            }
        } catch (e: RuntimeException) {
            // If a launcher/ROM refuses component switching, preserve the app's UI and data.
            Log.w(TAG, "Launcher did not accept icon change; application remains usable", e)
        }
    }
}
