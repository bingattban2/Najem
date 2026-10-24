package com.najimwifi.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.entity.CustomerEntity
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.data.local.entity.SettlementGroupEntity
import com.najimwifi.app.data.local.entity.SettlementLineEntity
import com.najimwifi.app.data.repository.AccountingRepositoryImpl
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.DeliveryRequest
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.SettlementMode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Shared fresh in-memory Room database; never touches the installed user's data. */
internal class AccountingTestFixture(firstPrice: Long = 180L) {
    val db: NajimDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), NajimDatabase::class.java
    ).build()
    val repo = AccountingRepositoryImpl(db)
    val customer = CustomerEntity("customer-a", "محل أ", "تريم", null, createdAtEpochMillis = 1L)
    val p200 = PackageEntity("p200", "باقة 200", "200", 200, firstPrice, 0x112233, createdAtEpochMillis = 1L)
    val p500 = PackageEntity("p500", "باقة 500", "500", 500, 450, 0x334455, createdAtEpochMillis = 1L)
    val initial = PackageEntity("initial", "باقة افتتاح الدين", "initial", 900, 200, 0x556677, createdAtEpochMillis = 1L)

    suspend fun seed() {
        db.customerDao().insert(customer)
        db.packageDao().insert(p200)
        db.packageDao().insert(p500)
        db.packageDao().insert(initial)
    }
    fun snapshot(pkg: PackageEntity) = PackageSnapshot(
        pkg.name, pkg.nominalValueMinor, pkg.generalSettlementPriceMinor, pkg.colorArgb
    )
    suspend fun cards(pkg: PackageEntity) = db.cardMovementDao().currentBalance(customer.id, pkg.id)
    suspend fun debt() = db.financialDao().currentDebt(customer.id)
    suspend fun deliver(pkg: PackageEntity, quantity: Long, id: String = UUID.randomUUID().toString()) =
        repo.deliver(DeliveryRequest(id, customer.id, pkg.id, quantity, cards(pkg), snapshot(pkg), true))
    suspend fun openingDebt3000() {
        deliver(initial, 15, "opening-delivery")
        repo.settle(
            SettlementRequest("opening-settle", customer.id, SettlementMode.ONE, initial.id,
                listOf(SettlementCountInput(initial.id, 15, 0, snapshot(initial))),
                0, 0, confirmed = true)
        )
        assertEquals(3_000L, debt())
    }
    suspend fun requestAll(
        id: String = "all-settle", firstRemaining: Long? = 35,
        secondRemaining: Long? = 20, payment: Long = 20_000,
        expectedDebt: Long = 3_000
    ) = SettlementRequest(
        operationId = id, customerId = customer.id, mode = SettlementMode.ALL,
        selectedPackageId = null,
        counts = listOf(
            SettlementCountInput(p200.id, 100, firstRemaining, snapshot(p200)),
            SettlementCountInput(p500.id, 50, secondRemaining, snapshot(p500))
        ), expectedPreviousDebtMinor = expectedDebt,
        singlePaymentMinor = payment, confirmed = true
    )
    fun close() = db.close()
}

@RunWith(AndroidJUnit4::class)
class SettlementTransactionTest {
    private lateinit var fixture: AccountingTestFixture
    @Before fun setup() = runBlocking {
        fixture = AccountingTestFixture()
        fixture.seed()
    }
    @After fun teardown() { fixture.close() }

    @Test fun fixtureBAllPostsTwoLinesOneReceiptAndOneDebt() = runBlocking {
        fixture.openingDebt3000()
        fixture.deliver(fixture.p200, 100)
        fixture.deliver(fixture.p500, 50)
        val posted = fixture.repo.settle(fixture.requestAll())
        assertEquals(25_200L, posted.amountDueMinor)
        assertEquals(20_000L, posted.paidMinor)
        assertEquals(8_200L, posted.customerDebtMinor)
        assertEquals(35L, fixture.cards(fixture.p200))
        assertEquals(20L, fixture.cards(fixture.p500))
        val group = requireNotNull(fixture.db.settlementDao().findGroupByOperation("all-settle"))
        assertEquals(2, fixture.db.settlementDao().findLines(group.id).size)
        assertEquals(1, fixture.db.financialDao().findByOperation("all-settle")
            .count { it.kind == FinancialEntryKind.COLLECTION })
        assertNotNull(fixture.db.financialDao().findByReceipt(requireNotNull(posted.referenceNumber)))
    }

    @Test fun singlePackageLeavesSecondPackageUntouched() = runBlocking {
        fixture.deliver(fixture.p200, 100)
        fixture.deliver(fixture.p500, 50)
        fixture.repo.settle(SettlementRequest(
            "one-settle", fixture.customer.id, SettlementMode.ONE, fixture.p200.id,
            listOf(SettlementCountInput(fixture.p200.id, 100, 35, fixture.snapshot(fixture.p200))),
            0, 0, confirmed = true
        ))
        assertEquals(35L, fixture.cards(fixture.p200))
        assertEquals(50L, fixture.cards(fixture.p500))
        assertEquals(11_700L, fixture.debt())
    }

    @Test fun duplicateAndConcurrentRetryDoesNotPostTwice() = runBlocking {
        fixture.openingDebt3000()
        fixture.deliver(fixture.p200, 100)
        fixture.deliver(fixture.p500, 50)
        val request = fixture.requestAll()
        val first = fixture.repo.settle(request)
        val repeated = fixture.repo.settle(request)
        assertEquals(first.referenceNumber, repeated.referenceNumber)
        coroutineScope {
            val attempts = (1..2).map { async(Dispatchers.IO) { fixture.repo.settle(request) } }
            attempts.forEach { assertEquals(first.referenceNumber, it.await().referenceNumber) }
        }
        assertEquals(2, fixture.db.financialDao().findByOperation(request.operationId).size)
        assertEquals(2, fixture.db.settlementDao()
            .findLines(requireNotNull(fixture.db.settlementDao().findGroupByOperation(request.operationId)).id).size)
        assertEquals(8_200L, fixture.debt())
        assertTrue(runCatching {
            fixture.repo.settle(request.copy(singlePaymentMinor = 19_999))
        }.isFailure)
    }

    @Test fun staleCountsAndInvalidPaymentDoNotPartiallyPost() = runBlocking {
        fixture.deliver(fixture.p200, 100)
        fixture.deliver(fixture.p500, 50)
        assertTrue(runCatching {
            fixture.repo.settle(fixture.requestAll(id = "invalid-balance", expectedDebt = 1L, payment = 0L))
        }.isFailure)
        assertTrue(runCatching {
            fixture.repo.settle(fixture.requestAll(id = "invalid-count", firstRemaining = 101L, payment = 0L, expectedDebt = 0L))
        }.isFailure)
        assertNull(fixture.db.settlementDao().findGroupByOperation("invalid-balance"))
        assertNull(fixture.db.settlementDao().findGroupByOperation("invalid-count"))
        assertEquals(0L, fixture.debt())
        assertEquals(100L, fixture.cards(fixture.p200))
        assertEquals(50L, fixture.cards(fixture.p500))
    }

    @Test fun secondLineConstraintFailureRollsBackFirstLineAndGroup() = runBlocking {
        val snapshot = CustomerSnapshot(fixture.customer.name, fixture.customer.location)
        val group = SettlementGroupEntity(
            "rollback-group", "rollback-op", fixture.customer.id,
            SettlementMode.ALL, 1000L, snapshot, 0, 11_700, 0, 11_700
        )
        val first = SettlementLineEntity(
            "rollback-line-1", group.id, fixture.p200.id, 100, 35, 65, 11_700,
            snapshot, fixture.snapshot(fixture.p200)
        )
        val duplicate = first.copy(id = "rollback-line-2") // same group+package unique index
        val error = runCatching {
            fixture.db.withTransaction {
                fixture.db.settlementDao().insertGroup(group)
                fixture.db.settlementDao().insertLines(listOf(first, duplicate))
            }
        }.exceptionOrNull()
        assertNotNull(error)
        assertNull(fixture.db.settlementDao().findGroup(group.id))
        assertTrue(fixture.db.settlementDao().findLines(group.id).isEmpty())
        assertEquals(0L, fixture.debt())
    }
}
