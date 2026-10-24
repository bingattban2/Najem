package com.najimwifi.app.domain.model

enum class CentralStockKind {
    OPENING,
    SUPPLY,
    DELIVERY_TO_CUSTOMER,
    RETURN_FROM_CUSTOMER,
    CORRECTION
}

enum class CentralStockEffect { INCREASE, DECREASE }

/** Tracking is opt-in by package, never a replacement for shop consignment. */
data class CentralStockPolicy(
    val packageId: String,
    val enabled: Boolean
) {
    init {
        require(packageId.isNotBlank()) { "Package ID is required" }
    }
}

/** Balances are verifiable snapshots; signed movements remain authoritative. */
data class CentralStockMovement(
    val id: String,
    val operationId: String,
    val packageId: String,
    val kind: CentralStockKind,
    val effect: CentralStockEffect,
    val quantity: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val committedAtEpochMillis: Long,
    val packageSnapshot: PackageSnapshot,
    val customerId: String? = null,
    val reversesMovementId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && packageId.isNotBlank()) {
            "Central stock movement identifiers are required"
        }
        require(committedAtEpochMillis >= 0L) { "Invalid stock movement timestamp" }
        require(quantity >= 0L && balanceBefore >= 0L && balanceAfter >= 0L) {
            "Central stock balances and quantities cannot be negative"
        }
        require(kind == CentralStockKind.OPENING || quantity > 0L) {
            "Only an opening balance can have zero quantity"
        }

        val expected = when (effect) {
            CentralStockEffect.INCREASE -> Math.addExact(balanceBefore, quantity)
            CentralStockEffect.DECREASE -> Math.subtractExact(balanceBefore, quantity)
        }

        require(balanceAfter == expected) {
            "Central stock movement does not reconcile"
        }

        when (kind) {
            CentralStockKind.OPENING -> {
                require(effect == CentralStockEffect.INCREASE && balanceBefore == 0L)
                require(customerId == null && reversesMovementId == null)
            }

            CentralStockKind.SUPPLY -> {
                require(effect == CentralStockEffect.INCREASE)
                require(customerId == null && reversesMovementId == null)
            }

            CentralStockKind.DELIVERY_TO_CUSTOMER -> {
                require(effect == CentralStockEffect.DECREASE)
                require(!customerId.isNullOrBlank() && reversesMovementId == null)
            }

            CentralStockKind.RETURN_FROM_CUSTOMER -> {
                require(effect == CentralStockEffect.INCREASE)
                require(!customerId.isNullOrBlank() && reversesMovementId == null)
            }

            CentralStockKind.CORRECTION -> {
                require(!reversesMovementId.isNullOrBlank())
                require(!correctionReason.isNullOrBlank())
                require(!authorizedBy.isNullOrBlank())
            }
        }

        if (kind != CentralStockKind.CORRECTION) {
            require(correctionReason == null && authorizedBy == null)
        }
    }
}