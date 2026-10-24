package com.najimwifi.app.ui.home


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.najimwifi.app.ui.theme.StitchTag
import com.najimwifi.app.ui.theme.CustomerAvatar
import com.najimwifi.app.ui.theme.westernDigits
import androidx.compose.ui.Alignment
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Stitch light/dark home. Counts reflect real shop balances, never invented sales. */
@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenCustomer: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val search = rememberSaveable { mutableStateOf("") }
    val today by produceState(LocalDate.now()) {
        while (true) { value = LocalDate.now(); delay(60_000L) }
    }
    val month = remember(today) {
        westernDigits(today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("ar"))))
    }
    val customers = remember(state.customers, search.value) {
        val query = search.value.trim()
        state.customers.filter { row ->
            query.isEmpty() || row.customer.name.contains(query, true) || row.customer.location.contains(query, true)
        }.sortedWith(compareBy<HomeCustomerRow> { !it.customer.isActive }
            .thenBy { it.customer.name }.thenBy { it.customer.id })
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item("month") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StitchTag("إدارة المبيعات")
                    StitchTag(month, color = MaterialTheme.colorScheme.tertiary)
                }
                Text("مبيعات شهر $month", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)
            }
        }
        item("counters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    Text("فئات الكروت", modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    StitchTag("${state.packages.size} فئات", modifier = Modifier.align(Alignment.CenterEnd))
                }
                if (state.packages.isEmpty() && !state.loading) Text("لا توجد فئات")
                else BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 330.dp) 2 else if (maxWidth >= 480.dp) 4 else 3
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        state.packages.chunked(columns).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.forEach { item ->
                                    PackageBalanceCard(packageName = item.pkg.name,
                                        displayValue = item.quantity.toString(), colorArgb = item.pkg.colorArgb,
                                        modifier = Modifier.weight(1f), compact = true)
                                }
                                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    Text("العملاء", modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    StitchTag("${customers.size} عميل", modifier = Modifier.align(Alignment.CenterEnd))
                }
                OutlinedTextField(
                    value = search.value,
                    onValueChange = { search.value = it },
                    placeholder = { Text("ابحث عن عميل أو موقع") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.value.isNotEmpty()) IconButton(onClick = { search.value = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "مسح البحث")
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
        if (state.loading) item("loading") { CircularProgressIndicator() }
        state.error?.let { issue -> item("error") { Text(issue, color = MaterialTheme.colorScheme.error) } }
        if (customers.isEmpty() && !state.loading && state.error == null)
            item("empty") { Text("لا يوجد عملاء مطابقون", Modifier.fillMaxWidth().padding(22.dp),
                textAlign = TextAlign.Center) }
        items(customers, key = { it.customer.id }) { row ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenCustomer(row.customer.id) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CustomerAvatar(row.customer.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.customer.name, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("الموقع: ${row.customer.location}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("${row.cardCount} كرت", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "فتح تفاصيل العميل",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                }
            }
        }
        item("bottom") { Spacer(Modifier.padding(bottom = 6.dp)) }
    }
}
