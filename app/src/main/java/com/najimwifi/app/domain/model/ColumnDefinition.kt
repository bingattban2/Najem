package com.najimwifi.app.domain.model

/** Fixed field identifiers; editing a label must never rename a database column. */
enum class ColumnTarget { PACKAGE_JOURNAL, ARCHIVE }

enum class ColumnValueType { TEXT, WHOLE_NUMBER, MONEY_MINOR }

enum class RequiredColumnKey {
    DATE,
    PACKAGE_NAME,
    NOMINAL_VALUE,
    WITH_CUSTOMER,
    REMAINING,
    DELIVERED,
    SOLD,
    AMOUNT_DUE,
    OPERATION,
    CUSTOMER_NAME,
    EVENT_QUANTITY,
    PAYMENT_AMOUNT
}

data class ColumnDefinition(
    val id: String,
    val target: ColumnTarget,
    val title: String,
    val displayOrder: Int,
    val valueType: ColumnValueType,
    val requiredKey: RequiredColumnKey? = null,
    val isActive: Boolean = true
) {
    val isRequired: Boolean get() = requiredKey != null

    init {
        require(id.isNotBlank()) { "Column ID is required" }
        require(title.isNotBlank()) { "Column title is required" }
        require(displayOrder >= 0) { "Display order must be nonnegative" }
        require(!isRequired || isActive) { "A required column cannot be hidden" }
    }
}

/** Optional user-entered cells live separately from immutable accounting events. */
sealed interface OptionalCellValue {
    data class TextValue(val value: String) : OptionalCellValue

    data class WholeNumberValue(val value: Long) : OptionalCellValue

    data class MoneyValue(val minorUnits: Long) : OptionalCellValue {
        init {
            require(minorUnits >= 0L) { "Money cell cannot be negative" }
        }
    }
}

data class OptionalColumnCell(
    val eventId: String,
    val columnId: String,
    val value: OptionalCellValue
) {
    init {
        require(eventId.isNotBlank() && columnId.isNotBlank()) {
            "A custom cell needs an event and a column ID"
        }
    }
}