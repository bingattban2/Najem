package com.najimwifi.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.najimwifi.app.data.local.LedgerConverters
import com.najimwifi.app.domain.model.CardMovementKind
import com.najimwifi.app.domain.model.ConsignmentEffect
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.PackageSnapshot

@Entity(
    tableName = "card_movements",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class, parentColumns = ["id"],
            childColumns = ["customerId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageEntity::class, parentColumns = ["id"],
            childColumns = ["packageId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = SettlementLineEntity::class, parentColumns = ["id"],
            childColumns = ["settlementLineId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CardMovementEntity::class, parentColumns = ["id"],
            childColumns = ["reversesMovementId"], onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["customerId", "packageId", "committedAtEpochMillis"]),
        Index(value = ["packageId"]),
        Index(value = ["operationId", "packageId", "kind"], unique = true),
        Index(value = ["referenceNumber"], unique = true),
        Index(value = ["settlementLineId"], unique = true),
        Index(value = ["reversesMovementId"], unique = true)
    ]
)
@TypeConverters(LedgerConverters::class)
data class CardMovementEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val referenceNumber: String,
    val customerId: String,
    val packageId: String,
    val kind: CardMovementKind,
    val effect: ConsignmentEffect,
    val quantity: Long,
    val committedAtEpochMillis: Long,
    @Embedded(prefix = "customer_") val customerSnapshot: CustomerSnapshot,
    @Embedded(prefix = "package_") val packageSnapshot: PackageSnapshot,
    val settlementLineId: String? = null,
    val reversesMovementId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank() && referenceNumber.isNotBlank())
        require(customerId.isNotBlank() && packageId.isNotBlank() && quantity > 0L)
        require(committedAtEpochMillis >= 0L)
        when (kind) {
            CardMovementKind.DELIVERY -> require(
                effect == ConsignmentEffect.INCREASE &&
                    settlementLineId == null && reversesMovementId == null
            )
            CardMovementKind.SETTLEMENT_SALE -> require(
                effect == ConsignmentEffect.DECREASE &&
                    !settlementLineId.isNullOrBlank() && reversesMovementId == null
            )
            CardMovementKind.UNSOLD_RETURN -> require(
                effect == ConsignmentEffect.DECREASE &&
                    settlementLineId == null && reversesMovementId == null
            )
            CardMovementKind.CORRECTION -> require(
                !reversesMovementId.isNullOrBlank() &&
                    !correctionReason.isNullOrBlank() && !authorizedBy.isNullOrBlank() &&
                    settlementLineId == null
            )
        }
        if (kind != CardMovementKind.CORRECTION) {
            require(correctionReason == null && authorizedBy == null)
        }
    }
}