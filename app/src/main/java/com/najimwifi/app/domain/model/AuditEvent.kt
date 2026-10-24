package com.najimwifi.app.domain.model

enum class AuditAction {
    CUSTOMER_CHANGE,
    PACKAGE_CHANGE,
    DELIVERY,
    SETTLEMENT,
    PAYMENT,
    RETURN,
    CORRECTION,
    ARCHIVE,
    BACKUP,
    RESTORE,
    SECURITY_CHANGE,
    STOCK_MOVEMENT
}

enum class AuditOutcome { COMMITTED, REJECTED }

/** Audit entries describe actions; they are not a second financial ledger. */
data class AuditEvent(
    val id: String,
    val operationId: String,
    val occurredAtEpochMillis: Long,
    val action: AuditAction,
    val outcome: AuditOutcome,
    val subjectId: String,
    val description: String,
    val actorId: String? = null,
    val correctionOfId: String? = null
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank()) {
            "Audit and operation IDs are required"
        }
        require(occurredAtEpochMillis >= 0L) { "Invalid audit timestamp" }
        require(subjectId.isNotBlank()) { "Audited subject ID is required" }
        require(description.isNotBlank()) { "Audit description is required" }
        require(actorId == null || actorId.isNotBlank())
        require(correctionOfId == null || correctionOfId.isNotBlank())

        if (action == AuditAction.CORRECTION && outcome == AuditOutcome.COMMITTED) {
            require(correctionOfId != null) {
                "Committed corrections must reference an original event"
            }
        }
    }
}