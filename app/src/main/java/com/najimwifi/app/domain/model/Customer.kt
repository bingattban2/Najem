package com.najimwifi.app.domain.model

/** Current editable customer details; posted events use CustomerSnapshot. */
data class Customer(
    val id: String,
    val name: String,
    val location: String,
    val phone: String? = null,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(id.isNotBlank()) { "Customer ID is required" }
        require(name.isNotBlank()) { "Customer name is required" }
        require(location.isNotBlank()) { "Customer location is required" }
        require(createdAtEpochMillis >= 0L) { "Invalid creation timestamp" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Update timestamp precedes creation"
        }
    }

    fun snapshot(): CustomerSnapshot = CustomerSnapshot(name, location)
}