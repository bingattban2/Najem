package com.najimwifi.app.data.report

/** Human-readable view of audit descriptions. Persisted descriptions and IDs are untouched. */
object OperationDescriptionArabic {
    private val references = listOf(
        "Settlement " to "محاسبة",
        "Delivery " to "إضافة كروت",
        "Collection " to "تحصيل",
        "Unsold return " to "إرجاع كروت غير مباعة",
        "Quantity reversal " to "عكس حركة كروت"
    )

    fun display(raw: String): String {
        val value = raw.trim()
        for ((prefix, label) in references) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                val reference = value.substring(prefix.length).trim()
                // The immutable full reference remains in the database; an abbreviated reference
                // keeps PDF cells readable without exposing long machine UUID strings.
                return if (reference.isEmpty()) label
                else "$label — مرجع العملية: ${reference.take(8)}"
            }
        }
        return when (value) {
            "Zero payment confirmed" -> "تم تأكيد المحاسبة دون دفعة"
            else -> value
        }
    }
}
