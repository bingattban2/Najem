package com.najimwifi.app.domain.contract

import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.PaymentMethod
import com.najimwifi.app.domain.model.SettlementMode

/** The caller must reuse operationId unchanged on retries. */
data class DeliveryRequest(
    val operationId: String,
    val customerId: String,
    val packageId: String,
    val quantity: Long,
    val expectedShopBalance: Long,
    val expectedPackage: PackageSnapshot,
    val confirmed: Boolean
)

data class SettlementCountInput(
    val packageId: String,
    val expectedShopBalance: Long,
    val remainingCount: Long?,
    val expectedPackage: PackageSnapshot
)

data class SettlementRequest(
    val operationId: String,
    val customerId: String,
    val mode: SettlementMode,
    val selectedPackageId: String?,
    val counts: List<SettlementCountInput>,
    val expectedPreviousDebtMinor: Long,
    val singlePaymentMinor: Long,
    val paymentMethod: PaymentMethod = PaymentMethod.UNSPECIFIED,
    val paymentNote: String? = null,
    val confirmed: Boolean
)

data class DebtPaymentRequest(
    val operationId: String,
    val customerId: String,
    val expectedDebtMinor: Long,
    val amountMinor: Long,
    val method: PaymentMethod = PaymentMethod.UNSPECIFIED,
    val note: String? = null,
    val confirmed: Boolean
)

data class ReturnRequest(
    val operationId: String,
    val customerId: String,
    val packageId: String,
    val quantity: Long,
    val expectedShopBalance: Long,
    val expectedPackage: PackageSnapshot,
    val returnToCentral: Boolean = false,
    val confirmed: Boolean
)

/** All corrections append compensating records and never erase originals. */
sealed interface OwnerCorrectionRequest {
    val operationId: String
    val reason: String
    val authorizedBy: String
    val confirmed: Boolean
}

/** Undo a standalone posted collection with an auditable counter-entry. */
data class CollectionReversalRequest(
    override val operationId: String,
    val originalEntryId: String,
    override val reason: String,
    override val authorizedBy: String,
    override val confirmed: Boolean
) : OwnerCorrectionRequest

/** Undo all posted lines, the receivable and the single payment as one unit. */
data class SettlementReversalRequest(
    override val operationId: String,
    val settlementGroupId: String,
    override val reason: String,
    override val authorizedBy: String,
    override val confirmed: Boolean
) : OwnerCorrectionRequest

data class QuantityCorrectionRequest(
    override val operationId: String,
    val originalMovementId: String,
    override val reason: String,
    override val authorizedBy: String,
    override val confirmed: Boolean
) : OwnerCorrectionRequest

data class PostingReceipt(
    val operationId: String,
    val referenceNumber: String?,
    val customerId: String,
    val customerDebtMinor: Long,
    val affectedPackageBalances: Map<String, Long>,
    val amountDueMinor: Long = 0L,
    val paidMinor: Long = 0L
)

interface AccountingRepository {
    suspend fun deliver(request: DeliveryRequest): PostingReceipt
    suspend fun settle(request: SettlementRequest): PostingReceipt
    suspend fun payDebt(request: DebtPaymentRequest): PostingReceipt
    suspend fun returnUnsold(request: ReturnRequest): PostingReceipt
    suspend fun reverseQuantity(request: QuantityCorrectionRequest): PostingReceipt
    suspend fun reverseCollection(request: CollectionReversalRequest): PostingReceipt
    suspend fun reverseSettlement(request: SettlementReversalRequest): PostingReceipt
}