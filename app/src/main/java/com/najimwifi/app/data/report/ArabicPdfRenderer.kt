package com.najimwifi.app.data.report

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.domain.report.ReportEventType
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Offline PDF built with Android StaticLayout RTL/BiDi text shaping. */
class ArabicPdfRenderer(
    private val context: Context,
    private val table: PdfTableRenderer = PdfTableRenderer()
) {
    fun render(report: ReportDocument, destination: OutputStream, brand: String = "إدارة مبيعات الشبكة") {
        val document = PdfDocument()
        try {
            val zone = report.request.zoneId
            val formatter = DateTimeFormatter.ofPattern(
                "yyyy/MM/dd HH:mm", Locale.forLanguageTag("ar")
            ).withZone(zone)
            val period = listOfNotNull(
                report.request.fromDate?.let { "من $it" },
                report.request.toDate?.let { "إلى $it" }
            ).joinToString(" - ").ifBlank { "كل الفترات" }
            val page = PdfPageLayout(
                context, document, report.title,
                "$period    |    إصدار: ${formatter.format(Instant.ofEpochMilli(report.generatedAtEpochMillis))}",
                brand
            )
            page.openPage()
            if (report.request.customerId != null) {
                val historical = report.rows.firstOrNull()
                if (historical != null) {
                    page.paragraph(
                        "العميل وقت العملية: ${historical.customerNameAtEvent} | " +
                            "الموقع: ${historical.customerLocationAtEvent}", 10f, true
                    )
                } else {
                    report.customers.firstOrNull()?.let {
                        page.paragraph("العميل: ${it.customerLabel}", 10f, true)
                    }
                }
            }
            table.draw(report, page)
            page.finish()
            document.writeTo(destination)
        } finally {
            document.close()
        }
    }
}
