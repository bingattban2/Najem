package com.najimwifi.app.ui.archive

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.service.ArchiveEventRow
import com.najimwifi.app.domain.service.ArchiveEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    managementMode: Boolean,
    onBack: () -> Unit,
    onExportReport: (ArchiveFilterState) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var filters by remember { mutableStateOf(ArchiveFilterState()) }
    var confirming by remember { mutableStateOf(false) }
    var confirmedBy by rememberSaveable { mutableStateOf("") }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val filteredRows = remember(filters, state.archived) { filters.apply(state.archived) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE yyyy/MM/dd", Locale.forLanguageTag("ar")) }
    val scroll = rememberScrollState()
    val archivable = state.eligible.map { it.groupId }.toSet()

    LazyColumn(modifier = modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("title") {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                if (managementMode) TextButton(onClick = onBack) { Text("رجوع") }
                Text(if (managementMode) "أرشفة الجداول المحاسبة" else "الأرشيف",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }
        }
        if (state.loading || state.busy) item("loading") { CircularProgressIndicator() }
        state.error?.let { item("error") {
            Text(it, color = MaterialTheme.colorScheme.error)
        } }
        state.message?.let { item("message") {
            Text(it, color = MaterialTheme.colorScheme.primary)
        } }
        if (managementMode) {
            item("manage") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("المحاسبات الجديدة الجاهزة للأرشفة: ${archivable.size}")
                    OutlinedButton(onClick = viewModel::refreshCandidates, enabled = !state.busy) {
                        Text("تحديث")
                    }
                    Button(onClick = {
                        operationId = UUID.randomUUID().toString()
                        confirming = true
                    }, enabled = !state.busy && archivable.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("أرشفة بالكامل")
                    }
                }
            }
        } else {
            item("filters") {
                ArchiveFiltersPanel(filters, onChange = { filters = it })
            }
            item("export") {
                OutlinedButton(onClick = { onExportReport(filters) },
                    modifier = Modifier.fillMaxWidth()) { Text("تصدير الأرشيف PDF") }
            }
        }
        item("table-title") {
            Text("العمليات المؤرشفة (${filteredRows.size})", style = MaterialTheme.typography.titleLarge)
            if (filteredRows.isNotEmpty()) ArchiveTableHeader(scroll)
        }
        if (!state.loading && filteredRows.isEmpty()) item("empty") {
            Text(if (filters.validationError() != null) "صحح التاريخ أولًا" else "لا توجد عمليات مؤرشفة مطابقة")
        }
        items(filteredRows, key = { "${it.batchId}:${it.recordId}" }) { row ->
            ArchiveTableRow(row, scroll, dateFormat.format(
                Instant.ofEpochMilli(row.committedAtEpochMillis).atZone(ZoneId.systemDefault())))
            if (row.groupId != null) {
                TextButton(onClick = { viewModel.openDetails(requireNotNull(row.groupId)) }) {
                    Text("تفاصيل المحاسبة")
                }
            }
        }
    }
    state.details?.let { details -> ArchiveDetailsDialog(details, onDismiss = viewModel::closeDetails) }
    if (state.detailsLoading) AlertDialog(onDismissRequest = {},
        title = { Text("تحميل تفاصيل المحاسبة") }, text = { CircularProgressIndicator() },
        confirmButton = {})
    if (confirming && managementMode) {
        // Freeze the exact candidate IDs while the confirmation dialog is open.
        val selected = remember(confirming) { archivable }
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirming = false },
            title = { Text("تأكيد الأرشفة بالكامل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("سيتم أرشفة ${selected.size} محاسبة مكتملة. لن تُحذف عمليات، " +
                        "ولن يتغير عدد الكروت المتبقية.")
                    OutlinedTextField(value = confirmedBy, onValueChange = { confirmedBy = it },
                        label = { Text("اسم منفذ الأرشفة") }, singleLine = true,
                        colors = softOutlinedTextFieldColors(), shape = RoundedCornerShape(16.dp)
)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.busy && selected.isNotEmpty() && confirmedBy.isNotBlank(),
                    onClick = {
                        viewModel.archiveSelected(operationId, confirmedBy.trim(),
                            selected, emptySet()) { confirming = false }
                    }) { Text("تأكيد") }
            },
            dismissButton = { TextButton(enabled = !state.busy,
                onClick = { confirming = false }) { Text("إلغاء") } }
        )
    }
}

private val widths = listOf(148.dp, 86.dp, 135.dp, 130.dp, 90.dp, 100.dp, 94.dp, 108.dp)
private val titles = listOf("اليوم والتاريخ", "العملية", "العميل", "الفئة", "عدد الكروت",
    "المتبقي", "المباع", "المستحق")

@Composable
private fun ArchiveTableHeader(scroll: androidx.compose.foundation.ScrollState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        titles.forEachIndexed { index, title ->
            Text(title, modifier = Modifier.width(widths[index]).padding(6.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ArchiveTableRow(row: ArchiveEventRow,
    scroll: androidx.compose.foundation.ScrollState, date: String) {
    val addition = row.eventType == ArchiveEventType.DELIVERY
    val values = listOf(
        date, archiveTypeLabel(row.eventType), row.customerName,
        row.packageName.orEmpty(), row.eventQuantity?.toString().orEmpty(),
        if (addition) "" else row.remainingQuantity?.toString().orEmpty(),
        if (addition) "" else row.soldQuantity?.toString().orEmpty(),
        if (addition) "" else row.amountDueMinor?.toString().orEmpty()
    )
    Card(modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            values.forEachIndexed { index, value ->
                Text(value, modifier = Modifier.width(widths[index]).padding(6.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
