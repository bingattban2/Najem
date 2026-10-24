package com.najimwifi.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.domain.contract.DebtPaymentRequest
import com.najimwifi.app.domain.contract.SettlementCountInput
import com.najimwifi.app.domain.contract.SettlementRequest
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.service.ArchiveRequest
import com.najimwifi.app.domain.service.ArchiveService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveInvariantTest {
    private lateinit var f: AccountingTestFixture
    private lateinit var archive: ArchiveService
    @Before fun setup() = runBlocking {
        f = AccountingTestFixture(firstPrice = 200)
        f.seed()
        archive = ArchiveService(f.db)
    }
    @After fun teardown() = f.close()

    @Test fun fixtureAArchiveDoesNotChangeCardsDebtOrHistoricalSale() = runBlocking {
        f.openingDebt3000()
        f.deliver(f.p200, 100)
        val settlement = f.repo.settle(SettlementRequest(
            "fixture-a-settle", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 100, 35, f.snapshot(f.p200))),
            3_000, 10_000, confirmed = true
        ))
        assertEquals(13_000L, settlement.amountDueMinor)
        assertEquals(6_000L, f.debt())
        assertEquals(35L, f.cards(f.p200))

        val group = requireNotNull(f.db.settlementDao().findGroupByOperation("fixture-a-settle"))
        val originalLines = f.db.settlementDao().findLines(group.id)
        val request = ArchiveRequest("archive-fixture-a", "مالك التطبيق", setOf(group.id))
        val posted = archive.archive(request)
        val replay = archive.archive(request)
        assertTrue(replay.alreadyArchived)
        assertEquals(posted.batchId, replay.batchId)
        assertTrue(posted.recordCount >= 2) // group + sale and possibly settlement payment
        assertNotNull(f.db.archiveDao().findArchivedGroup(group.id))
        assertEquals(originalLines, f.db.settlementDao().findLines(group.id))
        assertEquals(6_000L, f.debt())
        assertEquals(35L, f.cards(f.p200))

        f.deliver(f.p200, 20, id = "fixture-a-next-delivery")
        assertEquals(55L, f.cards(f.p200))
        assertEquals(6_000L, f.debt())
        val collection = f.repo.payDebt(
            DebtPaymentRequest("fixture-a-standalone", f.customer.id, 6_000, 2_000, confirmed = true)
        )
        assertEquals(4_000L, collection.customerDebtMinor)
        assertEquals(55L, f.cards(f.p200))
        val standalone = f.db.financialDao().findByOperation("fixture-a-standalone")
            .single { it.kind == FinancialEntryKind.COLLECTION }
        val saved = archive.archive(ArchiveRequest(
            "archive-standalone", "مالك التطبيق", emptySet(), setOf(standalone.id)
        ))
        assertFalse(saved.alreadyArchived)
        assertEquals(1, saved.recordCount)
        assertEquals(4_000L, f.debt())
        assertEquals(55L, f.cards(f.p200))
    }

    @Test fun duplicateArchivedGroupCannotJoinDifferentBatch() = runBlocking {
        f.deliver(f.p200, 100)
        f.repo.settle(SettlementRequest(
            "s1", f.customer.id, SettlementMode.ONE, f.p200.id,
            listOf(SettlementCountInput(f.p200.id, 100, 35, f.snapshot(f.p200))),
            0, 0, confirmed = true
        ))
        val group = requireNotNull(f.db.settlementDao().findGroupByOperation("s1"))
        archive.archive(ArchiveRequest("archive1", "owner", setOf(group.id)))
        assertTrue(runCatching {
            archive.archive(ArchiveRequest("archive2", "owner", setOf(group.id)))
        }.isFailure)
        assertEquals(13_000L, f.debt())
        assertEquals(35L, f.cards(f.p200))
    }
}
