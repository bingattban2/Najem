package com.najimwifi.app.ui.operations

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationLogDateFilterTest {
    @Test fun acceptsArabicAndPersianNumerals() {
        assertEquals(LocalDate.of(2026, 10, 24), OperationLogDateFilter.parse("٢٠٢٦/١٠/٢٤"))
        assertEquals(LocalDate.of(2026, 10, 24), OperationLogDateFilter.parse("۲۰۲۶-۱۰-۲۴"))
        assertEquals(LocalDate.of(2026, 10, 24), OperationLogDateFilter.parse("2026-10-24"))
    }

    @Test fun rejectsInvalidCalendarDatesAndBackwardRange() {
        assertNull(OperationLogDateFilter.parse("2026/02/30"))
        assertNull(OperationLogDateFilter.parse("2026/13/01"))
        assertTrue(OperationLogDateFilter.validRange("", ""))
        assertTrue(OperationLogDateFilter.validRange("٢٠٢٦/٠١/٠١", "٢٠٢٦/١٠/٢٤"))
        assertFalse(OperationLogDateFilter.validRange("2026/10/25", "2026/10/24"))
        assertFalse(OperationLogDateFilter.validRange("2026/13/01", ""))
    }
}
