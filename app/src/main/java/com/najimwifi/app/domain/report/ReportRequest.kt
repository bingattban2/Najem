package com.najimwifi.app.domain.report

import java.time.LocalDate
import java.time.ZoneId

enum class ReportScope { CUSTOMER, DEBTS, ARCHIVE }
enum class ReportEventType { DELIVERY, SETTLEMENT, RETURN, CORRECTION, PAYMENT }

data class ReportRequest(
    val scope: ReportScope,
    val customerId: String? = null,
    val packageId: String? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val customerSearch: String = "",
    val packageSearch: String = "",
    val newestFirst: Boolean = false,
    val eventTypes: Set<ReportEventType> = ReportEventType.entries.toSet(),
    val generatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    init {
        require(scope != ReportScope.CUSTOMER || !customerId.isNullOrBlank()) {
            "اختر العميل المطلوب"
        }
        require(fromDate == null || toDate == null || !fromDate.isAfter(toDate)) {
            "تاريخ البداية يتجاوز النهاية"
        }
        require(customerId == null || customerId.isNotBlank())
        require(packageId == null || packageId.isNotBlank())
    }

    // User-entered dates are inclusive; query intervals are half-open.
    val startInclusiveMillis: Long?
        get() = fromDate?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()

    val endExclusiveMillis: Long?
        get() = toDate?.plusDays(1)?.atStartOfDay(zoneId)
            ?.toInstant()?.toEpochMilli()

    fun includesInstant(epochMillis: Long): Boolean =
        (startInclusiveMillis == null || epochMillis >= startInclusiveMillis!!) &&
            (endExclusiveMillis == null || epochMillis < endExclusiveMillis!!)

    fun isBeforePeriod(epochMillis: Long): Boolean =
        startInclusiveMillis?.let { epochMillis < it } ?: false

    fun isAtOrBeforeEnd(epochMillis: Long): Boolean =
        endExclusiveMillis?.let { epochMillis < it } ?: true
}