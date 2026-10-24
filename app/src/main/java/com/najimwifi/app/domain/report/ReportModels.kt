package com.najimwifi.app.domain.report

/** Seven approved main columns, in their original fixed order. */
data class ReportColumn(val id: String, val title: String)

object ReportColumns {
    val main: List<ReportColumn> = listOf(
        ReportColumn("date", "اليوم والتاريخ"),
        ReportColumn("package", "اسم الباقة"),
        ReportColumn("nominal", "القيمة الاسمية"),
        ReportColumn("delivered", "المسلّم"),
        ReportColumn("remaining", "المتبقي المؤكد"),
        ReportColumn("sold", "المباع"),
        ReportColumn("due", "المبلغ المستحق")
    )
}

data class ReportRow(
    val id: String,
    val groupId: String? = null,
    val customerId: String,
    val customerNameAtEvent: String,
    val customerLocationAtEvent: String,
    val packageId: String? = null,
    val packageNameAtEvent: String? = null,
    val committedAtEpochMillis: Long,
    val type: ReportEventType,
    val nominalValueMinor: Long? = null,
    val globalPriceMinor: Long? = null,
    val deliveredQuantity: Long? = null,
    val returnedQuantity: Long? = null,
    val remainingQuantity: Long? = null,
    val soldQuantity: Long? = null,
    val amountDueMinor: Long? = null,
    val adjustmentMinor: Long? = null,
    val paymentMinor: Long? = null,
    val paymentMethod: String? = null,
    val receiptNumber: String? = null,
    val referenceNumber: String? = null,
    /** Non-null when this original posting was reversed by the report end date. */
    val reversedAtEpochMillis: Long? = null,
    /** Historical correction audit metadata; never overwrites the source row. */
    val reversesRecordId: String? = null,
    val correctionKind: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null,
    /** Signed customer consignment delta of an audited correction. */
    val consignmentDeltaQuantity: Long? = null
)

data class ReportCustomerTotals(
    val customerId: String,
    val customerLabel: String,
    val openingDebtMinor: Long,
    val newSalesDueMinor: Long,
    val collectionsMinor: Long,
    val adjustmentsMinor: Long,
    val closingDebtMinor: Long,
    val deliveredCards: Long,
    val soldCards: Long,
    val returnedCards: Long,
    val closingCards: Long,
    val reversedCollectionsMinor: Long = 0L,
    val reversedReceivablesMinor: Long = 0L
)

data class ReportTotals(
    val deliveredCards: Long,
    val soldCards: Long,
    val returnedCards: Long,
    val salesDueMinor: Long,
    val receiptsMinor: Long,
    val adjustmentsMinor: Long,
    val closingCards: Long,
    val openingDebtMinor: Long,
    val closingDebtMinor: Long,
    /** Customer-wide reversals, not attributed to any package. */
    val reversedCollectionsMinor: Long = 0L,
    val reversedReceivablesMinor: Long = 0L
)

data class ReportDocument(
    val request: ReportRequest,
    val title: String,
    val generatedAtEpochMillis: Long,
    val columns: List<ReportColumn> = ReportColumns.main,
    val rows: List<ReportRow>,
    val customers: List<ReportCustomerTotals>,
    val totals: ReportTotals,
    val notes: List<String> = emptyList()
)