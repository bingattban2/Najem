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
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.model.Package
import com.najimwifi.app.ui.home.PackageBalanceCard
import com.najimwifi.app.ui.theme.StitchPageHeader
import com.najimwifi.app.ui.theme.StitchTag
import java.util.Locale

@Composable
fun PackageListScreen(
    packages: List<Package>, busy: Boolean, onBack: () -> Unit, onAdd: () -> Unit,
    onEdit: (Package) -> Unit, onToggle: (Package) -> Unit, onRemove: (Package) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase(Locale.ROOT)
    val visible = remember(packages, query) {
        packages.filter { it.name.lowercase(Locale.ROOT).contains(query) ||
            it.nominalValueMinor.toString().contains(query) }
            .sortedWith(compareBy<Package> { !it.isActive }.thenBy { it.nominalValueMinor })
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 16.dp)) {
        item("header") { StitchPageHeader("الباقات", onBack = onBack) }
        item("controls") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StitchTag("${visible.size} باقة")
                Button(onClick = onAdd, enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                    Text("إضافة باقة")
                }
            }
        }
        item("search") {
            OutlinedTextField(search, onValueChange = { search = it },
                placeholder = { Text("ابحث باسم الفئة أو قيمتها") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = softOutlinedTextFieldColors()
)
        }
        if (visible.isEmpty()) item("empty") {
            Text(if (query.isEmpty()) "لا توجد باقات" else "لا توجد نتائج مطابقة")
        }
        items(visible, key = { it.id }) { pkg ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        PackageBalanceCard(pkg.name, pkg.nominalValueMinor.toString(), pkg.colorArgb,
                            modifier = Modifier.weight(1f), caption = "قيمة الباقة")
                        if (!pkg.isActive) StitchTag("موقوف", color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(pkg) }, enabled = !busy) { Text("تعديل") }
                        TextButton(onClick = { onToggle(pkg) }, enabled = !busy) {
                            Text(if (pkg.isActive) "إيقاف" else "تفعيل")
                        }
                        TextButton(onClick = { onRemove(pkg) }, enabled = !busy) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
