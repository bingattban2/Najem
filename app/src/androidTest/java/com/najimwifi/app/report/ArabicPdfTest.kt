package com.najimwifi.app.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.report.ArabicPdfRenderer
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.domain.report.ReportEventType
import com.najimwifi.app.domain.report.ReportRequest
import com.najimwifi.app.domain.report.ReportRow
import com.najimwifi.app.domain.report.ReportScope
import com.najimwifi.app.domain.report.ReportTotals
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real offline PDF generation, pagination and A4; visual Arabic proof is manual. */
@RunWith(AndroidJUnit4::class)
class ArabicPdfTest {
    @Test fun longArabicReportCreatesReadableMultiPageA4() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.cacheDir.resolve("najim_test_report_${System.nanoTime()}.pdf")
        val request = ReportRequest(scope = ReportScope.DEBTS)
        val lines = (1..180).map { i ->
            ReportRow(
                id = "test-row-$i",
                customerId = "customer-1",
                customerNameAtEvent = "محل تجريبي",
                customerLocationAtEvent = "حضرموت",
                packageId = "package-1",
                packageNameAtEvent = "باقة اختبار عربية",
                committedAtEpochMillis = 1_700_000_000_000L + i * 60_000L,
                type = ReportEventType.SETTLEMENT,
                nominalValueMinor = 200L,
                remainingQuantity = 15L,
                soldQuantity = 2L,
                amountDueMinor = 360L
            )
        }
        val report = ReportDocument(
            request = request,
            title = "كشف حساب عربي تجريبي",
            generatedAtEpochMillis = 1_700_000_000_000L,
            rows = lines,
            customers = emptyList(),
            totals = ReportTotals(
                deliveredCards = 0, soldCards = 360, returnedCards = 0,
                salesDueMinor = 64_800, receiptsMinor = 0,
                adjustmentsMinor = 0, closingCards = 15,
                openingDebtMinor = 0, closingDebtMinor = 64_800
            ),
            notes = listOf("هذا ملف اختبار لا يحتوي بيانات مستخدم حقيقية")
        )
        try {
            FileOutputStream(file).use { ArabicPdfRenderer(context).render(report, it) }
            assertTrue(file.length() > 1000L)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertTrue(renderer.pageCount > 1)
                    renderer.openPage(0).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                        val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(bitmap.width > 0 && bitmap.height > 0)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
