package com.najimwifi.app.domain.model

/** Price is global for every shop; uniqueness is enforced at persistence. */
data class Package(
    val id: String,
    val name: String,
    val nominalValueMinor: Long,
    val generalSettlementPriceMinor: Long,
    val colorArgb: Int,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(id.isNotBlank()) { "Package ID is required" }
        require(name.isNotBlank()) { "Package name is required" }
        require(nominalValueMinor > 0L) { "Nominal value must be positive" }
        require(generalSettlementPriceMinor > 0L) {
            "General settlement price must be positive"
        }
        require(createdAtEpochMillis >= 0L) { "Invalid creation timestamp" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Update timestamp precedes creation"
        }
    }

    fun snapshot(): PackageSnapshot = PackageSnapshot(
        name = name,
        nominalValueMinor = nominalValueMinor,
        generalSettlementPriceMinor = generalSettlementPriceMinor,
        colorArgb = colorArgb
    )
}