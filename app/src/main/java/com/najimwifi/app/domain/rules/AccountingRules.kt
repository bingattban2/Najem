package com.najimwifi.app.domain.rules

import com.najimwifi.app.domain.model.PackageSnapshot
import com.najimwifi.app.domain.model.SettlementMode
import com.najimwifi.app.domain.value.Money

/** Read-only inputs captured when a settlement form is opened. */
data class PackageBalance(
    val packageId: String,
    val snapshot: PackageSnapshot,
    val onHand: Long,
    val active: Boolean
) {
    init {
        require(packageId.isNotBlank()) { "Package ID is required" }
        require(onHand >= 0L) { "Shop balance cannot be negative" }
    }
}

data class CalculatedSettlementLine(
    val packageId: String,
    val snapshot: PackageSnapshot,
    val before: Long,
    val remaining: Long,
    val sold: Long,
    val amountDue: Money
)

data class CalculatedSettlement(
    val mode: SettlementMode,
    val lines: List<CalculatedSettlementLine>,
    val previousDebt: Money,
    val newAmountDue: Money,
    val singlePayment: Money,
    val endingDebt: Money
)

/** Quantity transitions do not post sales, receivables or payments. */
data class QuantityTransition(
    val shopAfter: Long,
    val centralAfter: Long?
)

/** Pure calculations only. The repository must revalidate and post atomically. */
object AccountingRules {

    fun calculateSettlement(
        mode: SettlementMode,
        balances: List<PackageBalance>,
        selectedPackageId: String?,
        remainingByPackageId: Map<String, Long?>,
        previousDebtMinor: Long,
        paymentMinor: Long
    ): CalculatedSettlement {
        require(balances.map { it.packageId }.toSet().size == balances.size) {
            "Duplicate package IDs in settlement balances"
        }

        val eligible = when (mode) {
            SettlementMode.ONE -> {
                require(!selectedPackageId.isNullOrBlank()) {
                    "Choose a package"
                }

                listOf(
                    balances.singleOrNull {
                        it.packageId == selectedPackageId &&
                            it.onHand > 0L
                    } ?: throw IllegalArgumentException(
                        "Package has no unsettled cards"
                    )
                )
            }

            SettlementMode.ALL -> {
                require(selectedPackageId == null) {
                    "All-packages mode must not select an individual package"
                }
                balances.filter { it.onHand > 0L }
            }
        }

        require(eligible.isNotEmpty()) {
            "No cards to settle; use independent debt payment"
        }

        require(
            remainingByPackageId.keys ==
                eligible.map { it.packageId }.toSet()
        ) {
            "Each included package needs exactly one remaining-count field"
        }

        val calculatedLines = eligible.map { balance ->
            val remaining = remainingByPackageId[balance.packageId]
                ?: throw IllegalArgumentException(
                    "Remaining count is required"
                )

            require(
                remaining >= 0L &&
                    remaining <= balance.onHand
            ) {
                "Remaining count is outside the available balance"
            }

            val sold = Math.subtractExact(
                balance.onHand,
                remaining
            )

            val price = Money.ofMinorUnits(
                balance.snapshot.generalSettlementPriceMinor
            )

            CalculatedSettlementLine(
                packageId = balance.packageId,
                snapshot = balance.snapshot,
                before = balance.onHand,
                remaining = remaining,
                sold = sold,
                amountDue = price.times(sold)
            )
        }

        val previousDebt = Money.ofMinorUnits(previousDebtMinor)

        val newDue = Money.sum(
            calculatedLines.map { it.amountDue }
        )

        val payment = Money.ofMinorUnits(paymentMinor)

        val totalPayable = previousDebt + newDue

        require(payment <= totalPayable) {
            "Payment exceeds amount payable"
        }

        return CalculatedSettlement(
            mode = mode,
            lines = calculatedLines,
            previousDebt = previousDebt,
            newAmountDue = newDue,
            singlePayment = payment,
            endingDebt = totalPayable - payment
        )
    }

    fun payExistingDebt(
        previousDebtMinor: Long,
        paymentMinor: Long
    ): Money {
        val debt = Money.ofMinorUnits(previousDebtMinor)
        val payment = Money.ofMinorUnits(paymentMinor)

        require(payment <= debt) {
            "Payment exceeds existing debt"
        }

        return debt - payment
    }

    fun deliver(
        shopOnHand: Long,
        quantity: Long,
        packageIsActive: Boolean,
        centralOnHand: Long? = null
    ): QuantityTransition {
        require(packageIsActive) {
            "Inactive packages cannot receive new deliveries"
        }

        require(shopOnHand >= 0L && quantity > 0L) {
            "Invalid delivery quantity"
        }

        require(
            centralOnHand == null ||
                centralOnHand >= quantity
        ) {
            "Insufficient central stock"
        }

        return QuantityTransition(
            shopAfter = Math.addExact(
                shopOnHand,
                quantity
            ),
            centralAfter = centralOnHand?.let {
                Math.subtractExact(it, quantity)
            }
        )
    }

    fun returnUnsold(
        shopOnHand: Long,
        quantity: Long,
        centralOnHand: Long? = null,
        returnToCentral: Boolean = false
    ): QuantityTransition {
        require(
            shopOnHand >= 0L &&
                quantity > 0L &&
                quantity <= shopOnHand
        ) {
            "Return quantity exceeds available cards"
        }

        require(!returnToCentral || centralOnHand != null) {
            "Returning to central stock requires enabled tracking"
        }

        require(
            centralOnHand == null ||
                centralOnHand >= 0L
        )

        return QuantityTransition(
            shopAfter = Math.subtractExact(
                shopOnHand,
                quantity
            ),
            centralAfter = if (returnToCentral) {
                Math.addExact(
                    requireNotNull(centralOnHand),
                    quantity
                )
            } else {
                centralOnHand
            }
        )
    }

    /** Reconcile a balance with signed ledger deltas, rejecting negative stock. */
    fun balanceFromMovements(
        opening: Long,
        deltas: Iterable<Long>
    ): Long {
        require(opening >= 0L)

        return deltas.fold(opening) { balance, delta ->
            Math.addExact(balance, delta).also {
                require(it >= 0L) {
                    "Ledger balance cannot be negative"
                }
            }
        }
    }
}