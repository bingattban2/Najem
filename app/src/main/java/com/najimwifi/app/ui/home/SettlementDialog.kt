package com.najimwifi.app.ui.home

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.PaymentMethod
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.rules.CalculatedSettlement
import java.util.UUID

@Composable
fun SettlementDialog(
    customerId: String,
    customerName: String,
    customerLocation: String,
    packages: List<HomePackageBalance>,
    previousDebtMinor: Long,
    busy: Boolean,
    error: String?,
    previewSettlement: (SettlementRequest) -> CalculatedSettlement,
    onDismiss: () -> Unit,
    onConfirm: (SettlementRequest) -> Unit
) {
    val captured = remember(customerId) {
        packages.filter { it.quantity > 0L }
    }

    var mode by rememberSaveable(customerId) {
        mutableStateOf(SettlementMode.ONE)
    }

    var selectedId by rememberSaveable(customerId) {
        mutableStateOf(captured.firstOrNull()?.pkg?.id.orEmpty())
    }

    var remainingInputs by remember(customerId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    var operationId by rememberSaveable(customerId) {
        mutableStateOf(UUID.randomUUID().toString())
    }

    var expanded by remember {
        mutableStateOf(false)
    }

    // A single submit button; one success confirmation is shown by the parent.
    var submitted by remember(customerId) { mutableStateOf(false) }
    LaunchedEffect(error, busy) {
        if (!busy && error != null) submitted = false
    }

    val selectedPackages = if (mode == SettlementMode.ALL) {
        captured
    } else {
        captured.filter { it.pkg.id == selectedId }
    }

    fun changed() {
        operationId = UUID.randomUUID().toString()
    }

    val payment = 0L // New simplified settlements do not collect payments.

    val completedCounts = selectedPackages.isNotEmpty() &&
        selectedPackages.all { row ->
            val remaining =
                remainingInputs[row.pkg.id]?.toLongOrNull()

            remaining != null &&
                remaining >= 0L &&
                remaining <= row.quantity
        }

    val draft = if (completedCounts) {
        SettlementRequest(
            operationId = operationId,
            customerId = customerId,
            mode = mode,
            selectedPackageId =
                if (mode == SettlementMode.ONE)
                    selectedId
                else
                    null,
            counts = selectedPackages.map { row ->
                SettlementCountInput(
                    packageId = row.pkg.id,
                    expectedShopBalance = row.quantity,
                    remainingCount =
                        remainingInputs[row.pkg.id]?.toLongOrNull(),
                    expectedPackage = row.toSnapshot()
                )
            },
            expectedPreviousDebtMinor = previousDebtMinor,
            singlePaymentMinor = payment,
            paymentMethod = PaymentMethod.UNSPECIFIED,
            paymentNote = null,
            confirmed = true
        )
    } else {
        null
    }

    // Preview only when the entered counts change, not on every Compose recomposition.
    val previewResult = remember(draft) {
        draft?.let { runCatching { previewSettlement(it) } }
    }

    val preview = previewResult?.getOrNull()
    val previewError = previewResult
        ?.exceptionOrNull()
        ?.message

    AlertDialog(
        onDismissRequest = {
            if (!busy && !submitted) onDismiss()
        },
        title = { Text("محاسبة العميل") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Text("العميل: $customerName")
                Text("الموقع: $customerLocation")


                if (captured.isEmpty()) {
                    Text(
                        "لا توجد كروت لدى العميل للمحاسبة."
                    )
                } else {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !busy && !submitted,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (mode == SettlementMode.ALL) "الفئة: جميع الفئات"
                            else "الفئة: " +
                                (selectedPackages.firstOrNull()?.pkg?.name ?: "اختر باقة"))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        captured.forEach { row ->
                            DropdownMenuItem(
                                text = { Text(row.pkg.name) },
                                onClick = {
                                    mode = SettlementMode.ONE
                                    selectedId = row.pkg.id
                                    expanded = false
                                    changed()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("جميع الفئات") },
                            onClick = {
                                mode = SettlementMode.ALL
                                selectedId = ""
                                expanded = false
                                changed()
                            }
                        )
                    }

                    selectedPackages.forEach { row ->
                        val calculated = preview?.lines?.firstOrNull {
                            it.packageId == row.pkg.id
                        }
                        SettlementPackageCountRow(
                            row = row,
                            remainingValue = remainingInputs[row.pkg.id].orEmpty(),
                            enabled = !busy && !submitted,
                            sold = calculated?.sold,
                            amountDueMinor = calculated?.amountDue?.minorUnits,
                            onRemainingChanged = { value ->
                                remainingInputs = remainingInputs + (row.pkg.id to asciiNumberInput(value))
                                changed()
                            }
                        )
                    }

                    HorizontalDivider()

                    preview?.let { total ->
                        HorizontalDivider()

                        Text("المبلغ المستحق: ${total.newAmountDue.minorUnits}")
                    }

                    previewError?.let {
                        Text(it)
                    }

                    error?.let {
                        Text(it)
                    }

                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview != null && !busy && !submitted,
                onClick = {
                    val request = draft ?: return@Button
                    if (submitted) return@Button
                    submitted = true
                    onConfirm(request)
                }
            ) { Text("تأكيد المحاسبة") }
        },
        dismissButton = {
            TextButton(enabled = !busy && !submitted, onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

/** Reused for one-package and all-package accounting; count is always explicit. */
@Composable
private fun SettlementPackageCountRow(
    row: HomePackageBalance,
    remainingValue: String,
    enabled: Boolean,
    sold: Long?,
    amountDueMinor: Long?,
    onRemainingChanged: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PackageBalanceCard(
                packageName = row.pkg.name,
                displayValue = row.quantity.toString(),
                colorArgb = row.pkg.colorArgb,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = remainingValue,
                onValueChange = onRemainingChanged,
                enabled = enabled,
                label = { Text("المتبقي") },
                singleLine = true,
                modifier = Modifier.weight(1.5f),
                shape = RoundedCornerShape(12.dp),
                colors = softOutlinedTextFieldColors()
)
        }
        Text("قيمة الفئة: ${row.pkg.generalSettlementPriceMinor}")
        sold?.let { Text("عدد الكروت المباعة: $it") }
        amountDueMinor?.let { Text("المبلغ المستحق: $it") }
    }
}
