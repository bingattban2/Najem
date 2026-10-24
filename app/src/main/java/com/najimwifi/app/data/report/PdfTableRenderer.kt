package com.najimwifi.app.data.report

import android.graphics.Color
import android.graphics.Paint
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.domain.report.ReportEventType
import com.najimwifi.app.domain.report.ReportRow
import com.najimwifi.app.domain.report.ReportScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Customer report has 7 columns; archive PDF has its separate 8 requested columns. */
class PdfTableRenderer {
    private val customerWidths = floatArrayOf(90f, 105f, 62f, 60f, 72f, 58f, 88f)
    private val archiveWidths = floatArrayOf(90f, 63f, 85f, 70f, 55f, 55f, 55f, 62f)
    private val operationWidths = floatArrayOf(83f, 55f, 68f, 65f, 43f, 43f, 62f, 116f)
    private val dateFormat = DateTimeFormatter.ofPattern("EEE yyyy/MM/dd", Locale.forLanguageTag("ar"))

    init {
        check(kotlin.math.abs(customerWidths.sum() - PdfPageLayout.CONTENT_WIDTH) < 0.01f)
        check(kotlin.math.abs(archiveWidths.sum() - PdfPageLayout.CONTENT_WIDTH) < 0.01f)
        check(kotlin.math.abs(operationWidths.sum() - PdfPageLayout.CONTENT_WIDTH) < 0.01f)
    }

    private fun date(row: ReportRow, zone: ZoneId): String =
        Instant.ofEpochMilli(row.committedAtEpochMillis).atZone(zone).format(dateFormat)

    private fun customerValues(row: ReportRow, zone: ZoneId): List<String> = listOf(
        date(row, zone), row.packageNameAtEvent.orEmpty(),
        row.nominalValueMinor?.toString().orEmpty(),
        row.deliveredQuantity?.toString().orEmpty(),
        row.remainingQuantity?.toString().orEmpty(),
        row.soldQuantity?.toString().orEmpty(),
        row.amountDueMinor?.toString().orEmpty()
    )

    private fun archiveValues(row: ReportRow, zone: ZoneId): List<String> {
        val added = row.type == ReportEventType.DELIVERY
        val before = if (added) row.deliveredQuantity else {
            val remaining = row.remainingQuantity
            val sold = row.soldQuantity
            if (remaining != null && sold != null) Math.addExact(remaining, sold) else null
        }
        return listOf(
            date(row, zone), if (added) "إضافة" else "محاسبة",
            row.customerNameAtEvent, row.packageNameAtEvent.orEmpty(),
            before?.toString().orEmpty(),
            if (added) "" else row.remainingQuantity?.toString().orEmpty(),
            if (added) "" else row.soldQuantity?.toString().orEmpty(),
            if (added) "" else row.amountDueMinor?.toString().orEmpty()
        )
    }

    private fun measure(page: PdfPageLayout, cells: List<String>, widths: FloatArray, bold: Boolean): Float =
        cells.indices.maxOf { i ->
            page.measure(cells[i], widths[i] - 10f, 8.5f, bold)
        } + 12f

    private fun drawRow(page: PdfPageLayout, cells: List<String>, widths: FloatArray,
                        height: Float, header: Boolean) {
        val y = page.cursor
        page.canvas.drawRect(
            PdfPageLayout.MARGIN, y,
            PdfPageLayout.WIDTH - PdfPageLayout.MARGIN, y + height,
            Paint().apply { color = if (header) Color.rgb(232, 238, 241) else Color.WHITE }
        )
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 0.55f
        }
        var right = PdfPageLayout.WIDTH - PdfPageLayout.MARGIN
        cells.forEachIndexed { index, value ->
            val left = right - widths[index]
            page.canvas.drawRect(left, y, right, y + height, border)
            page.text(value, left + 5f, y + 6f, widths[index] - 10f, 8.5f, header)
            right = left
        }
        page.advance(height)
    }

    fun draw(document: ReportDocument, page: PdfPageLayout) {
        val isArchive = document.request.scope == ReportScope.ARCHIVE
        val widths = if (isArchive) archiveWidths else customerWidths
        val headings = if (isArchive) {
            listOf("التاريخ واليوم", "العملية", "العميل", "الفئة", "عدد الكروت", "المتبقي", "المباع", "المستحق")
        } else {
            require(document.columns.map { it.id } ==
                listOf("date", "package", "nominal", "delivered", "remaining", "sold", "due")) {
                "ترتيب الأعمدة السبعة غير مطابق"
            }
            document.columns.map { it.title }
        }
        if (document.rows.isEmpty()) {
            page.paragraph("لا توجد عمليات ضمن الفترة المحددة")
        }
        val total = document.totals
        // The remaining column is an END BALANCE, not the sum of repeated snapshots.
        val totalValues = if (isArchive) {
            listOf("المجموع", "", "", "", total.deliveredCards.toString(),
                total.closingCards.toString(), total.soldCards.toString(),
                total.salesDueMinor.toString())
        } else {
            listOf("المجموع", "", "", total.deliveredCards.toString(),
                total.closingCards.toString(), total.soldCards.toString(),
                total.salesDueMinor.toString())
        }
        val displayedRows = document.rows.map { row ->
            if (isArchive) archiveValues(row, document.request.zoneId)
            else customerValues(row, document.request.zoneId)
        } + listOf(totalValues)
        val headingHeight = measure(page, headings, widths, true)
        var lastHeaderPage = 0
        displayedRows.forEachIndexed { index, values ->
            val isTotal = index == displayedRows.lastIndex
            val rowHeight = measure(page, values, widths, isTotal)
            if (page.pageNumber == 0) page.openPage()
            val extra = if (lastHeaderPage != page.pageNumber) headingHeight else 0f
            page.ensureSpace(rowHeight + extra)
            if (lastHeaderPage != page.pageNumber) {
                page.ensureSpace(headingHeight + rowHeight)
                drawRow(page, headings, widths, headingHeight, true)
                lastHeaderPage = page.pageNumber
            }
            drawRow(page, values, widths, rowHeight, isTotal)
        }
    }

    /** Operation history: table rows match the filtered screen, not manual archive membership. */
    fun drawOperations(rows: List<OperationLogPdfRow>, page: PdfPageLayout) {
        val headers = listOf("التاريخ واليوم", "العملية", "العميل", "الفئة",
            "المعطى", "المباع", "المستحق", "التفاصيل")
        val widths = operationWidths
        val timeFormat = DateTimeFormatter.ofPattern("EEE yyyy/MM/dd HH:mm", Locale.forLanguageTag("ar"))
        val zone = ZoneId.systemDefault()
        val headingHeight = measure(page, headers, widths, true)
        var lastHeaderPage = 0

        fun renderCells(cells: List<String>, total: Boolean = false) {
            // Split unusually long customer names and audit descriptions into complete
            // continuation rows; never clip history or draw beyond the page footer.
            val limits = listOf(36, 32, 48, 48, 24, 24, 30, 100)
            val chunks = cells.mapIndexed { index, value ->
                value.chunked(limits[index]).ifEmpty { listOf("") }
            }
            for (part in 0 until chunks.maxOf { it.size }) {
                val row = chunks.map { it.getOrElse(part) { "" } }
                val height = measure(page, row, widths, total)
                if (page.pageNumber == 0) page.openPage()
                val requiredHeader = if (lastHeaderPage != page.pageNumber) headingHeight else 0f
                page.ensureSpace(height + requiredHeader)
                if (lastHeaderPage != page.pageNumber) {
                    page.ensureSpace(headingHeight + height)
                    drawRow(page, headers, widths, headingHeight, true)
                    lastHeaderPage = page.pageNumber
                }
                drawRow(page, row, widths, height, total)
            }
        }

        var delivered = 0L
        var sold = 0L
        var due = 0L
        rows.forEach { entry ->
            val isDelivery = entry.operationType == "DELIVERY"
            val isSettlement = entry.operationType == "SETTLEMENT"
            if (isDelivery) delivered = Math.addExact(delivered, entry.cards ?: 0L)
            if (isSettlement) {
                sold = Math.addExact(sold, entry.cards ?: 0L)
                due = Math.addExact(due, entry.amountDueMinor ?: 0L)
            }
            renderCells(listOf(
                Instant.ofEpochMilli(entry.atMillis).atZone(zone).format(timeFormat),
                entry.operationLabel,
                entry.customerName,
                entry.packageName.orEmpty(),
                if (isDelivery) entry.cards?.toString().orEmpty() else "",
                if (isSettlement) entry.cards?.toString().orEmpty() else "",
                if (isSettlement) entry.amountDueMinor?.toString().orEmpty() else "",
                OperationDescriptionArabic.display(entry.description)
            ))
        }
        if (rows.isEmpty()) page.paragraph("لا توجد عمليات ضمن الفترة المحددة", 9f)
        renderCells(listOf("المجموع", "", "", "", delivered.toString(),
            sold.toString(), due.toString(), ""), total = true)
    }
}
