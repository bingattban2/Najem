package com.najimwifi.app.ui.settings

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Business
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.data.di.AppContainer
import com.najimwifi.app.data.settings.NajimSettings
import com.najimwifi.app.data.settings.AppearanceMode
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.ui.security.LoginScreen
import com.najimwifi.app.ui.security.SecurityViewModel
import com.najimwifi.app.ui.theme.StitchPanel
import com.najimwifi.app.R
import android.os.Build
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch

/** One scrollable settings page: appearance, network label, login and encrypted backup. */
@Composable
fun SettingsScreen(
    securityViewModel: SecurityViewModel,
    settingsStore: SettingsStore,
    settings: NajimSettings,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Read the installed APK version, rather than hardcoding a label that
    // could become inaccurate after an update.
    val versionName = remember(context) {
        try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "غير معروف"
        } catch (_: Exception) { "غير معروف" }
    }
    var networkName by remember(settings.businessHeader) { mutableStateOf(settings.businessHeader) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineLarge)
        StitchPanel {
            Text("مظهر التطبيق", style = MaterialTheme.typography.titleLarge)
            Text("اختر الوضع المناسب؛ سيطبّق على جميع الشاشات فورًا.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val current = settings.appearance
            val modes = listOf(AppearanceMode.LIGHT, AppearanceMode.DARK, AppearanceMode.SYSTEM)
            val titles = listOf("نهاري", "ليلي", "تلقائي")
            val icons = listOf(Icons.Rounded.LightMode, Icons.Rounded.DarkMode,
                Icons.Rounded.SettingsBrightness)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = current == mode,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        onClick = { scope.launch { settingsStore.setAppearance(mode) } },
                        icon = { Icon(icons[index], null, Modifier.size(17.dp)) }
                    ) { Text(titles[index], style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        StitchPanel {
            Text("اسم الشبكة في التقارير", style = MaterialTheme.typography.titleLarge)
            Text("يظهر اسم التطبيق افتراضيًا في أعلى كشوف PDF؛ ويمكن استبداله باسم شبكتك.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(networkName, { networkName = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("اسم الشبكة (اختياري)") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Business, contentDescription = null) },
                shape = RoundedCornerShape(16.dp),
                colors = softOutlinedTextFieldColors()
)
            Button(onClick = {
                if (saving) return@Button
                saving = true
                scope.launch {
                    try {
                        settingsStore.updateBusinessHeader(networkName)
                        message = "تم حفظ اسم الشبكة للتقارير"
                    } catch (e: Exception) {
                        message = e.message ?: "تعذر الحفظ"
                    } finally { saving = false }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)) { Text("حفظ اسم الشبكة") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        StitchPanel {
            LoginScreen(viewModel = securityViewModel, embedded = true)
        }
        StitchPanel {
            BackupSettingsSection(container = container)
        }
        StitchPanel {
            Text("حول التطبيق", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            RowInfo("اسم التطبيق", stringResource(R.string.app_name))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            RowInfo("إصدار التطبيق", versionName)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text("فكرة وبرمجة /", modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text("سعيد بن قطبان", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

/** Read-only about rows avoid accidental edits to the APK identity. */
@Composable
private fun RowInfo(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold)
    }
}
