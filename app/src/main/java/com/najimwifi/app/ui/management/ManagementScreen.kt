package com.najimwifi.app.ui.management

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.VerticalDivider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.najimwifi.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package

private enum class ManagementPage {
    HOME,
    CUSTOMERS,
    PACKAGES
}

/**
 * Exactly four management cards:
 * Customers, Packages, Tables, Operations.
 *
 * Operations log belongs to its own internal
 * destination supplied by onOpenOperations.
 */
@Composable
fun ManagementScreen(
    viewModel: ManagementViewModel,
    onOpenColumns: () -> Unit,
    onOpenOperations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    var page by remember {
        mutableStateOf(ManagementPage.HOME)
    }

    var editingCustomer by remember {
        mutableStateOf<Customer?>(null)
    }

    var editingPackage by remember {
        mutableStateOf<Package?>(null)
    }

    var customerEditorOpen by remember {
        mutableStateOf(false)
    }

    var packageEditorOpen by remember {
        mutableStateOf(false)
    }

    var customerToRemove by remember {
        mutableStateOf<Customer?>(null)
    }

    var packageToRemove by remember {
        mutableStateOf<Package?>(null)
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        state.message?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
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

        when (page) {
            ManagementPage.HOME -> {
                Column(modifier = Modifier.fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("الإدارة", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    Text("إدارة العملاء والباقات والجداول والسجل",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("ملخص الإدارة", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                SummaryMetric("العملاء", state.customers.size.toString(),
                                    R.drawable.najim_management_customers, Modifier.weight(1f))
                                VerticalDivider(Modifier.size(width = 1.dp, height = 54.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.20f))
                                SummaryMetric("الباقات", state.packages.size.toString(),
                                    R.drawable.najim_management_packages, Modifier.weight(1f))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ManagementCard(title = "العملاء", subtitle = "${state.customers.size} عميل",
                            modifier = Modifier.weight(1f)) { page = ManagementPage.CUSTOMERS }
                        ManagementCard(title = "الباقات", subtitle = "${state.packages.size} باقة",
                            modifier = Modifier.weight(1f)) { page = ManagementPage.PACKAGES }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ManagementCard(title = "الجداول", subtitle = "تخصيص الأعمدة",
                            modifier = Modifier.weight(1f), onClick = onOpenColumns)
                        ManagementCard(title = "سجل العمليات", subtitle = "جميع الحركات والتصدير",
                            modifier = Modifier.weight(1f), onClick = onOpenOperations)
                    }
                }
            }

            ManagementPage.CUSTOMERS -> CustomerListScreen(
                customers = state.customers,
                busy = state.busy,
                onBack = {
                    page = ManagementPage.HOME
                },
                onAdd = {
                    editingCustomer = null
                    customerEditorOpen = true
                },
                onEdit = {
                    editingCustomer = it
                    customerEditorOpen = true
                },
                onToggle = viewModel::toggleCustomer,
                onRemove = {
                    customerToRemove = it
                }
            )

            ManagementPage.PACKAGES -> PackageListScreen(
                packages = state.packages,
                busy = state.busy,
                onBack = {
                    page = ManagementPage.HOME
                },
                onAdd = {
                    editingPackage = null
                    packageEditorOpen = true
                },
                onEdit = {
                    editingPackage = it
                    packageEditorOpen = true
                },
                onToggle = viewModel::togglePackage,
                onRemove = {
                    packageToRemove = it
                }
            )
        }
    }

    if (customerEditorOpen) {
        CustomerEditorDialog(
            original = editingCustomer,
            busy = state.busy,
            onDismiss = {
                customerEditorOpen = false
            },
            onSave = { draft ->
                viewModel.saveCustomer(
                    draft,
                    editingCustomer
                ) {
                    customerEditorOpen = false
                }
            }
        )
    }

    if (packageEditorOpen) {
        PackageEditorDialog(
            original = editingPackage,
            existingPackages = state.packages,
            busy = state.busy,
            onDismiss = {
                packageEditorOpen = false
            },
            onSave = { draft ->
                viewModel.savePackage(
                    draft,
                    editingPackage
                ) {
                    packageEditorOpen = false
                }
            }
        )
    }

    customerToRemove?.let { customer ->
        RemovalDialog(
            subject = customer.name,
            busy = state.busy,
            onDismiss = {
                customerToRemove = null
            },
            onConfirm = {
                customerToRemove = null
                viewModel.removeCustomer(customer)
            }
        )
    }

    packageToRemove?.let { pkg ->
        RemovalDialog(
            subject = pkg.name,
            busy = state.busy,
            onDismiss = {
                packageToRemove = null
            },
            onConfirm = {
                packageToRemove = null
                viewModel.removePackage(pkg)
            }
        )
    }
}

@Composable
private fun SummaryMetric(label: String, count: String, icon: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(21.dp))
        }
        Column {
            Text(count, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ManagementCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(
            onClick = onClick
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val iconRes = when (title) {
                "العملاء" -> R.drawable.najim_management_customers
                "الباقات" -> R.drawable.najim_management_packages
                "الجداول" -> R.drawable.najim_nav_management
                else -> R.drawable.najim_nav_archive
            }
            Box(
                modifier = Modifier.size(48.dp).background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp)
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RemovalDialog(
    subject: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        title = {
            Text("تأكيد الحذف النهائي")
        },
        text = {
            Text(
                "سيُحذف $subject وجميع عملياته نهائيًا إذا لم تكن هناك كروت غير مباعة. " +
                    "يشمل الحذف القيود المالية والأرشيف المرتبط بها. " +
                    "هذا الإجراء لا يمكن التراجع عنه؛ خذ نسخة احتياطية أولًا."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy
            ) {
                Text("تأكيد")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy
            ) {
                Text("إلغاء")
            }
        },
        shape = RoundedCornerShape(24.dp))
}