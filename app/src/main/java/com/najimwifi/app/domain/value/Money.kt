package com.najimwifi.app.domain.value

/** Exact nonnegative amount expressed in the application's accounting minor unit. */
@JvmInline
value class Money private constructor(
    val minorUnits: Long
) : Comparable<Money> {

    init {
        require(minorUnits >= 0L) { "Money cannot be negative" }
    }

    operator fun plus(other: Money): Money = ofMinorUnits(
        Math.addExact(minorUnits, other.minorUnits)
    )

    operator fun minus(other: Money): Money {
        require(minorUnits >= other.minorUnits) { "Insufficient amount" }
        return ofMinorUnits(
            Math.subtractExact(minorUnits, other.minorUnits)
        )
    }

    fun times(quantity: Long): Money {
        require(quantity >= 0L) { "Quantity cannot be negative" }
        return ofMinorUnits(
            Math.multiplyExact(minorUnits, quantity)
        )
    }

    override fun compareTo(other: Money): Int =
        minorUnits.compareTo(other.minorUnits)

    companion object {
        val ZERO: Money = Money(0L)

        fun ofMinorUnits(value: Long): Money = Money(value)

        fun sum(values: Iterable<Money>): Money =
            values.fold(ZERO) { total, value ->
                total + value
            }
    }
}