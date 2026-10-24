package com.najimwifi.app.domain

import com.najimwifi.app.domain.contract.CollectionReversalRequest
import com.najimwifi.app.domain.contract.SettlementReversalRequest
import com.najimwifi.app.domain.contract.AccountingRepository
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.contract.PostingReceipt
import com.najimwifi.app.domain.contract.QuantityCorrectionRequest
import com.najimwifi.app.domain.contract.ReturnRequest
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.service.SettlementService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettlementServiceTest {
    private val snapshot = PackageSnapshot("باقة", 200, 200, 123)
    private val repository = RecordingRepository()
    private val service = SettlementService(repository)

    private fun settlement(confirmed: Boolean = true) = SettlementRequest(
        operationId = "stable-id-001", customerId = "customer-a",
        mode = SettlementMode.ONE, selectedPackageId = "p200",
        counts = listOf(SettlementCountInput("p200", 100, 35, snapshot)),
        expectedPreviousDebtMinor = 3_000, singlePaymentMinor = 10_000,
        confirmed = confirmed
    )

    @Test fun previewIsReadOnlyAndConfirmationDelegatesSameRequestOnce() = runBlocking {
        val request = settlement()
        val preview = service.preview(request)
        assertEquals(65L, preview.lines.single().sold)
        assertEquals(13_000L, preview.newAmountDue.minorUnits)
        assertEquals(6_000L, preview.endingDebt.minorUnits)
        assertEquals(0, repository.settlementCalls)
        val receipt = service.confirmSettlement(request)
        assertEquals(request.operationId, receipt.operationId)
        assertEquals(1, repository.settlementCalls)
        assertEquals(request, repository.lastSettlement)
    }

    @Test fun rejectUnconfirmedAndMissingCountsWithoutPosting() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.confirmSettlement(settlement(confirmed = false)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.preview(settlement().copy(counts = listOf(
                SettlementCountInput("p200", 100, null, snapshot)
            )))
        }
        assertEquals(0, repository.settlementCalls)
    }

    @Test fun rejectsNonpositiveDeliveryReturnAndUnconfirmedCorrection() = runBlocking {
        val delivery = DeliveryRequest("d", "customer-a", "p200", 0, 0, snapshot, true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.confirmDelivery(delivery) }
        }
        val returned = ReturnRequest("r", "customer-a", "p200", 0, 1, snapshot, confirmed = true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.confirmReturn(returned) }
        }
        val correction = QuantityCorrectionRequest("c", "m", "", "owner", true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.confirmQuantityCorrection(correction) }
        }
        assertEquals(0, repository.deliveryCalls + repository.returnCalls + repository.correctionCalls)
    }

    @Test fun acceptedPaymentPreservesTheExactStableOperationId() = runBlocking {
        val request = DebtPaymentRequest("pay-unique", "customer-a", 6_000, 2_000, confirmed = true)
        val result = service.confirmPayment(request)
        assertEquals("pay-unique", result.operationId)
        assertEquals(1, repository.paymentCalls)
    }

    @Test fun financialCorrectionsRequireOwnerConfirmationAndPreserveIds() {
        runBlocking {
            val collection = CollectionReversalRequest("reversal-receipt", "receipt-id",
                "تحصيل مكرر", "owner", true)
            assertEquals("reversal-receipt",
                service.confirmCollectionReversal(collection).operationId)
            val group = SettlementReversalRequest("reversal-settlement", "group-id",
                "تسوية مكررة", "owner", true)
            assertEquals("reversal-settlement",
                service.confirmSettlementReversal(group).operationId)
            assertEquals(2, repository.correctionCalls)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.confirmSettlementReversal(group.copy(confirmed = false)) }
            }
        }
    }

    private class RecordingRepository : AccountingRepository {
        var settlementCalls = 0
        var deliveryCalls = 0
        var paymentCalls = 0
        var returnCalls = 0
        var correctionCalls = 0
        var lastSettlement: SettlementRequest? = null
        private fun receipt(id: String, customer: String) =
            PostingReceipt(id, null, customer, 0L, emptyMap())
        override suspend fun deliver(request: DeliveryRequest): PostingReceipt {
            deliveryCalls++
            return receipt(request.operationId, request.customerId)
        }
        override suspend fun settle(request: SettlementRequest): PostingReceipt {
            settlementCalls++
            lastSettlement = request
            return receipt(request.operationId, request.customerId)
        }
        override suspend fun payDebt(request: DebtPaymentRequest): PostingReceipt {
            paymentCalls++
            return receipt(request.operationId, request.customerId)
        }
        override suspend fun returnUnsold(request: ReturnRequest): PostingReceipt {
            returnCalls++
            return receipt(request.operationId, request.customerId)
        }
        override suspend fun reverseQuantity(request: QuantityCorrectionRequest): PostingReceipt {
            correctionCalls++
            return receipt(request.operationId, "customer-a")
        }
        override suspend fun reverseCollection(request: CollectionReversalRequest): PostingReceipt {
            correctionCalls++
            return receipt(request.operationId, "customer-a")
        }
        override suspend fun reverseSettlement(request: SettlementReversalRequest): PostingReceipt {
            correctionCalls++
            return receipt(request.operationId, "customer-a")
        }
    }
}
