package com.najimwifi.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.najimwifi.app.domain.model.CustomerSnapshot
import com.najimwifi.app.domain.model.PackageSnapshot

@Entity(
    tableName = "settlement_lines",
    foreignKeys = [
        ForeignKey(
            entity = SettlementGroupEntity::class, parentColumns = ["id"],
            childColumns = ["settlementGroupId"], onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageEntity::class, parentColumns = ["id"],
            childColumns = ["packageId"], onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["settlementGroupId", "packageId"], unique = true),
        Index(value = ["packageId"])
    ]
)
data class SettlementLineEntity(
    @PrimaryKey val id: String,
    val settlementGroupId: String,
    val packageId: String,
    val stockBefore: Long,
    val stockRemaining: Long,
    val soldQuantity: Long,
    val amountDueMinor: Long,
    @Embedded(prefix = "customer_") val customerSnapshot: CustomerSnapshot,
    @Embedded(prefix = "package_") val packageSnapshot: PackageSnapshot
) {
    init {
        require(id.isNotBlank() && settlementGroupId.isNotBlank() && packageId.isNotBlank())
        require(stockBefore > 0L && stockRemaining in 0L..stockBefore)
        require(soldQuantity == stockBefore - stockRemaining)
        require(amountDueMinor >= 0L)
        require(amountDueMinor == Math.multiplyExact(
            soldQuantity, packageSnapshot.generalSettlementPriceMinor
        ))
    }
}