package com.najimwifi.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.SettlementMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The one-query dashboard strategy must preserve per-package and per-customer balances. */
@RunWith(AndroidJUnit4::class)
class PackageTotalsQueryTest {
    private lateinit var fixture: AccountingTestFixture

    @Before fun setUp() = runBlocking {
        fixture = AccountingTestFixture()
        fixture.seed()
    }

    @After fun tearDown() { fixture.close() }

    @Test fun groupTotalsMatchIndividualPackageBalancesAndIncludeZeroInUi() = runBlocking {
        val dao = fixture.db.cardMovementDao()
        // SQL returns no rows for unused packages; the UI maps missing ids to zero.
        assertEquals(emptyList<Any>(), dao.observePackageTotals().first())
        fixture.deliver(fixture.p200, 12, "totals-a")
        fixture.deliver(fixture.p500, 7, "totals-b")

        val totals = dao.observePackageTotals().first().associate { it.packageId to it.quantity }
        assertEquals(12L, totals[fixture.p200.id])
        assertEquals(7L, totals[fixture.p500.id])
        assertEquals(0L, totals[fixture.initial.id] ?: 0L)
        assertEquals(dao.currentBalance(fixture.customer.id, fixture.p200.id), totals[fixture.p200.id])

        val customerTotals = dao.observeCustomerPackageTotals(fixture.customer.id)
            .first().associate { it.packageId to it.quantity }
        assertEquals(totals, customerTotals)
    }

    @Test fun groupTotalsTrackSettlementWithoutCountingSalesAsDeliveries() = runBlocking {
        val dao = fixture.db.cardMovementDao()
        fixture.deliver(fixture.p200, 10, "before-settlement")
        fixture.repo.settle(SettlementRequest(
            operationId = "query-settlement", customerId = fixture.customer.id,
            mode = SettlementMode.ONE, selectedPackageId = fixture.p200.id,
            counts = listOf(SettlementCountInput(
                fixture.p200.id, 10, 3, fixture.snapshot(fixture.p200)
            )), expectedPreviousDebtMinor = 0L,
            singlePaymentMinor = 0L, confirmed = true
        ))
        val total = dao.observePackageTotals().first().single { it.packageId == fixture.p200.id }
        val customerTotal = dao.observeCustomerPackageTotals(fixture.customer.id)
            .first().single { it.packageId == fixture.p200.id }
        assertEquals(3L, total.quantity)
        assertEquals(3L, customerTotal.quantity)
    }
}
