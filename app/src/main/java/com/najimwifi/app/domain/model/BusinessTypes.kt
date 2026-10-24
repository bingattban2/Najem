package com.najimwifi.app.domain.model

enum class SettlementMode { ONE, ALL }

enum class CardMovementKind {
    DELIVERY,
    SETTLEMENT_SALE,
    UNSOLD_RETURN,
    CORRECTION
}

enum class ConsignmentEffect { INCREASE, DECREASE }

enum class FinancialEntryKind {
    SETTLEMENT_RECEIVABLE,
    COLLECTION,
    RECEIVABLE_REVERSAL,
    COLLECTION_REVERSAL
}

enum class PaymentMethod { CASH, TRANSFER, WALLET, UNSPECIFIED }

/** Historical text captured at posting time, not a live customer lookup. */
data class CustomerSnapshot(
    val name: String,
    val location: String
) {
    init {
        require(name.isNotBlank()) { "Customer name is required" }
        require(location.isNotBlank()) { "Customer location is required" }
    }
}

/** Historical package identity and global prices captured at posting time. */
data class PackageSnapshot(
    val name: String,
    val nominalValueMinor: Long,
    val generalSettlementPriceMinor: Long,
    val colorArgb: Int
) {
    init {
        require(name.isNotBlank()) { "Package name is required" }
        require(nominalValueMinor > 0L) { "Nominal value must be positive" }
        require(generalSettlementPriceMinor > 0L) {
            "General settlement price must be positive"
        }
    }
}