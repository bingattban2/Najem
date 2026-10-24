package com.najimwifi.app.ui.home

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.data.local.entity.OptionalColumnCellEntity
import com.najimwifi.app.domain.model.ColumnValueType
import com.najimwifi.app.domain.model.RequiredColumnKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class EditingJournalCell(
    val row: PackageJournalRow,
    val column: ColumnDefinitionEntity
)

@Composable
fun PackageLedgerScreen(
    viewModel: HomeViewModel,
    customerId: String,
    packageId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ledgerFlow = remember(
        viewModel,
        customerId,
        packageId
    ) {
        viewModel.observePackageLedger(
            customerId,
            packageId
        )
    }

    val state by ledgerFlow.collectAsState(
        initial = PackageLedgerUiState()
    )

    val scope = rememberCoroutineScope()

    var editing by remember {
        mutableStateOf<EditingJournalCell?>(null)
    }

    var enteredValue by remember {
        mutableStateOf("")
    }

    var saving by remember {
        mutableStateOf(false)
    }

    var saveError by remember {
        mutableStateOf<String?>(null)
    }

    val formatter = remember {
        DateTimeFormatter.ofPattern(
            "EEEE yyyy/MM/dd HH:mm",
            Locale.forLanguageTag("ar")
        )
    }

    val labels = remember(state.columns) {
        state.columns.mapNotNull { column ->
            column.requiredKey?.let {
                it to column.title
            }
        }.toMap()
    }

    val optionalColumns = remember(state.columns) {
        state.columns.filter {
            it.requiredKey == null && it.isActive
        }
    }

    val cellValues = remember(state.cells) {
        state.cells.associateBy {
            Triple(it.rowKind, it.rowId, it.columnId)
        }
    }

    fun displayTitle(
        key: RequiredColumnKey,
        fallback: String
    ): String = labels[key] ?: fallback

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        com.najimwifi.app.ui.theme.StitchPageHeader(
            title = "سجل حركات الباقة", onBack = onBack
        )

        if (state.loading) {
            CircularProgressIndicator()
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!state.loading && state.error == null) {
            Text("العميل: ${state.customer?.name ?: "غير معروف"}",
                style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth()) {
                PackageBalanceCard(
                    packageName = state.pkg?.name ?: "غير معروفة",
                    displayValue = state.quantity.toString(),
                    colorArgb = state.pkg?.colorArgb ?: 0xFF6750A4.toInt(),
                    modifier = Modifier.fillMaxWidth(0.34f)
                )
            }

            HorizontalDivider()

            if (state.rows.isEmpty()) {
                Text("لا توجد عمليات مسجلة لهذه الباقة")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement =
                        Arrangement.spacedBy(5.dp)
                ) {
                    Row {
                        LedgerTableCell(
                            displayTitle(
                                RequiredColumnKey.DATE,
                                "التاريخ"
                            ),
                            175.dp,
                            true
                        )

                        LedgerTableCell(
                            displayTitle(
                                RequiredColumnKey.PACKAGE_NAME,
                                "الباقة"
                            ),
                            185.dp,
                            true
                        )

                        LedgerTableCell(
                            displayTitle(
                                RequiredColumnKey.WITH_CUSTOMER,
                                "معه"
                            ),
                            145.dp,
                            true
                        )

                        LedgerTableCell(
                            displayTitle(
                                RequiredColumnKey.REMAINING,
                                "المتبقي"
                            ),
                            115.dp,
                            true
                        )

                        optionalColumns.forEach { column ->
                            LedgerTableCell(
                                column.title,
                                160.dp,
                                true
                            )
                        }
                    }

                    HorizontalDivider()

                    state.rows.forEach { row ->
                        Card {
                            Row {
                                val date = Instant
                                    .ofEpochMilli(
                                        row.committedAtEpochMillis
                                    )
                                    .atZone(ZoneId.systemDefault())
                                    .format(formatter)

                                LedgerTableCell(
                                    date,
                                    175.dp
                                )

                                LedgerTableCell(
                                    "${row.packageName}\n" +
                                        "القيمة: ${row.nominalValueMinor}\n" +
                                        row.operationLabel,
                                    185.dp
                                )

                                LedgerTableCell(
                                    row.withCustomer,
                                    145.dp
                                )

                                LedgerTableCell(
                                    row.remaining.orEmpty(),
                                    115.dp
                                )

                                optionalColumns.forEach { column ->
                                    val cell = cellValues[
                                        Triple(
                                            row.rowKind,
                                            row.rowId,
                                            column.id
                                        )
                                    ]

                                    Column(
                                        modifier = Modifier
                                            .width(160.dp)
                                            .padding(7.dp)
                                    ) {
                                        Text(
                                            cell?.displayValue()
                                                ?: "—"
                                        )

                                        TextButton(
                                            onClick = {
                                                editing =
                                                    EditingJournalCell(
                                                        row,
                                                        column
                                                    )
                                                enteredValue =
                                                    cell?.displayValue()
                                                        .orEmpty()
                                                saveError = null
                                            }
                                        ) {
                                            Text("تحرير")
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            row.details,
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                "القيم التاريخية محفوظة كما سُجلت. " +
                    "تغيير أسماء الأعمدة لا يغيّر بيانات المبيعات المحفوظة.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    editing?.let { selected ->
        AlertDialog(
            onDismissRequest = {
                if (!saving) editing = null
            },
            title = {
                Text(selected.column.title)
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "قيمة اختيارية لهذا السجل فقط. " +
                            "ترك الحقل فارغًا يمسح القيمة الاختيارية."
                    )

                    OutlinedTextField(
                        value = enteredValue,
                        onValueChange = {
                            enteredValue = it
                        },
                        singleLine =
                            selected.column.valueType !=
                                ColumnValueType.TEXT,
                        keyboardOptions = KeyboardOptions(
                            keyboardType =
                                if (
                                    selected.column.valueType ==
                                    ColumnValueType.WHOLE_NUMBER
                                ) {
                                    KeyboardType.Number
                                } else {
                                    KeyboardType.Text
                                }
                        ),
                        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),

                        colors = softOutlinedTextFieldColors()
)

                    saveError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        if (!saving) {
                            saving = true
                            saveError = null

                            scope.launch {
                                try {
                                    viewModel.saveJournalCell(
                                        customerId,
                                        packageId,
                                        selected.row,
                                        selected.column.id,
                                        enteredValue
                                    )
                                    editing = null
                                } catch (
                                    cancelled: CancellationException
                                ) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    saveError = error.message
                                        ?: "تعذر حفظ القيمة"
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        editing = null
                    }
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun OptionalColumnCellEntity.displayValue(): String =
    when (valueType) {
        ColumnValueType.TEXT ->
            textValue.orEmpty()

        ColumnValueType.WHOLE_NUMBER,
        ColumnValueType.MONEY_MINOR ->
            numberValue?.toString().orEmpty()
    }

@Composable
private fun LedgerTableCell(
    value: String,
    width: Dp,
    header: Boolean = false
) {
    Text(
        text = value,
        modifier = Modifier
            .width(width)
            .padding(9.dp),
        fontWeight =
            if (header)
                FontWeight.SemiBold
            else
                FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium
    )
}