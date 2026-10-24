package com.najimwifi.app.domain.model

/** Membership points to original posted events; archiving never copies ledger money. */
enum class ArchivedRecordType {
    SETTLEMENT_GROUP,
    CARD_MOVEMENT,
    FINANCIAL_ENTRY
}

data class ArchivedRecordRef(
    val recordType: ArchivedRecordType,
    val recordId: String
) {
    init {
        require(recordId.isNotBlank()) { "Archived record ID is required" }
    }
}

data class ArchiveBatch(
    val id: String,
    val operationId: String,
    val archivedAtEpochMillis: Long,
    val confirmedBy: String,
    val records: List<ArchivedRecordRef>
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank()) {
            "Archive IDs are required"
        }
        require(archivedAtEpochMillis >= 0L) { "Invalid archive timestamp" }
        require(confirmedBy.isNotBlank()) { "Explicit confirmation is required" }
        require(records.isNotEmpty()) { "An empty archive must not be posted" }
        require(records.size == records.toSet().size) {
            "An archive cannot contain the same record twice"
        }
    }

    val recordCount: Int get() = records.size
}