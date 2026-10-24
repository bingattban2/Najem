package com.najimwifi.app.domain.service

import com.najimwifi.app.domain.contract.CollectionReversalRequest
import com.najimwifi.app.domain.contract.SettlementReversalRequest
import com.najimwifi.app.domain.contract.AccountingRepository
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.contract.PostingReceipt
import com.najimwifi.app.domain.contract.QuantityCorrectionRequest
import com.najimwifi.app.domain.contract.ReturnRequest
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.rules.AccountingRules
import com.najimwifi.app.domain.rules.CalculatedSettlement
import com.najimwifi.app.domain.rules.PackageBalance

/** Application entry point; no Android APIs and no UI-specific financial math. */
class SettlementService(private val repository: AccountingRepository) {

    /** Preview is advisory: the repository recomputes against Room at commit time. */
    fun preview(request: SettlementRequest): CalculatedSettlement {
        require(request.operationId.isNotBlank() && request.customerId.isNotBlank())
        require(request.counts.map { it.packageId }.distinct().size == request.counts.size)

        return AccountingRules.calculateSettlement(
            mode = request.mode,
            balances = request.counts.map {
                PackageBalance(
                    it.packageId,
                    it.expectedPackage,
                    it.expectedShopBalance,
                    true
                )
            },
            selectedPackageId = request.selectedPackageId,
            remainingByPackageId = request.counts.associate {
                it.packageId to it.remainingCount
            },
            previousDebtMinor = request.expectedPreviousDebtMinor,
            paymentMinor = request.singlePaymentMinor
        )
    }

    suspend fun confirmSettlement(request: SettlementRequest): PostingReceipt {
        require(request.confirmed) {
            "Physical count requires explicit confirmation"
        }
        preview(request)
        return repository.settle(request)
    }

    suspend fun confirmDelivery(request: DeliveryRequest): PostingReceipt {
        require(request.confirmed && request.quantity > 0L)
        return repository.deliver(request)
    }

    suspend fun confirmPayment(request: DebtPaymentRequest): PostingReceipt {
        require(request.confirmed && request.amountMinor >= 0L)
        return repository.payDebt(request)
    }

    suspend fun confirmReturn(request: ReturnRequest): PostingReceipt {
        require(request.confirmed && request.quantity > 0L)
        return repository.returnUnsold(request)
    }

    suspend fun confirmQuantityCorrection(
        request: QuantityCorrectionRequest
    ): PostingReceipt {
        require(
            request.confirmed &&
                request.reason.isNotBlank() &&
                request.authorizedBy.isNotBlank()
        )
        return repository.reverseQuantity(request)
    }


    suspend fun confirmCollectionReversal(request: CollectionReversalRequest): PostingReceipt {
        require(request.confirmed && request.reason.isNotBlank() && request.authorizedBy.isNotBlank())
        return repository.reverseCollection(request)
    }

    suspend fun confirmSettlementReversal(request: SettlementReversalRequest): PostingReceipt {
        require(request.confirmed && request.reason.isNotBlank() && request.authorizedBy.isNotBlank())
        return repository.reverseSettlement(request)
    }
}
