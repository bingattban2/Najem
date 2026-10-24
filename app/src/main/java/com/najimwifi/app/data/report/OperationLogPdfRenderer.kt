package com.najimwifi.app.data.report

import android.content.Context
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.LocalDate

/** Display-only snapshot of committed operations; no database writes during export. */
data class OperationLogPdfRow(
    val atMillis: Long,
    val operationType: String,
    val operationLabel: String,
    val customerName: String,
    val packageName: String?,
    val cards: Long?,
    val amountDueMinor: Long?,
    val description: String
)

/** Uses the same A4 Arabic font, table and pagination as customer PDF reports. */
class OperationLogPdfRenderer(
    private val context: Context,
    private val table: PdfTableRenderer = PdfTableRenderer()
) {
    fun render(
        rows: List<OperationLogPdfRow>,
        query: String,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        destination: OutputStream,
        brand: String = "إدارة مبيعات الشبكة"
    ) {
        val pdf = PdfDocument()
        try {
            val period = listOfNotNull(
                fromDate?.let { "من $it" },
                toDate?.let { "إلى $it" }
            ).joinToString(" - ").ifBlank { "كل الفترات" }
            val page = PdfPageLayout(
                context, pdf, "سجل العمليات", "$period | عدد العمليات: ${rows.size}", brand
            )
            page.openPage()
            query.trim().takeIf { it.isNotEmpty() }?.let {
                page.paragraph("البحث: $it", 9f)
            }
            table.drawOperations(rows, page)
            page.finish()
            pdf.writeTo(destination)
        } finally {
            pdf.close()
        }
    }
}
