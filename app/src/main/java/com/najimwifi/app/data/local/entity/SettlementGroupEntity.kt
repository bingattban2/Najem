package com.najimwifi.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.najimwifi.app.data.local.LedgerConverters
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.SettlementMode

@Entity(
    tableName = "settlement_groups",
    foreignKeys = [ForeignKey(
        entity = CustomerEntity::class, parentColumns = ["id"],
        childColumns = ["customerId"], onDelete = ForeignKey.RESTRICT
    )],
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["customerId", "committedAtEpochMillis"])
    ]
)
@TypeConverters(LedgerConverters::class)
data class SettlementGroupEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val customerId: String,
    val mode: SettlementMode,
    val committedAtEpochMillis: Long,
    @Embedded(prefix = "customer_") val customerSnapshot: CustomerSnapshot,
    val previousDebtMinor: Long,
    val totalAmountDueMinor: Long,
    val singlePaymentMinor: Long,
    val endingDebtMinor: Long,
    val paymentEntryId: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && customerId.isNotBlank())
        require(committedAtEpochMillis >= 0L)
        require(previousDebtMinor >= 0L && totalAmountDueMinor >= 0L)
        require(singlePaymentMinor >= 0L && endingDebtMinor >= 0L)
        val payable = Math.addExact(previousDebtMinor, totalAmountDueMinor)
        require(singlePaymentMinor <= payable)
        require(endingDebtMinor == Math.subtractExact(payable, singlePaymentMinor))
        require((singlePaymentMinor > 0L) == (paymentEntryId != null))
        require(paymentEntryId == null || paymentEntryId.isNotBlank())
    }
}