package com.najimwifi.app.domain.model

/** An immutable consignment posting. Sale amounts belong to the financial ledger. */
data class CardMovement(
    val id: String,
    val operationId: String,
    val referenceNumber: String,
    val customerId: String,
    val packageId: String,
    val kind: CardMovementKind,
    val effect: ConsignmentEffect,
    val quantity: Long,
    val committedAtEpochMillis: Long,
    val customerSnapshot: CustomerSnapshot,
    val packageSnapshot: PackageSnapshot,
    val settlementLineId: String? = null,
    val reversesMovementId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank()) { "Posting IDs are required" }
        require(referenceNumber.isNotBlank()) { "Reference number is required" }
        require(customerId.isNotBlank() && packageId.isNotBlank()) {
            "Customer and package IDs are required"
        }
        require(quantity > 0L) { "Movement quantity must be positive" }
        require(committedAtEpochMillis >= 0L) { "Invalid commit timestamp" }

        when (kind) {
            CardMovementKind.DELIVERY -> {
                require(effect == ConsignmentEffect.INCREASE)
                require(settlementLineId == null && reversesMovementId == null)
            }
            CardMovementKind.SETTLEMENT_SALE -> {
                require(effect == ConsignmentEffect.DECREASE)
                require(!settlementLineId.isNullOrBlank())
                require(reversesMovementId == null)
            }
            CardMovementKind.UNSOLD_RETURN -> {
                require(effect == ConsignmentEffect.DECREASE)
                require(settlementLineId == null && reversesMovementId == null)
            }
            CardMovementKind.CORRECTION -> {
                require(!reversesMovementId.isNullOrBlank())
                require(!correctionReason.isNullOrBlank())
                require(!authorizedBy.isNullOrBlank())
                require(settlementLineId == null)
            }
        }
        if (kind != CardMovementKind.CORRECTION) {
            require(correctionReason == null && authorizedBy == null)
        }
    }

    val consignmentDelta: Long
        get() = if (effect == ConsignmentEffect.INCREASE) quantity else -quantity
}