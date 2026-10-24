package com.najimwifi.app.ui.management

import com.najimwifi.app.ui.theme.softOutlinedTextFieldColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.ui.theme.StitchPageHeader
import com.najimwifi.app.ui.theme.StitchTag
import com.najimwifi.app.ui.theme.CustomerAvatar
import androidx.compose.ui.Alignment
import java.util.Locale

@Composable
fun CustomerListScreen(
    customers: List<Customer>, busy: Boolean, onBack: () -> Unit, onAdd: () -> Unit,
    onEdit: (Customer) -> Unit, onToggle: (Customer) -> Unit, onRemove: (Customer) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase(Locale.ROOT)
    val visible = remember(customers, query) {
        customers.filter { it.name.lowercase(Locale.ROOT).contains(query) ||
            it.location.lowercase(Locale.ROOT).contains(query) }
            .sortedWith(compareBy<Customer> { !it.isActive }.thenBy { it.name })
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 16.dp)) {
        item("header") { StitchPageHeader("العملاء", onBack = onBack) }
        item("controls") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StitchTag("${visible.size} عميل")
                Button(onClick = onAdd, enabled = !busy,
                    shape = RoundedCornerShape(16.dp)) { Text("إضافة عميل") }
            }
        }
        item("search") {
            OutlinedTextField(search, onValueChange = { search = it },
                placeholder = { Text("ابحث بالاسم أو الموقع") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = softOutlinedTextFieldColors()
)
        }
        if (visible.isEmpty()) item("empty") {
            Text(if (query.isEmpty()) "لا يوجد عملاء" else "لا توجد نتائج مطابقة")
        }
        items(visible, key = { it.id }) { customer ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CustomerAvatar(customer.name)
                        Text(customer.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        if (!customer.isActive) StitchTag("موقوف", color = MaterialTheme.colorScheme.error)
                    }
                    Text("الموقع: ${customer.location}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(customer) }, enabled = !busy) { Text("تعديل") }
                        TextButton(onClick = { onToggle(customer) }, enabled = !busy) {
                            Text(if (customer.isActive) "إيقاف" else "تفعيل")
                        }
                        TextButton(onClick = { onRemove(customer) }, enabled = !busy) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
