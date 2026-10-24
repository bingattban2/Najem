package com.najimwifi.app.domain.service

/** Optional, read-only policy; amounts are the same integer units as financial_entries. */
data class DebtAlertOptions(
    val debtEnabled: Boolean = false,
    val minimumDebtMinor: Long = 10_000L,
    val settlementDelayEnabled: Boolean = false,
    val overdueAfterDays: Int = 7,
    val systemNotificationsEnabled: Boolean = false
) {
    init {
        require(minimumDebtMinor > 0L) { "حد الدين يجب أن يكون موجبًا" }
        require(overdueAfterDays in 1..365) { "مدة التأخير يجب أن تكون من يوم إلى 365 يومًا" }
    }
    val anyAlertEnabled: Boolean get() = debtEnabled || settlementDelayEnabled
}

data class CustomerAlertSnapshot(
    val customerId: String,
    val customerName: String,
    val debtMinor: Long,
    val consignmentCards: Long,
    val firstDeliveryAtEpochMillis: Long?,
    val lastSettlementAtEpochMillis: Long?
)

enum class LocalAlertKind { DEBT_THRESHOLD, SETTLEMENT_DELAY }

data class LocalAlert(
    val customerId: String,
    val customerName: String,
    val kind: LocalAlertKind,
    val debtMinor: Long? = null,
    val consignmentCards: Long? = null,
    val elapsedDays: Long? = null
)

/**
 * Detects conditions, never writes ledger entries or changes inventory.
 * A delayed settlement means positive consignment still at a customer,
 * with no completed settlement for N elapsed days; if no settlement exists,
 * the oldest delivery is the initial reference. This is an indicator,
 * not a claim that a particular delivery has been sold.
 */
object DebtAlertPolicy {
    private const val DAY_MILLIS = 86_400_000L

    fun evaluate(
        customers: List<CustomerAlertSnapshot>,
        options: DebtAlertOptions,
        nowEpochMillis: Long
    ): List<LocalAlert> {
        require(nowEpochMillis >= 0L)
        if (!options.anyAlertEnabled) return emptyList()
        val result = ArrayList<LocalAlert>()
        customers.forEach { customer ->
            if (options.debtEnabled && customer.debtMinor >= options.minimumDebtMinor) {
                result += LocalAlert(
                    customerId = customer.customerId,
                    customerName = customer.customerName,
                    kind = LocalAlertKind.DEBT_THRESHOLD,
                    debtMinor = customer.debtMinor
                )
            }
            if (options.settlementDelayEnabled && customer.consignmentCards > 0L) {
                val anchor = customer.lastSettlementAtEpochMillis
                    ?: customer.firstDeliveryAtEpochMillis
                if (anchor != null && anchor >= 0L && anchor <= nowEpochMillis) {
                    val days = (nowEpochMillis - anchor) / DAY_MILLIS
                    if (days >= options.overdueAfterDays) {
                        result += LocalAlert(
                            customerId = customer.customerId,
                            customerName = customer.customerName,
                            kind = LocalAlertKind.SETTLEMENT_DELAY,
                            consignmentCards = customer.consignmentCards,
                            elapsedDays = days
                        )
                    }
                }
            }
        }
        return result.sortedWith(compareBy<LocalAlert> { it.customerName }
            .thenBy { it.kind.name }.thenBy { it.customerId })
    }
}
