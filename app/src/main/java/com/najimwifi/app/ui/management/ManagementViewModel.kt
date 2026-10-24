package com.najimwifi.app.ui.management

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najimwifi.app.domain.contract.CatalogRepository
import com.najimwifi.app.domain.contract.CustomerDraft
import com.najimwifi.app.domain.contract.PackageDraft
import com.najimwifi.app.domain.contract.RemovalResult
import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManagementUiState(
    val customers: List<Customer> = emptyList(),
    val packages: List<Package> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

/** All displayed records are obtained from the repository, not test data. */
class ManagementViewModel(
    private val catalog: CatalogRepository
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ManagementUiState> = combine(
        catalog.observeCustomers(),
        catalog.observePackages(),
        busy,
        message
    ) { customers, packages, isBusy, notice ->
        ManagementUiState(
            customers = customers,
            packages = packages,
            busy = isBusy,
            message = notice
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        ManagementUiState()
    )

    fun dismissMessage() {
        message.value = null
    }

    private fun runChange(
        completion: () -> Unit = {},
        change: suspend () -> String
    ) {
        if (busy.value) return

        busy.value = true
        message.value = null

        viewModelScope.launch {
            try {
                message.value = change()
                completion()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SQLiteConstraintException) {
                message.value =
                    "توجد بيانات مكررة أو سجل مرتبط؛ " +
                        "تحقق من الاسم والقيمة واللون"
            } catch (error: Exception) {
                message.value =
                    error.message?.takeIf {
                        it.isNotBlank()
                    } ?: "تعذر تنفيذ العملية"
            } finally {
                busy.value = false
            }
        }
    }

    fun saveCustomer(
        draft: CustomerDraft,
        original: Customer?,
        onSuccess: () -> Unit
    ) = runChange(onSuccess) {
        catalog.saveCustomer(
            draft,
            original?.id,
            original?.updatedAtEpochMillis
        )
        "تم حفظ العميل"
    }

    fun savePackage(
        draft: PackageDraft,
        original: Package?,
        onSuccess: () -> Unit
    ) = runChange(onSuccess) {
        catalog.savePackage(
            draft,
            original?.id,
            original?.updatedAtEpochMillis
        )
        "تم حفظ الباقة"
    }

    fun toggleCustomer(
        customer: Customer
    ) = runChange {
        catalog.setCustomerActive(
            customer.id,
            !customer.isActive,
            customer.updatedAtEpochMillis
        )

        if (customer.isActive)
            "تم إيقاف العميل"
        else
            "تم تفعيل العميل"
    }

    fun togglePackage(
        pkg: Package
    ) = runChange {
        catalog.setPackageActive(
            pkg.id,
            !pkg.isActive,
            pkg.updatedAtEpochMillis
        )

        if (pkg.isActive)
            "تم إيقاف الباقة"
        else
            "تم تفعيل الباقة"
    }

    fun removeCustomer(
        customer: Customer
    ) = runChange {
        when (
            catalog.removeCustomer(
                customer.id,
                customer.updatedAtEpochMillis
            )
        ) {
            RemovalResult.DELETED ->
                "تم حذف العميل وعملياته"

            RemovalResult.DEACTIVATED ->
                "تعذر حذف العميل؛ توجد كروت غير مباعة"
        }
    }

    fun removePackage(
        pkg: Package
    ) = runChange {
        when (
            catalog.removePackage(
                pkg.id,
                pkg.updatedAtEpochMillis
            )
        ) {
            RemovalResult.DELETED ->
                "تم حذف الباقة وعملياتها"

            RemovalResult.DEACTIVATED ->
                "تعذر حذف الباقة؛ توجد كروت غير مباعة"
        }
    }
}