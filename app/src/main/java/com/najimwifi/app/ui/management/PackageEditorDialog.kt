package com.najimwifi.app.ui.management

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.contract.PackageDraft
import com.najimwifi.app.domain.model.Package

private val colors = listOf(
    0xFFEF5350, 0xFFEC407A, 0xFFAB47BC, 0xFF5C6BC0,
    0xFF29B6F6, 0xFF26A69A, 0xFF66BB6A, 0xFFD4E157,
    0xFFFFCA28, 0xFFFF7043, 0xFF8D6E63, 0xFF78909C
).map(Long::toInt)

@Composable
fun PackageEditorDialog(
    original: Package?, existingPackages: List<Package>, busy: Boolean,
    onDismiss: () -> Unit, onSave: (PackageDraft) -> Unit
) {
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var price by remember(original?.id) { mutableStateOf(original?.nominalValueMinor?.toString().orEmpty()) }
    var color by remember(original?.id) { mutableStateOf(original?.colorArgb ?: colors.first()) }
    val amount = price.toLongOrNull()
    val duplicate = existingPackages.any { pkg ->
        pkg.id != original?.id && (
            pkg.name.trim().equals(name.trim(), ignoreCase = true) ||
                (amount != null && pkg.nominalValueMinor == amount) || pkg.colorArgb == color
        )
    }
    val valid = name.isNotBlank() && amount != null && amount > 0 && !duplicate
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (original == null) "إضافة فئة" else "تعديل الفئة") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("اسم الفئة") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = softOutlinedTextFieldColors(), shape = RoundedCornerShape(16.dp)
)
                OutlinedTextField(value = price,
                    onValueChange = { raw -> price = raw.mapNotNull { Character.digit(it, 10).takeIf { d -> d >= 0 }?.toString() }.joinToString("") },
                    label = { Text("قيمة الفئة") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = softOutlinedTextFieldColors(), shape = RoundedCornerShape(16.dp)
)
                Text("لون الفئة", style = MaterialTheme.typography.titleMedium)
                colors.chunked(4).forEach { set ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        set.forEach { candidate ->
                            val unavailable = existingPackages.any { it.id != original?.id && it.colorArgb == candidate }
                            Box(modifier = Modifier.size(46.dp)
                                .border(if (candidate == color) 3.dp else 1.dp,
                                    if (candidate == color) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(4.dp).background(Color(candidate), RoundedCornerShape(7.dp))
                                .clickable(enabled = !unavailable) { color = candidate })
                        }
                    }
                }
                if (duplicate) Text("لا يسمح بتكرار الاسم أو القيمة أو اللون",
                    color = MaterialTheme.colorScheme.error)
                if (original != null && original.generalSettlementPriceMinor != original.nominalValueMinor) {
                    Text("سيُوحّد السعر المستقبلي لهذه الفئة مع قيمتها الجديدة؛ " +
                        "أما المحاسبات القديمة فتبقى بقيمتها التاريخية.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy, onClick = {
            // Simplified catalog: one value controls both the visible value and sale accounting.
            val safeAmount = requireNotNull(amount)
            onSave(PackageDraft(
                name = name.trim(), nominalValueMinor = safeAmount,
                generalSettlementPriceMinor = safeAmount, colorArgb = color
            ))
        }) { Text("تأكيد") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") } }
    )
}
