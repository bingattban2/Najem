package com.najimwifi.app.ui.report

import androidx.compose.foundation.shape.RoundedCornerShape

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.najimwifi.app.data.di.AppContainer
import com.najimwifi.app.data.report.PdfShareManager
import com.najimwifi.app.domain.report.ReportScope
import com.najimwifi.app.ui.archive.ArchiveFilterState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exports only source-backed reports. Save uses Android's document picker. */
@Composable
fun ReportExportDialog(
    container: AppContainer,
    viewModel: ReportViewModel,
    scope: ReportScope,
    initialCustomerId: String?,
    archiveFilters: ArchiveFilterState?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { PdfShareManager(context) }
    val coroutineScope = rememberCoroutineScope()
    val customers by container.catalogRepository.observeCustomers().collectAsState(initial = emptyList())
    val packages by container.catalogRepository.observePackages().collectAsState(initial = emptyList())
    val state by viewModel.state.collectAsState()
    var preparedFile by remember { mutableStateOf<File?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val file = preparedFile
        if (uri != null && file != null) {
            coroutineScope.launch {
                try {
                    manager.saveToUri(file, uri)
                    localMessage = "تم حفظ التقرير"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    localError = error.message ?: "تعذر حفظ التقرير"
                }
            }
        }
    }
    LaunchedEffect(state.document) {
        preparedFile = null
        val document = state.document ?: return@LaunchedEffect
        rendering = true
        localError = null
        try {
            preparedFile = withContext(Dispatchers.IO) { manager.createReport(document) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            localError = error.message ?: "تعذر إنشاء PDF"
        } finally {
            rendering = false
        }
    }

    if (state.document == null) {
        ReportFilterDialog(
            scope = scope,
            customers = customers,
            packages = packages,
            busy = state.busy,
            onDismiss = { viewModel.clear(); onClose() },
            onGenerate = { viewModel.prepare(it) },
            initialCustomerId = initialCustomerId,
            archiveFilters = archiveFilters
        )
        // When preparation fails, retain the selection and allow retry.
        state.error?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clear() },
                title = { Text("تعذر تجهيز التقرير") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { viewModel.clear() }) { Text("إغلاق الخطأ") } },
        shape = RoundedCornerShape(24.dp))
        }
    } else {
        AlertDialog(
            onDismissRequest = { if (!rendering) { viewModel.clear(); onClose() } },
            title = { Text("تصدير تقرير PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rendering) CircularProgressIndicator()
                    localError?.let { Text(it) }
                    localMessage?.let { Text(it) }
                    val file = preparedFile
                    if (file != null) {
                        OutlinedButton(onClick = {
                            try { manager.share(file) }
                            catch (error: Exception) { localError = error.message ?: "تعذرت المشاركة" }
                        }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("مشاركة PDF") }
                        Button(onClick = { saveLauncher.launch("najim_report.pdf") }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
                            Text("حفظ PDF")
                        }
                        OutlinedButton(onClick = {
                            var current: Context = context
                            while (current is ContextWrapper && current !is Activity) current = current.baseContext
                            val activity = current as? Activity
                            if (activity == null) localError = "خدمة الطباعة غير متاحة"
                            else try { manager.print(activity, file) }
                            catch (error: Exception) { localError = error.message ?: "تعذرت الطباعة" }
                        }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) { Text("طباعة") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.clear(); onClose() }) { Text("إغلاق") } },
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    }
}

/** A receipt is generated only from a real posted COLLECTION, never from UI input. */
