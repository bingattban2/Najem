package com.najimwifi.app.ui.archive

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.service.ArchiveEventRow
import com.najimwifi.app.domain.service.ArchiveEventType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val visibleKinds = setOf(ArchiveEventType.DELIVERY, ArchiveEventType.SETTLEMENT)

enum class ArchiveSort { NEWEST, OLDEST }

data class ArchiveFilterState(
    val customerQuery: String = "",
    val packageQuery: String = "",
    val fromDay: String = "",
    val toDay: String = "",
    val selectedTypes: Set<ArchiveEventType> = visibleKinds,
    val sort: ArchiveSort = ArchiveSort.NEWEST
) {
    private fun parsed(value: String): LocalDate? = if (value.isBlank()) null
        else runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    fun validationError(): String? {
        val first = parsed(fromDay)
        val last = parsed(toDay)
        return when {
            fromDay.isNotBlank() && first == null -> "تاريخ البداية غير صحيح (YYYY-MM-DD)"
            toDay.isNotBlank() && last == null -> "تاريخ النهاية غير صحيح (YYYY-MM-DD)"
            first != null && last != null && first.isAfter(last) -> "تاريخ البداية بعد النهاية"
            else -> null
        }
    }

    fun apply(rows: List<ArchiveEventRow>): List<ArchiveEventRow> {
        if (validationError() != null) return emptyList()
        val zone = ZoneId.systemDefault()
        val start = parsed(fromDay)?.atStartOfDay(zone)?.toInstant()
        val end = parsed(toDay)?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
        val query = customerQuery.trim()
        val packageSearch = packageQuery.trim()
        val filtered = rows.filter { row ->
            val at = Instant.ofEpochMilli(row.committedAtEpochMillis)
            row.eventType in visibleKinds && row.eventType in selectedTypes &&
                (query.isEmpty() || row.customerName.contains(query, ignoreCase = true)) &&
                (packageSearch.isEmpty() || row.packageName?.contains(packageSearch, true) == true) &&
                (start == null || !at.isBefore(start)) && (end == null || at.isBefore(end))
        }
        val ordering = compareBy<ArchiveEventRow> { it.committedAtEpochMillis }
            .thenBy { it.recordId }
        return filtered.sortedWith(if (sort == ArchiveSort.NEWEST) ordering.reversed() else ordering)
    }
}

internal fun archiveTypeLabel(type: ArchiveEventType) = when (type) {
    ArchiveEventType.DELIVERY -> "إضافة"
    ArchiveEventType.SETTLEMENT -> "محاسبة"
    ArchiveEventType.PAYMENT -> "تحصيل قديم"
    ArchiveEventType.RETURN -> "إرجاع قديم"
    ArchiveEventType.CORRECTION -> "تصحيح قديم"
}

@Composable
fun ArchiveFiltersPanel(filters: ArchiveFilterState, onChange: (ArchiveFilterState) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = filters.customerQuery,
            onValueChange = { onChange(filters.copy(customerQuery = it)) },
            placeholder = { Text("بحث باسم العميل", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.fillMaxWidth(0.86f).height(50.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp),
            colors = softOutlinedTextFieldColors()
)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(value = filters.fromDay,
                onValueChange = { onChange(filters.copy(fromDay = it)) },
                placeholder = { Text("من YYYY-MM-DD", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f).height(50.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                colors = softOutlinedTextFieldColors()
)
            OutlinedTextField(value = filters.toDay,
                onValueChange = { onChange(filters.copy(toDay = it)) },
                placeholder = { Text("إلى YYYY-MM-DD", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f).height(50.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                colors = softOutlinedTextFieldColors()
)
        }
        filters.validationError()?.let { Text(it) }
        Text("نوع العملية")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ArchiveEventType.DELIVERY, ArchiveEventType.SETTLEMENT).forEach { type ->
                Row {
                    Checkbox(checked = type in filters.selectedTypes,
                        onCheckedChange = { checked ->
                            onChange(filters.copy(selectedTypes =
                                if (checked) filters.selectedTypes + type else filters.selectedTypes - type))
                        })
                    Text(archiveTypeLabel(type))
                }
            }
        }
    }
}
