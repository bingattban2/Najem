package com.najimwifi.app.ui.home

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.model.PackageSnapshot
import java.util.UUID

internal fun asciiNumberInput(value: String): String = buildString {
    value.forEach { character ->
        val digit = Character.digit(character, 10)
        if (digit >= 0) append(digit)
    }
}

internal fun HomePackageBalance.toSnapshot() = PackageSnapshot(
    name = pkg.name,
    nominalValueMinor = pkg.nominalValueMinor,
    generalSettlementPriceMinor =
        pkg.generalSettlementPriceMinor,
    colorArgb = pkg.colorArgb
)

@Composable
fun DeliveryDialog(
    customerId: String,
    customerName: String,
    customerLocation: String,
    packages: List<HomePackageBalance>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (DeliveryRequest) -> Unit
) {
    val capturedPackages = remember(customerId) {
        packages.filter { it.pkg.isActive }
    }

    var selectedId by rememberSaveable(customerId) {
        mutableStateOf(
            capturedPackages.firstOrNull()?.pkg?.id
                .orEmpty()
        )
    }

    var quantityText by rememberSaveable(customerId) {
        mutableStateOf("")
    }

    var operationId by rememberSaveable(customerId) {
        mutableStateOf(UUID.randomUUID().toString())
    }

    // Suppress a second tap while the same operation is being committed.
    var submitted by remember(customerId) { mutableStateOf(false) }
    LaunchedEffect(error, busy) {
        // A failed request may be retried with the same idempotency key.
        if (!busy && error != null) submitted = false
    }

    val selected = capturedPackages.firstOrNull {
        it.pkg.id == selectedId
    }

    val quantity = quantityText.toLongOrNull()
    val valid = selected != null &&
        quantity != null &&
        quantity > 0L

    fun changed() {
        operationId = UUID.randomUUID().toString()
    }

    AlertDialog(
        onDismissRequest = {
            if (!busy && !submitted) onDismiss()
        },
        title = { Text("إضافة كروت للعميل") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Text("العميل: $customerName")
                Text("الموقع: $customerLocation")

                if (selected == null) {
                    Text("لا توجد باقة نشطة متاحة للتسليم")
                } else {
                    Text("اختر فئة الكروت", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth < 300.dp) 1 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            capturedPackages.chunked(columns).forEach { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    group.forEach { row ->
                                        PackageBalanceCard(
                                            packageName = row.pkg.name,
                                            displayValue = row.pkg.nominalValueMinor.toString(),
                                            colorArgb = row.pkg.colorArgb,
                                            modifier = Modifier.weight(1f),
                                            caption = "قيمة الباقة",
                                            selected = row.pkg.id == selectedId,
                                            onClick = {
                                                if (!busy && !submitted && selectedId != row.pkg.id) {
                                                    selectedId = row.pkg.id
                                                    quantityText = ""
                                                    changed()
                                                }
                                            }
                                        )
                                    }
                                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Text("الكروت لدى العميل: ${selected.quantity}")

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = {
                            quantityText = asciiNumberInput(it)
                            changed()
                        },
                        enabled = !busy && !submitted,
                        label = { Text("عدد الكروت المراد تسليمها") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),

                        colors = softOutlinedTextFieldColors()
)

                }

                error?.let {
                    Text(it)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !busy && !submitted,
                onClick = {
                    val pkg = selected ?: return@Button
                    val count = quantity ?: return@Button
                    if (submitted) return@Button
                    submitted = true
                    onConfirm(
                        DeliveryRequest(
                            operationId = operationId,
                            customerId = customerId,
                            packageId = pkg.pkg.id,
                            quantity = count,
                            expectedShopBalance = pkg.quantity,
                            expectedPackage = pkg.toSnapshot(),
                            confirmed = true
                        )
                    )
                }
            ) { Text("تأكيد الإضافة") }
        },
        dismissButton = {
            TextButton(enabled = !busy && !submitted, onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
