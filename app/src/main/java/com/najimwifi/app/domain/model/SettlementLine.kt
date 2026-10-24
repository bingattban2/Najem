package com.najimwifi.app.domain.model

/** A physical count for one package; never a second financial receivable. */
data class SettlementLine(
    val id: String,
    val settlementGroupId: String,
    val packageId: String,
    val stockBefore: Long,
    val stockRemaining: Long,
    val soldQuantity: Long,
    val amountDueMinor: Long,
    val customerSnapshot: CustomerSnapshot,
    val packageSnapshot: PackageSnapshot
) {
    init {
        require(id.isNotBlank() && settlementGroupId.isNotBlank() && packageId.isNotBlank()) {
            "Settlement line identifiers are required"
        }
        require(stockBefore > 0L) { "A settlement line needs prior consignment" }
        require(stockRemaining >= 0L && stockRemaining <= stockBefore) {
            "Remaining quantity is outside the available balance"
        }
        require(soldQuantity == stockBefore - stockRemaining) {
            "Sold quantity does not match the physical count"
        }
        require(amountDueMinor >= 0L) { "Amount due must be nonnegative" }
        val expectedDue = Math.multiplyExact(
            soldQuantity,
            packageSnapshot.generalSettlementPriceMinor
        )
        require(amountDueMinor == expectedDue) {
            "Amount due must use the snapshotted global settlement price"
        }
    }
}