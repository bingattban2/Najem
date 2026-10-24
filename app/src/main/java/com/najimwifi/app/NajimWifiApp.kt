package com.najimwifi.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsControllerCompat
import com.najimwifi.app.data.settings.AppearanceMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.ui.navigation.RootNavGraph
import com.najimwifi.app.ui.theme.NajimWifiTheme
import com.najimwifi.app.ui.icon.LauncherIconSwitcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NajimWifiApp() {
    val hostActivity = LocalContext.current as? Activity
    val context = LocalContext.current.applicationContext
    val settingsStore = remember(context) { SettingsStore(context) }
    val preferences by settingsStore.settings.collectAsState(initial = null)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        NajimWifiTheme(appearance = preferences?.appearance ?: com.najimwifi.app.data.settings.AppearanceMode.SYSTEM) {
            val dark = when (preferences?.appearance ?: AppearanceMode.SYSTEM) {
                AppearanceMode.DARK -> true
                AppearanceMode.LIGHT -> false
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Never change launcher components while preferences are loading.
            // Only synchronize when effective appearance changes (including SYSTEM mode).
            // PackageManager work is off the UI thread; icon switching never recreates this Activity.
            if (preferences != null) {
                LaunchedEffect(dark) {
                    withContext(Dispatchers.IO) {
                        LauncherIconSwitcher.sync(context, dark)
                    }
                }
            }
            SideEffect {
                hostActivity?.let { activity ->
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        .isAppearanceLightStatusBars = !dark
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        .isAppearanceLightNavigationBars = !dark
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Wait for preferences to load: no onboarding flash or duplicate datastore collectors.
                preferences?.let { RootNavGraph(settings = it, settingsStore = settingsStore) }
            }
        }
    }
}
