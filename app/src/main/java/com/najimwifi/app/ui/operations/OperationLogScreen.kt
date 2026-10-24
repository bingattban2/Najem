package com.najimwifi.app.ui.operations

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import com.najimwifi.app.ui.theme.StitchTag
import com.najimwifi.app.ui.theme.StitchPageHeader
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.dao.OperationLogEntry
import com.najimwifi.app.data.report.OperationLogPdfRow
import com.najimwifi.app.data.report.PdfShareManager
import com.najimwifi.app.data.report.OperationDescriptionArabic
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val arabicLocale = Locale.forLanguageTag("ar")
private val displayDate = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", arabicLocale)

private fun OperationLogEntry.actionLabel() = when (operationType) {
    "DELIVERY" -> "إضافة كروت"
    "SETTLEMENT" -> "محاسبة"
    "CUSTOMER_CHANGE" -> "إدارة العملاء"
    "PACKAGE_CHANGE" -> "إدارة الباقات"
    "PAYMENT" -> "تحصيل سابق"
    "RETURN" -> "إرجاع سابق"
    "CORRECTION" -> "تصحيح سابق"
    "ARCHIVE" -> "أرشفة سابقة"
    "BACKUP" -> "نسخة احتياطية"
    "RESTORE" -> "استعادة"
    "SECURITY_CHANGE" -> "إعدادات الحماية"
    "STOCK_MOVEMENT" -> "مخزون سابق"
    else -> "عملية"
}

private fun OperationLogEntry.details(): String = buildList {
    if (subjectName.isNotBlank()) add(subjectName)
    packageName?.takeIf { it.isNotBlank() }?.let { add(it) }
    cards?.let { add("$it كرت") }
    amountMinor?.let { add("المستحق: $it") }
    if (operationType in setOf("CUSTOMER_CHANGE", "PACKAGE_CHANGE")) add(OperationDescriptionArabic.display(description))
}.joinToString(" • ").ifEmpty { actionLabel() }

private fun OperationLogEntry.formattedDate(): String = displayDate.format(
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())
)

private data class OperationLogExportSnapshot(
    val rows: List<OperationLogPdfRow>,
    val query: String,
    val from: LocalDate?,
    val to: LocalDate?
)

private fun OperationLogEntry.forPdf() = OperationLogPdfRow(
    atMillis = atMillis,
    operationType = operationType,
    operationLabel = actionLabel(),
    customerName = subjectName,
    packageName = packageName,
    cards = cards,
    amountDueMinor = amountMinor,
    description = description
)

/** Live log: reads committed operations, never archive memberships. */
@Composable
fun OperationLogScreen(
    database: NajimDatabase,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val flow = remember(database) { database.auditDao().observeOperationLog() }
    val entries by flow.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var exportSnapshot by remember { mutableStateOf<OperationLogExportSnapshot?>(null) }

    val fromDate = remember(from) { OperationLogDateFilter.parse(from) }
    val toDate = remember(to) { OperationLogDateFilter.parse(to) }
    val valid = remember(from, to) { OperationLogDateFilter.validRange(from, to) }
    val shown = remember(entries, query, fromDate, toDate, valid) {
        if (!valid) emptyList() else entries.filter { entry ->
            val day = Instant.ofEpochMilli(entry.atMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            val term = query.trim()
            (term.isEmpty() || entry.subjectName.contains(term, ignoreCase = true) ||
                entry.actionLabel().contains(term, ignoreCase = true) ||
                entry.description.contains(term, ignoreCase = true) ||
                (entry.packageName?.contains(term, ignoreCase = true) == true)) &&
                (fromDate == null || !day.isBefore(fromDate)) &&
                (toDate == null || !day.isAfter(toDate))
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 16.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StitchPageHeader("سجل العمليات", onBack = onBack)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StitchTag("${shown.size} عملية")
                    StitchTag("سجل تلقائي", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        item("search") {
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        placeholder = { Text("ابحث باسم العميل أو العملية") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        colors = softOutlinedTextFieldColors()
)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = from, onValueChange = { from = it },
                            placeholder = { Text("من: سنة/شهر/يوم") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                            colors = softOutlinedTextFieldColors()
)
                        OutlinedTextField(value = to, onValueChange = { to = it },
                            placeholder = { Text("إلى: سنة/شهر/يوم") }, singleLine = true,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                            colors = softOutlinedTextFieldColors()
)
                    }
                }
            }
        }
        if (!valid) item("invalid") {
            Text("التاريخ غير صحيح", color = MaterialTheme.colorScheme.error)
        }
        item("export") {
            Button(onClick = {
                exportSnapshot = OperationLogExportSnapshot(
                    rows = shown.map { it.forPdf() }, query = query.trim(),
                    from = fromDate, to = toDate
                )
            }, enabled = valid && shown.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)) {
                Text("تصدير سجل العمليات PDF (${shown.size})")
            }
        }
        if (valid && shown.isEmpty()) item("empty") { Text("لا توجد عمليات مطابقة") }
        items(shown, key = { it.id }) { entry ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StitchTag(entry.actionLabel(), color = when (entry.operationType) {
                            "DELIVERY" -> MaterialTheme.colorScheme.tertiary
                            "SETTLEMENT" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        })
                        Text(entry.formattedDate(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(entry.details(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item("spacer") { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 6.dp)) }
    }

    exportSnapshot?.let { snapshot ->
        OperationLogPdfDialog(snapshot = snapshot, onClose = { exportSnapshot = null })
    }
}

/** Same export actions as the customer report: share, save or print one filtered PDF. */
@Composable
private fun OperationLogPdfDialog(
    snapshot: OperationLogExportSnapshot,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { PdfShareManager(context) }
    val scope = rememberCoroutineScope()
    var file by remember(snapshot) { mutableStateOf<File?>(null) }
    var rendering by remember(snapshot) { mutableStateOf(true) }
    var message by remember(snapshot) { mutableStateOf<String?>(null) }
    var error by remember(snapshot) { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val selectedFile = file
        if (uri != null && selectedFile != null) {
            scope.launch {
                try {
                    manager.saveToUri(selectedFile, uri)
                    message = "تم حفظ سجل العمليات"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "تعذر حفظ التقرير"
                }
            }
        }
    }

    LaunchedEffect(snapshot) {
        rendering = true
        try {
            file = withContext(Dispatchers.IO) {
                manager.createOperationLogReport(snapshot.rows, snapshot.query, snapshot.from, snapshot.to)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "تعذر إنشاء PDF"
        } finally {
            rendering = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!rendering) onClose() },
        title = { Text("تصدير سجل العمليات PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rendering) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
                val ready = file
                if (ready != null) {
                    OutlinedButton(onClick = {
                        try { manager.share(ready) }
                        catch (failure: Exception) {
                            error = failure.message ?: "تعذرت المشاركة"
                        }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text("مشاركة PDF")
                    }
                    Button(onClick = { saveLauncher.launch("najim_operations.pdf") },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text("حفظ PDF")
                    }
                    OutlinedButton(onClick = {
                        var current: Context = context
                        while (current is ContextWrapper && current !is Activity) {
                            val base = current.baseContext
                            if (base === current) break
                            current = base
                        }
                        val activity = current as? Activity
                        if (activity == null) {
                            error = "خدمة الطباعة غير متاحة"
                        } else {
                            try {
                                manager.print(activity, ready)
                            } catch (failure: Exception) {
                                error = failure.message ?: "تعذرت الطباعة"
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text("طباعة")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !rendering) { Text("إغلاق") } },
        shape = RoundedCornerShape(24.dp)
    )
}
