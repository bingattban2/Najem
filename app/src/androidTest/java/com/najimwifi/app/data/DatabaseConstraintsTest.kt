package com.najimwifi.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.local.entity.CardMovementEntity
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.SettlementMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseConstraintsTest {
    private lateinit var f: AccountingTestFixture
    @Before fun setup() = runBlocking { f = AccountingTestFixture(); f.seed() }
    @After fun teardown() = f.close()

    @Test fun blankPhoneIsAccepted() = runBlocking {
        assertNull(f.db.customerDao().findById(f.customer.id)?.phone)
    }

    @Test fun packageNameNominalAndColorAreUniqueButGeneralPriceMayMatch() = runBlocking {
        val pkg = f.p200
        suspend fun forbidden(candidate: PackageEntity) {
            assertTrue(runCatching { f.db.packageDao().insert(candidate) }.isFailure)
        }
        forbidden(pkg.copy(id = "dupe-name", nominalValueMinor = 300, colorArgb = 999))
        forbidden(pkg.copy(id = "dupe-nominal", normalizedName = "unique-name", colorArgb = 999))
        forbidden(pkg.copy(id = "dupe-color", normalizedName = "other-name", nominalValueMinor = 300))
        f.db.packageDao().insert(pkg.copy(
            id = "shared-price", name = "سعر عام مكرر", normalizedName = "shared",
            nominalValueMinor = 300, colorArgb = 999
        ))
        assertEquals(180L, f.db.packageDao().findById("shared-price")?.generalSettlementPriceMinor)
    }

    @Test fun foreignKeyRejectsMovementForMissingCustomer() = runBlocking {
        val movement = CardMovementEntity(
            id = "missing-customer-movement", operationId = "bad-foreign-key",
            referenceNumber = "DEL-invalid", customerId = "absent",
            packageId = f.p200.id, kind = CardMovementKind.DELIVERY,
            effect = ConsignmentEffect.INCREASE, quantity = 1,
            committedAtEpochMillis = 100,
            customerSnapshot = com.najimwifi.app.domain.model.CustomerSnapshot("مجهول", "مكان"),
            packageSnapshot = f.snapshot(f.p200)
        )
        assertTrue(runCatching { f.db.cardMovementDao().insert(movement) }.isFailure)
        assertEquals(0L, f.cards(f.p200))
    }

    @Test fun historicalSettlementSnapshotsSurvivePackageRepricing() = runBlocking {
        f.deliver(f.p200, 100)
        f.repo.settle(SettlementRequest(
            "history-settle", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 100, 35, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        val group = requireNotNull(f.db.settlementDao().findGroupByOperation("history-settle"))
        val before = f.db.settlementDao().findLines(group.id).single().packageSnapshot
        f.db.packageDao().update(f.p200.copy(
            name = "اسم معدل", normalizedName = "revised-p200",
            generalSettlementPriceMinor = 300,
            updatedAtEpochMillis = 2
        ))
        val after = f.db.settlementDao().findLines(group.id).single().packageSnapshot
        assertEquals(before, after)
        assertEquals(180L, after.generalSettlementPriceMinor)
        assertEquals(11_700L, f.debt())
        assertEquals(35L, f.cards(f.p200))
    }

    @Test fun customerWithHistoryCannotBeDeletedPhysically() = runBlocking {
        f.deliver(f.p200, 1)
        assertEquals(0, f.db.customerDao().deleteNeverUsed(f.customer.id))
        assertEquals(f.customer.id, f.db.customerDao().findById(f.customer.id)?.id)
    }
}
