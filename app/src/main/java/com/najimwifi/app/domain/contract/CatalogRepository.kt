package com.najimwifi.app.domain.contract

import com.najimwifi.app.domain.model.Customer
import com.najimwifi.app.domain.model.Package
import kotlinx.coroutines.flow.Flow

data class CustomerDraft(
    val name: String,
    val location: String,
    val phone: String? = null
)

data class PackageDraft(
    val name: String,
    val nominalValueMinor: Long,
    val generalSettlementPriceMinor: Long,
    val colorArgb: Int
)

enum class RemovalResult {
    DELETED,
    DEACTIVATED
}

/** Catalog edits never rewrite committed historical event snapshots. */
interface CatalogRepository {

    fun observeCustomers(): Flow<List<Customer>>

    fun observePackages(): Flow<List<Package>>

    suspend fun saveCustomer(
        draft: CustomerDraft,
        customerId: String? = null,
        expectedUpdatedAtEpochMillis: Long? = null
    ): String

    suspend fun savePackage(
        draft: PackageDraft,
        packageId: String? = null,
        expectedUpdatedAtEpochMillis: Long? = null
    ): String

    suspend fun setCustomerActive(
        customerId: String,
        active: Boolean,
        expectedUpdatedAtEpochMillis: Long
    )

    suspend fun setPackageActive(
        packageId: String,
        active: Boolean,
        expectedUpdatedAtEpochMillis: Long
    )

    suspend fun removeCustomer(
        customerId: String,
        expectedUpdatedAtEpochMillis: Long
    ): RemovalResult

    suspend fun removePackage(
        packageId: String,
        expectedUpdatedAtEpochMillis: Long
    ): RemovalResult
}