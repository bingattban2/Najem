package com.najimwifi.app.ui.management

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.domain.model.ColumnTarget
import com.najimwifi.app.domain.model.ColumnValueType

@Composable
fun ColumnEditorScreen(
    viewModel: ColumnEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    var editing by remember {
        mutableStateOf<ColumnDefinitionEntity?>(null)
    }

    var showEditor by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        com.najimwifi.app.ui.theme.StitchPageHeader(
            title = "أعمدة الجداول", onBack = onBack
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.select(ColumnTarget.PACKAGE_JOURNAL)
                },
                enabled = !state.busy,
        shape = RoundedCornerShape(16.dp)) {
                Text(
                    if (state.target == ColumnTarget.PACKAGE_JOURNAL)
                        "✓ سجل الباقات"
                    else
                        "سجل الباقات"
                )
            }

            OutlinedButton(
                onClick = {
                    viewModel.select(ColumnTarget.ARCHIVE)
                },
                enabled = !state.busy,
        shape = RoundedCornerShape(16.dp)) {
                Text(
                    if (state.target == ColumnTarget.ARCHIVE)
                        "✓ الأرشيف"
                    else
                        "الأرشيف"
                )
            }
        }


        state.message?.let { message ->
            Card {
                Row(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = viewModel::dismissMessage
                    ) {
                        Text("إغلاق")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                editing = null
                showEditor = true
            },
            enabled = !state.busy,
        shape = RoundedCornerShape(16.dp)) {
            Text("إضافة عمود اختياري")
        }

        state.columns.forEach { column ->
            Card(
                modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        column.title,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        (if (column.requiredKey != null)
                            "إلزامي"
                        else
                            "اختياري") +
                            " • " + column.valueType.name +
                            (if (column.isActive)
                                ""
                            else
                                " • مخفي"),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick = {
                                editing = column
                                showEditor = true
                            },
                            enabled = !state.busy
                        ) {
                            Text(
                                if (column.requiredKey != null)
                                    "تغيير العنوان"
                                else
                                    "تعديل"
                            )
                        }

                        if (column.requiredKey == null) {
                            TextButton(
                                onClick = {
                                    viewModel.setOptionalVisible(
                                        column,
                                        !column.isActive
                                    )
                                },
                                enabled = !state.busy
                            ) {
                                Text(
                                    if (column.isActive)
                                        "إزالة من العرض"
                                    else
                                        "إظهار"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        ColumnEditorDialog(
            original = editing,
            busy = state.busy,
            onDismiss = {
                showEditor = false
            },
            onSave = { title, order, kind ->
                val old = editing

                when {
                    old == null ->
                        viewModel.addOptional(
                            title,
                            kind,
                            order
                        ) {
                            showEditor = false
                        }

                    old.requiredKey != null ->
                        viewModel.renameLabel(
                            old,
                            title
                        ) {
                            showEditor = false
                        }

                    else ->
                        viewModel.editOptional(
                            old,
                            title,
                            order
                        ) {
                            showEditor = false
                        }
                }
            }
        )
    }
}

@Composable
private fun ColumnEditorDialog(
    original: ColumnDefinitionEntity?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Int, ColumnValueType) -> Unit
) {
    var title by remember(original?.id) {
        mutableStateOf(original?.title.orEmpty())
    }

    var order by remember(original?.id) {
        mutableStateOf(
            original?.displayOrder?.toString() ?: "100"
        )
    }

    var kind by remember(original?.id) {
        mutableStateOf(
            original?.valueType ?: ColumnValueType.TEXT
        )
    }

    val required = original?.requiredKey != null
    val orderValue = order.toIntOrNull()

    val valid =
        title.isNotBlank() &&
            title.trim().length <= 64 &&
            (required || (
                orderValue != null &&
                    orderValue >= 0
            ))

    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        title = {
            Text(
                if (original == null)
                    "إضافة عمود اختياري"
                else
                    "تعديل عنوان العمود"
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                    },
                    label = {
                        Text("العنوان الظاهر")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                    colors = softOutlinedTextFieldColors()
)

                if (!required) {
                    OutlinedTextField(
                        value = order,
                        onValueChange = { input ->
                            order = buildString {
                                input.forEach { char ->
                                    Character.digit(
                                        char,
                                        10
                                    ).takeIf {
                                        it >= 0
                                    }?.let {
                                        append(it)
                                    }
                                }
                            }
                        },
                        label = {
                            Text("ترتيب العرض")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
                        colors = softOutlinedTextFieldColors()
)
                }

                if (original == null) {
                    Text("نوع القيمة الاختيارية")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                kind = ColumnValueType.TEXT
                            },
        shape = RoundedCornerShape(16.dp)) {
                            Text(
                                if (kind == ColumnValueType.TEXT)
                                    "✓ نص"
                                else
                                    "نص"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                kind = ColumnValueType.WHOLE_NUMBER
                            },
        shape = RoundedCornerShape(16.dp)) {
                            Text(
                                if (kind == ColumnValueType.WHOLE_NUMBER)
                                    "✓ عدد"
                                else
                                    "عدد"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        orderValue ?: 0,
                        kind
                    )
                },
                enabled = valid && !busy
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy
            ) {
                Text("إلغاء")
            }
        }
    )
}