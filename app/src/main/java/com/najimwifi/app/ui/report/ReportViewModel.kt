package com.najimwifi.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najimwifi.app.data.report.ReportComposer
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.domain.report.ReportRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ReportUiState(
    val busy: Boolean = false,
    val document: ReportDocument? = null,
    val error: String? = null
)

/** Produces immutable report data, not a mock PDF. */
class ReportViewModel(
    private val composer: ReportComposer
) : ViewModel() {
    private val current = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = current

    fun prepare(request: ReportRequest) {
        if (current.value.busy) return
        current.value = ReportUiState(busy = true)
        viewModelScope.launch {
            try {
                val report = composer.compose(request)
                current.value = ReportUiState(document = report)
            } catch (cancelled: CancellationException) {
                current.value = ReportUiState()
                throw cancelled
            } catch (error: Exception) {
                current.value = ReportUiState(
                    error = error.message ?: "تعذر إعداد بيانات التقرير"
                )
            }
        }
    }

    fun clear() {
        if (!current.value.busy) current.value = ReportUiState()
    }
}