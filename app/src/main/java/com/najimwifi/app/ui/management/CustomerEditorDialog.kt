package com.najimwifi.app.ui.management

import androidx.compose.foundation.shape.RoundedCornerShape

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.najimwifi.app.domain.contract.CustomerDraft
import com.najimwifi.app.domain.model.Customer

@Composable
fun CustomerEditorDialog(
    original: Customer?, busy: Boolean, onDismiss: () -> Unit,
    onSave: (CustomerDraft) -> Unit
) {
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var location by remember(original?.id) { mutableStateOf(original?.location.orEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (original == null) "إضافة عميل" else "تعديل العميل") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("اسم العميل") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = softOutlinedTextFieldColors(), shape = RoundedCornerShape(16.dp)
)
                OutlinedTextField(value = location, onValueChange = { location = it },
                    label = { Text("الموقع") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = softOutlinedTextFieldColors(), shape = RoundedCornerShape(16.dp)
)
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && location.isNotBlank(),
            onClick = { onSave(CustomerDraft(name.trim(), location.trim(), original?.phone)) }
        ) { Text("تأكيد") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") } }
    )
}
