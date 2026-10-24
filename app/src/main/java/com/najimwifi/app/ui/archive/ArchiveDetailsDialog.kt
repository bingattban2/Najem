package com.najimwifi.app.ui.archive

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.service.ArchiveGroupDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ArchiveDetailsDialog(
    details: ArchiveGroupDetails,
    onDismiss: () -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern(
        "EEEE yyyy/MM/dd HH:mm",
        Locale.forLanguageTag("ar")
    )

    val date = Instant.ofEpochMilli(
        details.committedAtEpochMillis
    ).atZone(
        ZoneId.systemDefault()
    ).format(formatter)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("تفاصيل المحاسبة المؤرشفة")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("العميل: ${details.customerName}")
                Text("التاريخ: $date")

                Text(
                    "عدد باقات المحاسبة: ${details.lines.size}"
                )

                HorizontalDivider()

                details.lines.forEach { line ->
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            line.packageName,
                            style =
                                MaterialTheme.typography.titleMedium
                        )

                        Text(
                            "القيمة الاسمية وقت العملية: " +
                                line.nominalValueMinor
                        )

                        Text(
                            "سعر المحاسبة وقت العملية: " +
                                line.settlementPriceMinor
                        )

                        Text(
                            "العهدة قبل المحاسبة: " +
                                line.stockBefore
                        )

                        Text(
                            "المتبقي وقت المحاسبة: " +
                                line.stockRemaining
                        )

                        Text(
                            "المباع: ${line.soldQuantity}"
                        )

                        Text(
                            "المستحق: ${line.amountDueMinor}"
                        )

                        HorizontalDivider()
                    }
                }

                Text(
                    "إجمالي المبلغ المستحق: ${details.totalAmountDueMinor}",
                    style = MaterialTheme.typography.titleMedium
                )

            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        },
        shape = RoundedCornerShape(24.dp))
}