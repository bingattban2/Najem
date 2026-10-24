package com.najimwifi.app.report

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.report.OperationLogPdfRow
import com.najimwifi.app.data.report.PdfShareManager
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** PDF uses the same A4 table as customer exports; filtered input is not expanded. */
@RunWith(AndroidJUnit4::class)
class OperationLogPdfTest {
    @Test fun operationsExportUsesA4TableAndBreaksAcrossPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selectedRows = (1..90).map { number ->
            OperationLogPdfRow(
                atMillis = 1_700_000_000_000L + number * 86_400_000L,
                operationType = if (number % 2 == 0) "SETTLEMENT" else "DELIVERY",
                operationLabel = if (number % 2 == 0) "محاسبة" else "إضافة كروت",
                customerName = "عميل تجريبي رقم $number",
                packageName = "باقة اختبار",
                cards = 3,
                amountDueMinor = if (number % 2 == 0) 600 else null,
                description = "عملية محفوظة لاختبار التصدير إلى جدول عربي متعدد الصفحات. ".repeat(5)
            )
        }
        val pdf = PdfShareManager(context).createOperationLogReport(
            selectedRows, "عميل تجريبي", LocalDate.of(2023, 1, 1), null
        )
        try {
            assertTrue(pdf.length() > 1000L)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertTrue(renderer.pageCount > 1)
                    renderer.openPage(0).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                    }
                }
            }
        } finally {
            pdf.delete()
        }
    }
}
