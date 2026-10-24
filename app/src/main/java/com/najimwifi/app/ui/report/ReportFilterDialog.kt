package com.najimwifi.app.ui.report

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package
import com.najimwifi.app.domain.report.ReportEventType
import com.najimwifi.app.domain.report.ReportRequest
import com.najimwifi.app.domain.report.ReportScope
import com.najimwifi.app.ui.archive.ArchiveFilterState
import com.najimwifi.app.ui.archive.ArchiveSort
import java.time.LocalDate

/** Prepares a report request; PDF rendering/sharing belongs to STEP 08.02. */
@Composable
fun ReportFilterDialog(
    scope: ReportScope,
    customers: List<Customer>,
    packages: List<Package>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (ReportRequest) -> Unit,
    initialCustomerId: String? = null,
    initialPackageId: String? = null,
    archiveFilters: ArchiveFilterState? = null
) {
    var customerId by remember(scope, initialCustomerId) {
        mutableStateOf(initialCustomerId)
    }
    var packageId by remember(scope, initialPackageId) {
        mutableStateOf(initialPackageId)
    }
    var fromText by remember(scope, archiveFilters) {
        mutableStateOf(archiveFilters?.fromDay.orEmpty())
    }
    var toText by remember(scope, archiveFilters) {
        mutableStateOf(archiveFilters?.toDay.orEmpty())
    }
    var customerQuery by remember(scope, archiveFilters) {
        mutableStateOf(archiveFilters?.customerQuery.orEmpty())
    }
    var packageQuery by remember(scope, archiveFilters) {
        mutableStateOf(archiveFilters?.packageQuery.orEmpty())
    }
    var types by remember(scope, archiveFilters) {
        mutableStateOf(
            archiveFilters?.selectedTypes?.mapNotNull {
                runCatching { ReportEventType.valueOf(it.name) }.getOrNull()
            }?.toSet() ?: setOf(ReportEventType.DELIVERY, ReportEventType.SETTLEMENT)
        )
    }
    var selectCustomer by remember { mutableStateOf(false) }
    var selectPackage by remember { mutableStateOf(false) }

    fun parseDay(text: String): LocalDate? =
        if (text.isBlank()) null else runCatching {
            LocalDate.parse(text.trim())
        }.getOrNull()

    val from = parseDay(fromText)
    val to = parseDay(toText)
    val datesValid =
        (fromText.isBlank() || from != null) &&
            (toText.isBlank() || to != null) &&
            (from == null || to == null || !from.isAfter(to))
    val canGenerate = datesValid && !busy &&
        (scope != ReportScope.CUSTOMER || customerId != null)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("إعداد التقرير") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (scope != ReportScope.ARCHIVE) {
                    OutlinedButton(onClick = { selectCustomer = true },
        shape = RoundedCornerShape(16.dp)) {
                        Text(customers.firstOrNull { it.id == customerId }?.name
                            ?: "جميع العملاء")
                    }
                    DropdownMenu(
                        expanded = selectCustomer,
                        onDismissRequest = { selectCustomer = false }
                    ) {
                        if (scope != ReportScope.CUSTOMER) {
                            DropdownMenuItem(
                                text = { Text("جميع العملاء") },
                                onClick = { customerId = null; selectCustomer = false }
                            )
                        }
                        customers.forEach { customer ->
                            DropdownMenuItem(
                                text = { Text(customer.name) },
                                onClick = { customerId = customer.id; selectCustomer = false }
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { selectPackage = true },
        shape = RoundedCornerShape(16.dp)) {
                    Text(packages.firstOrNull { it.id == packageId }?.name
                        ?: "جميع الباقات")
                }
                DropdownMenu(
                    expanded = selectPackage,
                    onDismissRequest = { selectPackage = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("جميع الباقات") },
                        onClick = { packageId = null; selectPackage = false }
                    )
                    packages.forEach { pkg ->
                        DropdownMenuItem(
                            text = { Text(pkg.name) },
                            onClick = { packageId = pkg.id; selectPackage = false }
                        )
                    }
                }
                OutlinedTextField(
                    value = fromText, onValueChange = { fromText = it },
                    label = { Text("من YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                    colors = softOutlinedTextFieldColors()
)
                OutlinedTextField(
                    value = toText, onValueChange = { toText = it },
                    label = { Text("إلى YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                    colors = softOutlinedTextFieldColors()
)
                if (!datesValid) Text("نطاق التاريخ غير صحيح")
                if (scope == ReportScope.ARCHIVE) {
                    OutlinedTextField(
                        value = customerQuery, onValueChange = { customerQuery = it },
                        label = { Text("بحث العميل") }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                        colors = softOutlinedTextFieldColors()
)
                    OutlinedTextField(
                        value = packageQuery, onValueChange = { packageQuery = it },
                        label = { Text("بحث الباقة") }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                        colors = softOutlinedTextFieldColors()
)
                    listOf(ReportEventType.DELIVERY, ReportEventType.SETTLEMENT).forEach { type ->
                        androidx.compose.foundation.layout.Row {
                            Checkbox(
                                checked = type in types,
                                onCheckedChange = {
                                    types = if (it) types + type else types - type
                                }
                            )
                            Text(if (type == ReportEventType.DELIVERY) "إضافة" else "محاسبة")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canGenerate,
                onClick = {
                    onGenerate(
                        ReportRequest(
                            scope = scope,
                            customerId = customerId,
                            packageId = packageId,
                            fromDate = from,
                            toDate = to,
                            customerSearch = customerQuery,
                            packageSearch = packageQuery,
                            eventTypes = types,
                            newestFirst = archiveFilters?.sort != ArchiveSort.OLDEST &&
                                scope == ReportScope.ARCHIVE
                        )
                    )
                }
            ) { Text("تجهيز بيانات التقرير") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("إلغاء")
            }
        }
    )
}