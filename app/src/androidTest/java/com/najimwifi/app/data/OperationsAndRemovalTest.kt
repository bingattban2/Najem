package com.najimwifi.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.repository.CatalogRepositoryImpl
import com.najimwifi.app.domain.contract.RemovalResult
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.SettlementMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationsAndRemovalTest {
    private lateinit var f: AccountingTestFixture

    @Before fun setup() = runBlocking { f = AccountingTestFixture(); f.seed() }
    @After fun teardown() = f.close()

    @Test fun additionAppearsImmediatelyWithoutArchiving() = runBlocking {
        f.deliver(f.p200, 3)
        val records = f.db.auditDao().observeOperationLog().first()
        assertTrue(records.any { it.operationType == "DELIVERY" && it.cards == 3L })
    }

    @Test fun committedSettlementShowsDueInOperationsLogWithoutCustomerDebt() = runBlocking {
        f.deliver(f.p200, 4, "test-due-delivery")
        f.repo.settle(SettlementRequest(
            "test-due-settlement", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 4, 1, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        val rows = f.db.auditDao().observeOperationLog().first()
        val accounting = rows.single { it.operationType == "SETTLEMENT" }
        assertEquals(540L, accounting.amountMinor)
        assertEquals(3L, accounting.cards)
        assertEquals(f.customer.name, accounting.subjectName)
        val delivery = rows.single { it.operationType == "DELIVERY" }
        assertNull(delivery.amountMinor)
    }

    @Test fun customerRemovalRejectsRemainingCardsWithoutTouchingHistory() = runBlocking {
        f.deliver(f.p200, 3)
        val catalog = CatalogRepositoryImpl(f.db)
        val customer = requireNotNull(f.db.customerDao().findById(f.customer.id))
        assertTrue(runCatching {
            catalog.removeCustomer(customer.id, customer.updatedAtEpochMillis)
        }.isFailure)
        assertEquals(3L, f.cards(f.p200))
        assertTrue(f.db.auditDao().observeOperationLog().first().any { it.operationType == "DELIVERY" })
    }

    @Test fun customerDeletionAfterFullSaleLeavesNoOrphanEntries() = runBlocking {
        f.deliver(f.p200, 5)
        f.repo.settle(SettlementRequest(
            "purge-settle-c", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 5, 0, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        val catalog = CatalogRepositoryImpl(f.db)
        val customer = requireNotNull(f.db.customerDao().findById(f.customer.id))
        assertEquals(RemovalResult.DELETED, catalog.removeCustomer(
            customer.id, customer.updatedAtEpochMillis
        ))
        assertNull(f.db.customerDao().findById(customer.id))
        assertTrue(f.db.auditDao().observeOperationLog().first().any {
            it.description.startsWith("حذف العميل:")
        })
    }

    @Test fun packageDeletionAfterFullSaleKeepsCustomer() = runBlocking {
        f.deliver(f.p200, 4)
        f.repo.settle(SettlementRequest(
            "purge-settle-p", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 4, 0, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        val catalog = CatalogRepositoryImpl(f.db)
        val pkg = requireNotNull(f.db.packageDao().findById(f.p200.id))
        assertEquals(RemovalResult.DELETED, catalog.removePackage(
            pkg.id, pkg.updatedAtEpochMillis
        ))
        assertNull(f.db.packageDao().findById(pkg.id))
        assertTrue(f.db.customerDao().findById(f.customer.id) != null)
    }
}
