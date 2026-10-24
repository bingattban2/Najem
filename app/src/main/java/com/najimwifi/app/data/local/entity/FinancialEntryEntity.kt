package com.najimwifi.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.najimwifi.app.data.local.LedgerConverters
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.FinancialEntryKind
import com.najimwifi.app.domain.model.PaymentMethod

@Entity(
    tableName = "financial_entries",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class, parentColumns = ["id"],
            childColumns = ["customerId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = SettlementGroupEntity::class, parentColumns = ["id"],
            childColumns = ["settlementGroupId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = FinancialEntryEntity::class, parentColumns = ["id"],
            childColumns = ["reversesEntryId"], onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["customerId", "committedAtEpochMillis"]),
        Index(value = ["operationId", "kind"], unique = true),
        Index(value = ["settlementGroupId", "kind"], unique = true),
        Index(value = ["receiptNumber"], unique = true),
        Index(value = ["reversesEntryId"], unique = true)
    ]
)
@TypeConverters(LedgerConverters::class)
data class FinancialEntryEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val customerId: String,
    val kind: FinancialEntryKind,
    val amountMinor: Long,
    val committedAtEpochMillis: Long,
    @Embedded(prefix = "customer_") val customerSnapshot: CustomerSnapshot,
    val settlementGroupId: String? = null,
    val receiptNumber: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val note: String? = null,
    val reversesEntryId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && customerId.isNotBlank())
        require(amountMinor > 0L && committedAtEpochMillis >= 0L)
        require(settlementGroupId == null || settlementGroupId.isNotBlank())
        when (kind) {
            FinancialEntryKind.SETTLEMENT_RECEIVABLE -> require(
                !settlementGroupId.isNullOrBlank() &&
                    receiptNumber == null && paymentMethod == null && reversesEntryId == null
            )
            FinancialEntryKind.COLLECTION -> require(
                !receiptNumber.isNullOrBlank() && reversesEntryId == null
            )
            FinancialEntryKind.RECEIVABLE_REVERSAL,
            FinancialEntryKind.COLLECTION_REVERSAL -> require(
                !reversesEntryId.isNullOrBlank() &&
                    !correctionReason.isNullOrBlank() && !authorizedBy.isNullOrBlank() &&
                    receiptNumber == null && paymentMethod == null
            )
        }
        if (kind != FinancialEntryKind.RECEIVABLE_REVERSAL &&
            kind != FinancialEntryKind.COLLECTION_REVERSAL
        ) {
            require(correctionReason == null && authorizedBy == null)
        }
    }
}