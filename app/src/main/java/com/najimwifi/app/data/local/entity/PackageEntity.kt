package com.najimwifi.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "packages",
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["nominalValueMinor"], unique = true),
        Index(value = ["colorArgb"], unique = true)
    ]
)
data class PackageEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val nominalValueMinor: Long,
    val generalSettlementPriceMinor: Long,
    val colorArgb: Int,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && normalizedName.isNotBlank())
        require(nominalValueMinor > 0L && generalSettlementPriceMinor > 0L)
        require(createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}