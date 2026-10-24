package com.najimwifi.app.report

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.report.ReportComposer
import com.najimwifi.app.data.report.ReportDbRow
import com.najimwifi.app.data.report.ReportQueries
import com.najimwifi.app.data.report.ReportSource
import com.najimwifi.app.domain.report.ReportEventType
import com.najimwifi.app.domain.report.ReportRequest
import com.najimwifi.app.domain.report.ReportScope
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only, synthetic source rows; never opens or modifies najim_wifi.db. */
@RunWith(AndroidJUnit4::class)
class FinancialReversalReportTest {
    private val zone = ZoneId.of("Asia/Aden")
    private fun time(day: Int): Long =
        LocalDate.of(2026, 10, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun row(vararg fields: Pair<String, Any?>): ReportDbRow =
        ReportDbRow(mapOf(*fields))

    private fun financial(id: String, kind: String, amount: Long, day: Int,
                          reverses: String? = null): ReportDbRow = row(
        "id" to id, "kind" to kind, "customerId" to "customer-1",
        "customer_name" to "متجر الاختبار", "customer_location" to "حضرموت",
        "amountMinor" to amount, "committedAtEpochMillis" to time(day),
        "settlementGroupId" to if (reverses == null) "group-1" else null,
        "reversesEntryId" to reverses,
        "receiptNumber" to if (kind == "COLLECTION") "receipt-1" else null,
        "correctionReason" to if (reverses != null) "تصحيح معتمد" else null,
        "authorizedBy" to if (reverses != null) "المالك" else null
    )

    private fun movement(id: String, kind: String, quantity: Long, day: Int,
                         effect: String, reversedId: String? = null): ReportDbRow = row(
        "id" to id, "customerId" to "customer-1", "packageId" to "pkg-1",
        "customer_name" to "متجر الاختبار", "customer_location" to "حضرموت",
        "package_name" to "باقة 200", "package_nominalValueMinor" to 200L,
        "package_generalSettlementPriceMinor" to 100L,
        "kind" to kind, "quantity" to quantity, "effect" to effect,
        "committedAtEpochMillis" to time(day), "referenceNumber" to "ref-$id",
        "reversesMovementId" to reversedId,
        "correctionReason" to if (reversedId != null) "تصحيح معتمد" else null,
        "authorizedBy" to if (reversedId != null) "المالك" else null
    )

    private fun source(badReversal: Boolean = false): ReportSource = ReportSource(
        customers = listOf(row("id" to "customer-1", "name" to "متجر الاختبار")),
        movements = listOf(
            movement("delivery", "DELIVERY", 10, 19, "INCREASE"),
            movement("sale", "SETTLEMENT_SALE", 4, 20, "DECREASE"),
            movement("reversed-sale", "CORRECTION", 4, 22, "INCREASE", "sale")
        ),
        groups = listOf(row(
            "id" to "group-1", "customerId" to "customer-1",
            "customer_name" to "متجر الاختبار", "customer_location" to "حضرموت",
            "committedAtEpochMillis" to time(20), "totalAmountDueMinor" to 400L,
            "singlePaymentMinor" to 200L, "paymentEntryId" to "payment"
        )),
        lines = listOf(row(
            "id" to "line-1", "settlementGroupId" to "group-1", "packageId" to "pkg-1",
            "package_name" to "باقة 200", "package_nominalValueMinor" to 200L,
            "package_generalSettlementPriceMinor" to 100L,
            "soldQuantity" to 4L, "stockRemaining" to 6L, "amountDueMinor" to 400L
        )),
        financial = listOf(
            financial("receivable", "SETTLEMENT_RECEIVABLE", 400L, 20),
            financial("payment", "COLLECTION", 200L, 20),
            financial("reversed-receivable", "RECEIVABLE_REVERSAL",
                if (badReversal) 399L else 400L, 22, "receivable"),
            financial("reversed-payment", "COLLECTION_REVERSAL", 200L, 22, "payment")
        ),
        archiveMemberships = emptyList()
    )

    private fun composer(block: (ReportComposer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NajimDatabase::class.java).build()
        try { block(ReportComposer(ReportQueries(db))) }
        finally { db.close() }
    }

    @Test fun reversalIsAuditableAndOnlyVisibleAfterPeriodEnd() = composer { engine ->
        val request = ReportRequest(
            scope = ReportScope.CUSTOMER, customerId = "customer-1", zoneId = zone
        )
        val reversed = engine.fromSnapshot(request, source())
        assertEquals(0L, reversed.totals.closingDebtMinor)
        assertEquals(10L, reversed.totals.closingCards)
        assertEquals(200L, reversed.totals.reversedCollectionsMinor)
        assertEquals(400L, reversed.totals.reversedReceivablesMinor)
        assertEquals(1, reversed.rows.count { it.type == ReportEventType.PAYMENT })
        assertEquals(time(22), requireNotNull(reversed.rows.first {
            it.type == ReportEventType.PAYMENT
        }.reversedAtEpochMillis))
        assertTrue(reversed.rows.any {
            it.correctionKind == "عكس مستحق محاسبة" &&
                it.adjustmentMinor == -400L && it.authorizedBy == "المالك"
        })
        assertTrue(reversed.rows.any {
            it.correctionKind == "عكس بيع محاسبة" &&
                it.consignmentDeltaQuantity == 4L
        })

        val before = engine.fromSnapshot(
            request.copy(toDate = LocalDate.of(2026, 10, 20)), source()
        )
        assertEquals(200L, before.totals.closingDebtMinor)
        assertEquals(6L, before.totals.closingCards)
        assertEquals(0L, before.totals.reversedCollectionsMinor)
        assertNull(before.rows.first { it.type == ReportEventType.PAYMENT }
            .reversedAtEpochMillis)
    }

    @Test fun mismatchedReversalRejectsReportRatherThanMisstateDebt() = composer { engine ->
        val request = ReportRequest(scope = ReportScope.CUSTOMER, customerId = "customer-1")
        assertThrows(IllegalArgumentException::class.java) {
            engine.fromSnapshot(request, source(badReversal = true))
        }
    }
}
