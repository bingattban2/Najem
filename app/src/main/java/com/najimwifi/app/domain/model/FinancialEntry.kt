package com.najimwifi.app.domain.model

/** Authoritative customer-debt ledger entry; quantities stay in CardMovement. */
data class FinancialEntry(
    val id: String,
    val operationId: String,
    val customerId: String,
    val kind: FinancialEntryKind,
    val amountMinor: Long,
    val committedAtEpochMillis: Long,
    val customerSnapshot: CustomerSnapshot,
    val settlementGroupId: String? = null,
    val receiptNumber: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val note: String? = null,
    val reversesEntryId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && customerId.isNotBlank()) {
            "Financial posting identifiers are required"
        }
        require(amountMinor > 0L) { "Posted financial entries must be positive" }
        require(committedAtEpochMillis >= 0L) { "Invalid commit timestamp" }
        require(settlementGroupId == null || settlementGroupId.isNotBlank())
        when (kind) {
            FinancialEntryKind.SETTLEMENT_RECEIVABLE -> {
                require(!settlementGroupId.isNullOrBlank())
                require(receiptNumber == null && paymentMethod == null)
                require(reversesEntryId == null)
            }
            FinancialEntryKind.COLLECTION -> {
                require(!receiptNumber.isNullOrBlank()) { "Collection receipt is required" }
                require(reversesEntryId == null)
            }
            FinancialEntryKind.RECEIVABLE_REVERSAL,
            FinancialEntryKind.COLLECTION_REVERSAL -> {
                require(!reversesEntryId.isNullOrBlank())
                require(!correctionReason.isNullOrBlank())
                require(!authorizedBy.isNullOrBlank())
                require(receiptNumber == null && paymentMethod == null)
            }
        }
        if (kind != FinancialEntryKind.RECEIVABLE_REVERSAL &&
            kind != FinancialEntryKind.COLLECTION_REVERSAL
        ) {
            require(correctionReason == null && authorizedBy == null)
        }
    }

    val debtDeltaMinor: Long
        get() = when (kind) {
            FinancialEntryKind.SETTLEMENT_RECEIVABLE,
            FinancialEntryKind.COLLECTION_REVERSAL -> amountMinor
            FinancialEntryKind.COLLECTION,
            FinancialEntryKind.RECEIVABLE_REVERSAL -> -amountMinor
        }
}