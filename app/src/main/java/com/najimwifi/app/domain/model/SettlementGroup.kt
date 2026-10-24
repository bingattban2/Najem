package com.najimwifi.app.domain.model

/** One operation with a single customer debt and at most one payment. */
data class SettlementGroup(
    val id: String,
    val operationId: String,
    val customerId: String,
    val mode: SettlementMode,
    val committedAtEpochMillis: Long,
    val customerSnapshot: CustomerSnapshot,
    val previousDebtMinor: Long,
    val totalAmountDueMinor: Long,
    val singlePaymentMinor: Long,
    val endingDebtMinor: Long,
    val paymentEntryId: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && customerId.isNotBlank()) {
            "Settlement identifiers are required"
        }
        require(committedAtEpochMillis >= 0L) { "Invalid commit timestamp" }
        require(previousDebtMinor >= 0L && totalAmountDueMinor >= 0L) {
            "Debt and sales amounts must be nonnegative"
        }
        require(singlePaymentMinor >= 0L && endingDebtMinor >= 0L) {
            "Payment and final debt must be nonnegative"
        }
        val payable = Math.addExact(previousDebtMinor, totalAmountDueMinor)
        require(singlePaymentMinor <= payable) { "Payment exceeds amount payable" }
        require(endingDebtMinor == Math.subtractExact(payable, singlePaymentMinor)) {
            "Closing debt does not reconcile"
        }
        require((singlePaymentMinor > 0L) == (paymentEntryId != null)) {
            "A positive payment requires exactly one payment entry ID"
        }
        require(paymentEntryId == null || paymentEntryId.isNotBlank()) {
            "Payment entry ID must not be blank"
        }
    }
}