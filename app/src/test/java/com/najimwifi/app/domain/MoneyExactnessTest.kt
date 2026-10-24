package com.najimwifi.app.domain

import com.najimwifi.app.domain.value.Money
import com.najimwifi.app.domain.report.ReportRequest
import com.najimwifi.app.domain.report.ReportScope
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyExactnessTest {
    @Test fun minorUnitArithmeticIsExact() {
        assertEquals(25_200L, (Money.ofMinorUnits(180).times(65) +
            Money.ofMinorUnits(450).times(30)).minorUnits)
        assertEquals(8_200L, (Money.ofMinorUnits(3_000) + Money.ofMinorUnits(25_200) -
            Money.ofMinorUnits(20_000)).minorUnits)
    }

    @Test fun negativeAmountsAndUnderflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Money.ofMinorUnits(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            Money.ofMinorUnits(100) - Money.ofMinorUnits(101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Money.ofMinorUnits(1).times(-1)
        }
    }

    @Test fun multiplicationAndAdditionOverflowAreRejected() {
        assertThrows(ArithmeticException::class.java) { Money.ofMinorUnits(Long.MAX_VALUE).times(2) }
        assertThrows(ArithmeticException::class.java) { Money.ofMinorUnits(Long.MAX_VALUE) + Money.ofMinorUnits(1) }
        assertThrows(ArithmeticException::class.java) {
            Money.sum(listOf(Money.ofMinorUnits(Long.MAX_VALUE), Money.ofMinorUnits(1)))
        }
    }

    @Test fun inclusiveOwnerDatesUseExclusiveNextMidnightInternally() {
        val request = ReportRequest(
            scope = ReportScope.DEBTS,
            fromDate = LocalDate.parse("2026-10-22"),
            toDate = LocalDate.parse("2026-10-23"),
            zoneId = ZoneId.of("Asia/Aden")
        )
        val midnight = LocalDate.parse("2026-10-23")
            .atStartOfDay(ZoneId.of("Asia/Aden")).toInstant().toEpochMilli()
        assertTrue(request.includesInstant(midnight))
        assertTrue(request.includesInstant(midnight + 86_400_000L - 1L))
        assertFalse(request.includesInstant(midnight + 86_400_000L))
    }
}
