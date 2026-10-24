package com.najimwifi.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.najimwifi.app.domain.model.CentralStockEffect
import com.najimwifi.app.domain.model.CentralStockKind
import com.najimwifi.app.domain.model.PackageSnapshot

/** An optional setting; absence means central tracking is disabled. */
@Entity(
    tableName = "central_stock_policies",
    foreignKeys = [ForeignKey(
        entity = PackageEntity::class,
        parentColumns = ["id"],
        childColumns = ["packageId"],
        onDelete = ForeignKey.RESTRICT
    )]
)
data class CentralStockPolicyEntity(
    @PrimaryKey val packageId: String,
    val enabled: Boolean = false
) {
    init {
        require(packageId.isNotBlank())
    }
}

/** Immutable central-stock posting, not a sale or customer receivable. */
@Entity(
    tableName = "central_stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = PackageEntity::class,
            parentColumns = ["id"],
            childColumns = ["packageId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CentralStockEntity::class,
            parentColumns = ["id"],
            childColumns = ["reversesMovementId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["packageId", "committedAtEpochMillis"]),
        Index(value = ["customerId"]),
        Index(
            value = ["operationId", "packageId", "kind"],
            unique = true
        ),
        Index(value = ["reversesMovementId"], unique = true)
    ]
)
@TypeConverters(CentralStockConverters::class)
data class CentralStockEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val packageId: String,
    val kind: CentralStockKind,
    val effect: CentralStockEffect,
    val quantity: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val committedAtEpochMillis: Long,
    @Embedded(prefix = "package_")
    val packageSnapshot: PackageSnapshot,
    val customerId: String? = null,
    val reversesMovementId: String? = null,
    val correctionReason: String? = null,
    val authorizedBy: String? = null
) {
    init {
        require(
            id.isNotBlank() &&
                operationId.isNotBlank() &&
                packageId.isNotBlank()
        )
        require(
            quantity >= 0L &&
                balanceBefore >= 0L &&
                balanceAfter >= 0L
        )
        require(committedAtEpochMillis >= 0L)
        require(kind == CentralStockKind.OPENING || quantity > 0L)

        val expected = when (effect) {
            CentralStockEffect.INCREASE ->
                Math.addExact(balanceBefore, quantity)
            CentralStockEffect.DECREASE ->
                Math.subtractExact(balanceBefore, quantity)
        }

        require(balanceAfter == expected)

        when (kind) {
            CentralStockKind.OPENING -> require(
                effect == CentralStockEffect.INCREASE &&
                    balanceBefore == 0L &&
                    customerId == null &&
                    reversesMovementId == null
            )

            CentralStockKind.SUPPLY -> require(
                effect == CentralStockEffect.INCREASE &&
                    customerId == null &&
                    reversesMovementId == null
            )

            CentralStockKind.DELIVERY_TO_CUSTOMER -> require(
                effect == CentralStockEffect.DECREASE &&
                    !customerId.isNullOrBlank() &&
                    reversesMovementId == null
            )

            CentralStockKind.RETURN_FROM_CUSTOMER -> require(
                effect == CentralStockEffect.INCREASE &&
                    !customerId.isNullOrBlank() &&
                    reversesMovementId == null
            )

            CentralStockKind.CORRECTION -> require(
                !reversesMovementId.isNullOrBlank() &&
                    !correctionReason.isNullOrBlank() &&
                    !authorizedBy.isNullOrBlank()
            )
        }

        if (kind != CentralStockKind.CORRECTION) {
            require(correctionReason == null && authorizedBy == null)
        }
    }
}

class CentralStockConverters {
    @TypeConverter
    fun fromKind(value: CentralStockKind): String = value.name

    @TypeConverter
    fun toKind(value: String): CentralStockKind =
        CentralStockKind.valueOf(value)

    @TypeConverter
    fun fromEffect(value: CentralStockEffect): String = value.name

    @TypeConverter
    fun toEffect(value: String): CentralStockEffect =
        CentralStockEffect.valueOf(value)
}