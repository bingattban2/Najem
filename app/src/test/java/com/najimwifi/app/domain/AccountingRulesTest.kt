package com.najimwifi.app.domain

import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.rules.AccountingRules
import com.najimwifi.app.domain.rules.PackageBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountingRulesTest {
    private val first = PackageBalance(
        "p200", PackageSnapshot("باقة 200", 200, 180, 0x112233), 100, true
    )
    private val second = PackageBalance(
        "p500", PackageSnapshot("باقة 500", 500, 450, 0x334455), 50, true
    )

    @Test fun onePackageLeavesOtherOutsideTheSettlement() {
        val result = AccountingRules.calculateSettlement(
            SettlementMode.ONE, listOf(first, second), "p200",
            mapOf("p200" to 35L), 3_000, 0
        )
        assertEquals(1, result.lines.size)
        assertEquals("p200", result.lines.single().packageId)
        assertEquals(65L, result.lines.single().sold)
        assertEquals(11_700L, result.newAmountDue.minorUnits)
        assertEquals(14_700L, result.endingDebt.minorUnits)
    }

    @Test fun fixtureBAllPackagesPostsOnePaymentAndOneFinalDebt() {
        val result = AccountingRules.calculateSettlement(
            SettlementMode.ALL, listOf(first, second), null,
            mapOf("p200" to 35L, "p500" to 20L), 3_000, 20_000
        )
        assertEquals(2, result.lines.size)
        assertEquals(listOf(65L, 30L), result.lines.map { it.sold })
        assertEquals(listOf(11_700L, 13_500L), result.lines.map { it.amountDue.minorUnits })
        assertEquals(25_200L, result.newAmountDue.minorUnits)
        assertEquals(20_000L, result.singlePayment.minorUnits)
        assertEquals(8_200L, result.endingDebt.minorUnits)
    }

    @Test fun blankNegativeTooLargeAndMissingLineAreRejected() {
        val balances = listOf(first, second)
        for (invalid in listOf(null, -1L, 101L)) {
            assertThrows(IllegalArgumentException::class.java) {
                AccountingRules.calculateSettlement(
                    SettlementMode.ONE, balances, "p200",
                    mapOf("p200" to invalid), 0, 0
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountingRules.calculateSettlement(
                SettlementMode.ALL, balances, null, mapOf("p200" to 35L), 0, 0
            )
        }
    }

    @Test fun invalidPaymentAndDuplicatePackagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountingRules.calculateSettlement(
                SettlementMode.ONE, listOf(first), "p200", mapOf("p200" to 99L), 0, 181
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountingRules.calculateSettlement(
                SettlementMode.ONE, listOf(first, first), "p200", mapOf("p200" to 0L), 0, 0
            )
        }
    }

    @Test fun deliveriesAndReturnsNeverProduceReceivables() {
        val delivery = AccountingRules.deliver(0, 100, true)
        assertEquals(100L, delivery.shopAfter)
        assertEquals(null, delivery.centralAfter)
        val returned = AccountingRules.returnUnsold(100, 25)
        assertEquals(75L, returned.shopAfter)
        assertThrows(IllegalArgumentException::class.java) {
            AccountingRules.returnUnsold(100, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountingRules.deliver(0, 5, true, centralOnHand = 4)
        }
    }

    @Test fun fixtureAExactFollowOnBalances() {
        val package200 = first.copy(snapshot = first.snapshot.copy(generalSettlementPriceMinor = 200))
        val settled = AccountingRules.calculateSettlement(
            SettlementMode.ONE, listOf(package200), "p200", mapOf("p200" to 35L), 3_000, 10_000
        )
        assertEquals(13_000L, settled.newAmountDue.minorUnits)
        assertEquals(6_000L, settled.endingDebt.minorUnits)
        assertEquals(55L, AccountingRules.deliver(35, 20, true).shopAfter)
        assertEquals(4_000L, AccountingRules.payExistingDebt(6_000, 2_000).minorUnits)
    }
}
