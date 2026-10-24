package com.najimwifi.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [Index(value = ["name"]), Index(value = ["location"])]
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val location: String,
    val phone: String? = null,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && location.isNotBlank())
        require(createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}