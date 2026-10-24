package com.najimwifi.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.domain.contract.CollectionReversalRequest
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.contract.SettlementReversalRequest
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.SettlementMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the existing isolated in-memory AccountingTestFixture. */
@RunWith(AndroidJUnit4::class)
class FinancialReversalTransactionTest {
    private lateinit var f: AccountingTestFixture

    @Before fun setup() = runBlocking {
        f = AccountingTestFixture()
        f.seed()
    }

    @After fun tearDown() = f.close()

    @Test fun settlementReversalRestoresBothCardBalancesAndDebtAtomically() = runBlocking {
        f.openingDebt3000()
        f.deliver(f.p200, 100)
        f.deliver(f.p500, 50)
        f.repo.settle(f.requestAll())
        assertEquals(8_200L, f.debt())
        val group = requireNotNull(f.db.settlementDao().findGroupByOperation("all-settle"))
        val correction = SettlementReversalRequest("undo-all", group.id,
            "تصحيح عد المستودع", "owner", true)
        val posted = f.repo.reverseSettlement(correction)
        assertEquals(3_000L, posted.customerDebtMinor)
        assertEquals(100L, f.cards(f.p200))
        assertEquals(50L, f.cards(f.p500))
        val finance = f.db.financialDao().findByOperation("undo-all")
        assertEquals(2, finance.size)
        assertEquals(1, finance.count { it.kind == FinancialEntryKind.RECEIVABLE_REVERSAL })
        assertEquals(1, finance.count { it.kind == FinancialEntryKind.COLLECTION_REVERSAL })
        assertEquals(2, f.db.cardMovementDao().findByOperation("undo-all").size)
        assertEquals(posted.referenceNumber, f.repo.reverseSettlement(correction).referenceNumber)
        assertEquals(2, f.db.financialDao().findByOperation("undo-all").size)
        assertEquals(3_000L, f.debt())
        assertTrue(runCatching {
            f.repo.reverseSettlement(correction.copy(operationId = "undo-all-again"))
        }.isFailure)
        assertTrue(runCatching {
            f.repo.reverseSettlement(correction.copy(reason = "سبب مختلف"))
        }.isFailure)
        assertNotNull(f.db.settlementDao().findGroup(group.id))
    }

    @Test fun independentCollectionReversalRestoresDebtAndIsIdempotent() = runBlocking {
        f.openingDebt3000()
        val original = f.repo.payDebt(DebtPaymentRequest(
            "collection", f.customer.id, 3_000L, 1_000L,
            confirmed = true
        ))
        val entry = requireNotNull(f.db.financialDao().findByReceipt(
            requireNotNull(original.referenceNumber)))
        assertEquals(2_000L, f.debt())
        val reverse = CollectionReversalRequest("undo-collection", entry.id,
            "تحصيل مسجل بالخطأ", "owner", true)
        val receipt = f.repo.reverseCollection(reverse)
        assertEquals(3_000L, receipt.customerDebtMinor)
        assertEquals(3_000L, f.repo.reverseCollection(reverse).customerDebtMinor)
        assertEquals(1, f.db.financialDao().findByOperation("undo-collection").size)
        assertTrue(runCatching {
            f.repo.reverseCollection(reverse.copy(operationId = "different-undo"))
        }.isFailure)
        assertNotNull(f.db.financialDao().findById(entry.id))
    }

    @Test fun reversalRefusesNegativeDebtAndLeavesEverythingUnchanged() = runBlocking {
        f.deliver(f.p200, 100)
        f.repo.settle(SettlementRequest(
            "one", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 100, 35, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        assertEquals(11_700L, f.debt())
        f.repo.payDebt(DebtPaymentRequest(
            "paid-all", f.customer.id, 11_700L, 11_700L,
            confirmed = true
        ))
        val group = requireNotNull(f.db.settlementDao().findGroupByOperation("one"))
        assertTrue(runCatching {
            f.repo.reverseSettlement(SettlementReversalRequest("invalid-undo",
                group.id, "مراجعة تسوية قديمة", "owner", true))
        }.isFailure)
        assertEquals(0L, f.debt())
        assertEquals(35L, f.cards(f.p200))
        assertTrue(f.db.financialDao().findByOperation("invalid-undo").isEmpty())
        assertTrue(f.db.cardMovementDao().findByOperation("invalid-undo").isEmpty())
        assertNull(f.db.auditDao().findById("invalid-undo"))
    }
}
