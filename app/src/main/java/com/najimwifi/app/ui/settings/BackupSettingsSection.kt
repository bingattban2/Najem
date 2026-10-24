package com.najimwifi.app.ui.settings

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.najimwifi.app.backup.BackupPreview
import com.najimwifi.app.backup.BackupScheduler
import com.najimwifi.app.backup.DriveAuthorization
import com.najimwifi.app.backup.DriveBackupClient
import com.najimwifi.app.backup.DriveBackupVersion
import com.najimwifi.app.data.di.AppContainer
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Separate from login settings; never persists recovery phrases or OAuth tokens. */
@Composable
fun BackupSettingsSection(container: AppContainer) {
    val context = LocalContext.current.applicationContext
    val local = remember(container) { container.localBackupManager }
    val scheduler = remember(context) { BackupScheduler(context) }
    val drive = remember(context) { DriveBackupClient(context) }
    val authorization = remember(context) { DriveAuthorization(context) }
    val scope = rememberCoroutineScope()

    var phrase by remember { mutableStateOf("") }
    var repeatPhrase by remember { mutableStateOf("") }
    var phraseVisible by remember { mutableStateOf(false) }
    var repeatVisible by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    var issue by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<DriveBackupVersion>>(emptyList()) }
    var currentStatus by remember { mutableStateOf(scheduler.status()) }
    var scheduleExpanded by remember { mutableStateOf(currentStatus.enabled) }
    var weekly by remember { mutableStateOf(currentStatus.weekly) }
    var retention by remember { mutableStateOf(currentStatus.keep) }
    var allowCleanup by remember { mutableStateOf(currentStatus.allowCleanup) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTemp by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var restoreAnswer by remember { mutableStateOf("") }
    var restartNeeded by remember { mutableStateOf(false) }

    fun requirePhrase() {
        require(phrase.length in 12..256 && phrase == repeatPhrase) {
            "اكتب عبارة استرداد من 12 حرفًا على الأقل، وكررها للتأكيد"
        }
    }

    fun task(success: String? = null, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        issue = null
        notice = null
        scope.launch {
            try {
                work()
                if (success != null) notice = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                issue = e.message ?: "تعذر إكمال عملية النسخ"
            } finally {
                busy = false
                currentStatus = scheduler.status()
            }
        }
    }


    fun changeCleanupConsent(approved: Boolean) {
        if (approved) {
            // Scheduling still requires a separate, explicit Save/Enable action.
            allowCleanup = true
        } else {
            try {
                scheduler.revokeCleanupConsent()
                currentStatus = scheduler.status()
                allowCleanup = false
            } catch (failure: Exception) {
                allowCleanup = currentStatus.allowCleanup
                issue = failure.message ?: "تعذر إلغاء إذن التنظيف"
            }
        }
    }

    fun updateSchedule(enabled: Boolean) {
        if (busy) return
        if (!enabled) {
            try {
                scheduler.disable()
                currentStatus = scheduler.status()
                scheduleExpanded = false
                notice = "تم إيقاف النسخ المجدول"
            } catch (failure: Exception) {
                issue = failure.message ?: "تعذر إيقاف النسخ المجدول"
            }
            return
        }
        try {
            require(token != null) { "اتصل بحساب Google Drive قبل تفعيل الجدولة" }
            requirePhrase()
            val secret = phrase.toCharArray()
            try { scheduler.enable(secret, weekly, retention, allowCleanup) }
            finally { secret.fill('\u0000') }
            currentStatus = scheduler.status()
            scheduleExpanded = true
            issue = null
            notice = if (allowCleanup) "تم تفعيل النسخ مع موافقة حذف الأقدم" else "تم تفعيل النسخ دون حذف الإصدارات القديمة"
        } catch (failure: Exception) {
            issue = failure.message ?: "تعذر تفعيل النسخ المجدول"
        }
    }

    val authorizeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { outcome: ActivityResult ->
        if (outcome.resultCode == Activity.RESULT_OK) {
            runCatching { authorization.finishAuthorization(outcome.data) }
                .onSuccess { token = it; notice = "تم تفويض الوصول إلى مجلد التطبيق في Drive" }
                .onFailure { issue = it.message ?: "فشل التفويض" }
        } else {
            issue = "لم يمنح المستخدم إذن الوصول إلى Drive"
        }
    }

    val createLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) task("حُفظت النسخة المحلية المشفرة") {
            requirePhrase()
            val chars = phrase.toCharArray()
            try { local.createBackup(uri, chars) } finally { chars.fill('\u0000') }
        }
    }

    val openLocal = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) task {
            require(phrase.length >= 12) { "أدخل عبارة استرداد النسخة أولًا" }
            pendingUri = uri
            pendingTemp = null
            val chars = phrase.toCharArray()
            try { preview = local.inspectBackup(uri, chars) }
            finally { chars.fill('\u0000') }
        }
    }

    DisposableEffect(Unit) {
        onDispose { pendingTemp?.delete() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("النسخ الاحتياطي والاستعادة", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = phrase, onValueChange = { phrase = it },
            label = { Text("عبارة الاسترداد") },
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            trailingIcon = { IconButton(onClick = { phraseVisible = !phraseVisible }) {
                Icon(if (phraseVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (phraseVisible) "إخفاء" else "إظهار")
            } },
            visualTransformation = if (phraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp),
            colors = softOutlinedTextFieldColors()
)
        OutlinedTextField(
            value = repeatPhrase, onValueChange = { repeatPhrase = it },
            label = { Text("تأكيد عبارة الاسترداد للتصدير والجدولة") },
            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            trailingIcon = { IconButton(onClick = { repeatVisible = !repeatVisible }) {
                Icon(if (repeatVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (repeatVisible) "إخفاء" else "إظهار")
            } },
            visualTransformation = if (repeatVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp),
            colors = softOutlinedTextFieldColors()
)
        OutlinedButton(
            enabled = !busy,
            onClick = {
                try {
                    requirePhrase()
                    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                        .withZone(ZoneOffset.UTC).format(Instant.now())
                    createLocal.launch("Najim_${timestamp}.njbak")
                } catch (e: Exception) { issue = e.message }
            }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("حفظ نسخة مشفرة على الهاتف") }
        OutlinedButton(
            enabled = !busy,
            onClick = { openLocal.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("اختيار نسخة محلية لمعاينة الاستعادة") }

        HorizontalDivider()
        Text("Google Drive — مجلد التطبيق المخفي", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(
            onClick = {
                if (busy) return@FilledTonalButton
                issue = null
                authorization.authorize(
                    resolve = { authorizeLauncher.launch(it) },
                    success = { token = it; notice = "صلاحية Drive جاهزة" },
                    failure = { issue = it }
                )
            }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Cloud, null)
            Text(if (token == null) "  الاتصال بـ Google Drive" else "  تجديد تفويض Drive")
        }
        Button(
            enabled = !busy && token != null,
            onClick = {
                task {
                    requirePhrase()
                    val access = requireNotNull(token)
                    val dir = File(context.cacheDir, "backup_uploads")
                        .apply { require(isDirectory || mkdirs()) }
                    val archive = File.createTempFile("sending_", ".njbak", dir)
                    val chars = phrase.toCharArray()
                    try {
                        local.createBackup(Uri.fromFile(archive), chars)
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                            .withZone(ZoneOffset.UTC).format(Instant.now())
                        val name = "Najim_${stamp}_${UUID.randomUUID().toString().take(8)}.njbak"
                        drive.upload(access, archive, name)
                        notice = if (allowCleanup) {
                            runCatching { drive.prune(access, retention) }.fold(
                                onSuccess = { "تم الرفع؛ حُذف $it من الإصدارات الأقدم حسب اختيارك" },
                                onFailure = { "تم الرفع بنجاح؛ تعذر تنظيف الإصدارات القديمة" }
                            )
                        } else {
                            "تم الرفع بنجاح؛ لم تُحذف أي نسخة قديمة"
                        }
                        versions = drive.list(access)
                    } finally {
                        chars.fill('\u0000')
                        archive.delete()
                    }
                }
            }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.CloudUpload, null)
            Text("  إنشاء نسخة الآن ورفعها إلى Drive")
        }
        OutlinedButton(
            enabled = !busy && token != null,
            onClick = { task { versions = drive.list(requireNotNull(token)) } },
            modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Cloud, null)
            Text("  عرض إصدارات النسخ في Drive")
        }

        versions.forEach { item ->
            Card(Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    Text("الحجم: ${item.size} بايت | ${item.createdTime}")
                    OutlinedButton(enabled = !busy, onClick = {
                        task {
                            require(phrase.length >= 12) { "أدخل عبارة استرداد هذا الإصدار" }
                            val file = drive.download(requireNotNull(token), item)
                            pendingTemp?.delete()
                            pendingTemp = file
                            pendingUri = Uri.fromFile(file)
                            val chars = phrase.toCharArray()
                            try { preview = local.inspectBackup(requireNotNull(pendingUri), chars) }
                            finally { chars.fill('\u0000') }
                        }
                    },
        shape = RoundedCornerShape(16.dp)) { Text("تنزيل هذا الإصدار ومعاينة استعادته") }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).clickable { scheduleExpanded = !scheduleExpanded }) {
                Text("النسخ الاحتياطي المجدول", style = MaterialTheme.typography.titleMedium)
                Text(if (currentStatus.enabled) "الحالة: مفعّل" else "الحالة: متوقف",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scheduleExpanded = !scheduleExpanded }) {
                Icon(Icons.Rounded.ExpandMore, contentDescription = "إظهار خيارات الجدولة")
            }
            Switch(checked = currentStatus.enabled, enabled = !busy,
                onCheckedChange = ::updateSchedule)
        }
        AnimatedVisibility(visible = scheduleExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("التكرار", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !weekly, onClick = { weekly = false },
                        modifier = Modifier.weight(1f), label = { Text("يومي") },
                        leadingIcon = if (!weekly) ({ Icon(Icons.Rounded.Check, null,
                            Modifier.size(18.dp)) }) else null)
                    FilterChip(selected = weekly, onClick = { weekly = true },
                        modifier = Modifier.weight(1f), label = { Text("أسبوعي") },
                        leadingIcon = if (weekly) ({ Icon(Icons.Rounded.Check, null,
                            Modifier.size(18.dp)) }) else null)
                }
                Text("عدد الإصدارات المحفوظة في Drive",
                    style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(3, 5, 10, 20).forEach { count ->
                        FilterChip(selected = retention == count, onClick = { retention = count },
                            label = { Text(count.toString()) },
                            leadingIcon = if (retention == count) ({ Icon(Icons.Rounded.Check, null,
                                Modifier.size(18.dp)) }) else null)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = allowCleanup, role = Role.Checkbox,
                        onValueChange = ::changeCleanupConsent
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = allowCleanup, onCheckedChange = null)
                    Text("أوافق على حذف الإصدارات الأقدم نهائيًا بعد نجاح الرفع؛ " +
                        "تبقى آخر $retention نسخ مشفرة في Drive",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                if (currentStatus.enabled && (allowCleanup != currentStatus.allowCleanup ||
                        retention != currentStatus.keep || weekly != currentStatus.weekly)) {
                    Text("توجد تغييرات غير محفوظة في إعدادات الجدولة.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(currentStatus.result, style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !busy && token != null,
                    onClick = { updateSchedule(true) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (currentStatus.enabled) "حفظ إعدادات الجدولة" else "تفعيل النسخ المجدول")
                }
            }
        }
        TextButton(onClick = { currentStatus = scheduler.status() }) { Text("تحديث حالة آخر نسخة") }
        if (busy) Text("جارٍ تنفيذ العملية...")
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (restartNeeded) Text(
            "نجحت الاستعادة. أغلق التطبيق وافتحه مجددًا لتحديث جميع الشاشات.",
            color = MaterialTheme.colorScheme.primary
        )
    }

    preview?.let { ready ->
        AlertDialog(
            onDismissRequest = { if (!busy) { preview = null; restoreAnswer = "" } },
            title = { Text("معاينة النسخة قبل استبدال البيانات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("المعرّف: ${ready.manifest.backupId}")
                    Text("تاريخ النسخة: ${java.time.Instant.ofEpochMilli(ready.manifest.createdAtMillis)}")
                    Text("العملاء: ${ready.totalCustomers} | الباقات: " +
                        "${ready.manifest.tableCounts["packages"] ?: 0L}")
                    Text("القيود: ${ready.totalFinancialEntries}")
                    Text(
                        if (ready.includesPreferences)
                            "تحتوي النسخة على اسم المنشأة وإعدادات التنبيهات. " +
                                "إشعارات الهاتف تحتاج موافقة جديدة بعد الاستعادة."
                        else
                            "نسخة قديمة: تحتوي على قاعدة البيانات فقط؛ " +
                                "ستبقى إعدادات هذا الجهاز دون تغيير."
                    )
                    Text("الحركات: ${ready.totalCardMovements} | المحاسبات: ${ready.totalSettlements}")
                    Text("ستُستبدل بيانات المشروع الحالية بالكامل. تُنشأ نسخة إنقاذ مشفرة أولًا. " +
                        "لن تتغير كلمة دخول التطبيق أو مفتاح SQLCipher أو صلاحيات Google. " +
                        "قد تتطلب الإعدادات إعادة فتح التطبيق لتحديث الواجهات.")
                    OutlinedTextField(
                        value = restoreAnswer,
                        onValueChange = { restoreAnswer = it },
                        label = { Text("للتأكيد اكتب: استعادة") },
                        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                        colors = softOutlinedTextFieldColors()
)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && restoreAnswer == "استعادة",
                    onClick = {
                        task {
                            val chars = phrase.toCharArray()
                            try {
                                val result = local.restoreBackup(
                                    requireNotNull(pendingUri), chars, ready, restoreAnswer
                                )
                                restartNeeded = result.restartRequired
                                notice = "تمت الاستعادة، وحُفظت نسخة إنقاذ مشفرة داخل التطبيق"
                                preview = null
                                restoreAnswer = ""
                                pendingTemp?.delete()
                                pendingTemp = null
                            } finally { chars.fill('\u0000') }
                        }
                    }
                ) { Text("تأكيد الاستعادة") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { preview = null; restoreAnswer = "" }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
