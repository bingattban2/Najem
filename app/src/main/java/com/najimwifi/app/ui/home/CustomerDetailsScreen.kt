package com.najimwifi.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.najimwifi.app.ui.theme.StitchPageHeader
import com.najimwifi.app.ui.theme.StitchTag

private enum class SimpleAction { ADD, SETTLE }

@Composable
fun CustomerDetailsScreen(
    viewModel: HomeViewModel,
    customerId: String,
    onBack: () -> Unit,
    onOpenPackage: (String) -> Unit,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detailsFlow = remember(viewModel, customerId) { viewModel.observeCustomerDetails(customerId) }
    val state by detailsFlow.collectAsState(initial = CustomerDetailsUiState())
    val posting by viewModel.postingState.collectAsState()
    val action = remember(customerId) { mutableStateOf<SimpleAction?>(null) }
    val customer = state.customer

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StitchPageHeader(customer?.name ?: "العميل", onBack = onBack)
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (customer != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StitchTag("الموقع: ${customer.location}", color = MaterialTheme.colorScheme.tertiary)
                    if (!customer.isActive) StitchTag("العميل موقوف", color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("فئات الكروت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StitchTag("${state.packages.size} فئات")
                }
                if (state.packages.isEmpty()) Text("لا توجد فئات")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < 330.dp) 2 else if (maxWidth >= 480.dp) 4 else 3
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        state.packages.chunked(columns).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.forEach { row ->
                                    PackageBalanceCard(packageName = row.pkg.name,
                                        displayValue = row.quantity.toString(), colorArgb = row.pkg.colorArgb,
                                        onClick = { onOpenPackage(row.pkg.id) }, modifier = Modifier.weight(1f))
                                }
                                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (posting.busy) StitchTag("جارٍ حفظ العملية")
            } else if (!state.loading && state.error == null) Text("العميل غير موجود")
        }
        if (customer != null) {
            // Always on screen, outside vertical scroll as requested by the owner.
            Surface(tonalElevation = 2.dp, shadowElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.clearPostingMessage(); action.value = SimpleAction.ADD },
                        enabled = !posting.busy && customer.isActive && state.packages.any { it.pkg.isActive },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("إضافة") }
                    FilledTonalButton(onClick = { viewModel.clearPostingMessage(); action.value = SimpleAction.SETTLE },
                        enabled = !posting.busy && state.packages.any { it.quantity > 0L },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("محاسبة") }
                    OutlinedButton(onClick = onOpenReport, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)) { Text("تصدير") }
                }
            }
        }
    }
    posting.message?.let {
        AlertDialog(onDismissRequest = viewModel::clearPostingMessage,
            title = { Text("تمت العملية بنجاح") },
            confirmButton = {
                TextButton(onClick = viewModel::clearPostingMessage) { Text("حسنًا") }
            }, shape = RoundedCornerShape(24.dp))
    }
    if (customer != null) {
        if (action.value == SimpleAction.ADD) {
            DeliveryDialog(customerId = customer.id, customerName = customer.name,
                customerLocation = customer.location, packages = state.packages,
                busy = posting.busy, error = posting.error,
                onDismiss = { if (!posting.busy) action.value = null },
                onConfirm = { request -> viewModel.confirmDelivery(request) { action.value = null } })
        }
        if (action.value == SimpleAction.SETTLE) {
            SettlementDialog(customerId = customer.id, customerName = customer.name,
                customerLocation = customer.location, packages = state.packages,
                previousDebtMinor = state.debtMinor, busy = posting.busy, error = posting.error,
                previewSettlement = viewModel::previewSettlement,
                onDismiss = { if (!posting.busy) action.value = null },
                onConfirm = { request -> viewModel.confirmSettlement(request) { action.value = null } })
        }
    }
}
