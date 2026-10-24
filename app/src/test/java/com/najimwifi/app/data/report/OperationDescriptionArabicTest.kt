package com.najimwifi.app.data.report

import org.junit.Assert.assertEquals
import org.junit.Test

/** Display-only localization never changes the persisted immutable audit event. */
class OperationDescriptionArabicTest {
    @Test fun settlementReferenceIsLocalizedAndShortened() {
        assertEquals("محاسبة — مرجع العملية: 12345678",
            OperationDescriptionArabic.display("Settlement 12345678-90ab-cdef"))
    }

    @Test fun deliveryReferenceIsLocalized() {
        assertEquals("إضافة كروت — مرجع العملية: 12345678",
            OperationDescriptionArabic.display("Delivery 12345678-xxxx"))
    }

    @Test fun unknownDataIsNotFabricated() {
        assertEquals("نص مخصص", OperationDescriptionArabic.display("نص مخصص"))
    }
}
