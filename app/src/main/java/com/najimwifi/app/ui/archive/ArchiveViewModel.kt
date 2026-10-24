package com.najimwifi.app.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najimwifi.app.domain.service.ArchiveCandidate
import com.najimwifi.app.domain.service.ArchiveEventRow
import com.najimwifi.app.domain.service.ArchivePaymentCandidate
import com.najimwifi.app.domain.service.ArchiveGroupDetails
import com.najimwifi.app.domain.service.ArchiveRequest
import com.najimwifi.app.domain.service.ArchiveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ArchiveUiState(
    val archived: List<ArchiveEventRow> = emptyList(),
    val eligible: List<ArchiveCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val eligiblePayments: List<ArchivePaymentCandidate> = emptyList(),
    val selectedPaymentIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val details: ArchiveGroupDetails? = null,
    val detailsLoading: Boolean = false
)

private data class ArchiveSecondary(
    val details: ArchiveGroupDetails?,
    val loading: Boolean,
    val payments: List<ArchivePaymentCandidate>,
    val selectedPayments: Set<String>
)

private data class ArchiveStatus(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

/**
 * No financial or consignment calculations are performed
 * in this ViewModel. ArchiveService owns all database work.
 */
class ArchiveViewModel(
    private val service: ArchiveService
) : ViewModel() {

    private val eligible =
        MutableStateFlow<List<ArchiveCandidate>>(emptyList())

    private val selected =
        MutableStateFlow<Set<String>>(emptySet())
    private val payments = MutableStateFlow<List<ArchivePaymentCandidate>>(emptyList())
    private val selectedPayments = MutableStateFlow<Set<String>>(emptySet())

    private val status =
        MutableStateFlow(ArchiveStatus())

    private val details =
        MutableStateFlow<ArchiveGroupDetails?>(null)

    private val detailsLoading = MutableStateFlow(false)

    private val archived = service.observeArchivedEvents()
        .catch { error ->
            status.value = status.value.copy(
                error = error.message
                    ?: "تعذر قراءة الأرشيف",
                loading = false
            )
            emit(emptyList())
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList()
        )

    val state: StateFlow<ArchiveUiState> = combine(
        archived,
        eligible,
        selected,
        status,
        combine(details, detailsLoading, payments, selectedPayments) { item, loading, available, checked ->
            ArchiveSecondary(item, loading, available, checked)
        }
    ) { rows, candidates, checked, current, dialog ->
        ArchiveUiState(
            archived = rows,
            eligible = candidates,
            selectedIds = checked,
            eligiblePayments = dialog.payments,
            selectedPaymentIds = dialog.selectedPayments,
            loading = current.loading,
            busy = current.busy,
            error = current.error,
            message = current.message,
            details = dialog.details,
            detailsLoading = dialog.loading
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        ArchiveUiState()
    )

    init {
        refreshCandidates()
    }

    fun refreshCandidates() {
        if (status.value.busy) return

        viewModelScope.launch {
            status.value = status.value.copy(
                loading = true,
                error = null
            )

            try {
                val candidates = service.pendingGroups()
                eligible.value = candidates
                val availablePayments = service.pendingPayments()
                payments.value = availablePayments
                selectedPayments.value = selectedPayments.value.intersect(
                    availablePayments.map { it.entryId }.toSet()
                )

                val availableIds = candidates.map {
                    it.groupId
                }.toSet()

                selected.value =
                    selected.value.intersect(availableIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.value = status.value.copy(
                    error = error.message
                        ?: "تعذر قراءة العمليات المؤهلة"
                )
            } finally {
                status.value = status.value.copy(
                    loading = false
                )
            }
        }
    }

    fun toggleSelected(
        groupId: String,
        checked: Boolean
    ) {
        if (status.value.busy) return

        if (eligible.value.none { it.groupId == groupId }) {
            return
        }

        selected.value = if (checked) {
            selected.value + groupId
        } else {
            selected.value - groupId
        }
    }

    fun togglePayment(entryId: String, checked: Boolean) {
        if (status.value.busy || payments.value.none { it.entryId == entryId }) return
        selectedPayments.value = if (checked) selectedPayments.value + entryId
            else selectedPayments.value - entryId
    }

    fun selectAllEligible() {
        if (status.value.busy) return

        selected.value = eligible.value.map { it.groupId }.toSet()
        selectedPayments.value = payments.value.map { it.entryId }.toSet()
    }

    fun clearSelection() {
        if (!status.value.busy) {
            selected.value = emptySet()
            selectedPayments.value = emptySet()
        }
    }

    fun dismissMessage() {
        status.value = status.value.copy(
            message = null,
            error = null
        )
    }

    fun archiveSelected(
        operationId: String,
        confirmedBy: String,
        requestedGroupIds: Set<String>,
        requestedPaymentIds: Set<String>,
        onSuccess: () -> Unit
    ) {
        if (status.value.busy) return

        status.value = status.value.copy(
            busy = true,
            error = null,
            message = null
        )

        viewModelScope.launch {
            try {
                require(requestedGroupIds.isNotEmpty() || requestedPaymentIds.isNotEmpty()) {
                    "اختر عمليات للأرشفة"
                }

                val result = service.archive(
                    ArchiveRequest(
                        operationId = operationId,
                        confirmedBy = confirmedBy,
                        groupIds = requestedGroupIds,
                        paymentIds = requestedPaymentIds
                    )
                )

                status.value = status.value.copy(
                    message = if (result.alreadyArchived) {
                        "كانت هذه الدفعة مؤرشفة مسبقًا"
                    } else {
                        "تم إنشاء دفعة الأرشفة بنجاح؛ " +
                            "عدد العضويات ${result.recordCount}"
                    }
                )

                selected.value = emptySet()
                selectedPayments.value = emptySet()
                eligible.value = service.pendingGroups()
                payments.value = service.pendingPayments()

                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.value = status.value.copy(
                    error = error.message
                        ?: "تعذر إكمال الأرشفة"
                )
            } finally {
                status.value = status.value.copy(
                    busy = false,
                    loading = false
                )
            }
        }
    }

    fun openDetails(
        groupId: String
    ) {
        if (detailsLoading.value) return

        viewModelScope.launch {
            details.value = null
            detailsLoading.value = true

            try {
                details.value = service.groupDetails(groupId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.value = status.value.copy(
                    error = error.message
                        ?: "تعذر قراءة تفاصيل المحاسبة"
                )
            } finally {
                detailsLoading.value = false
            }
        }
    }

    fun closeDetails() {
        details.value = null
    }
}