package com.najimwifi.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.najimwifi.app.domain.model.AuditAction
import com.najimwifi.app.domain.model.AuditOutcome

/** Append-only audit events. Polymorphic subject IDs are not SQL foreign keys. */
@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["operationId"]),
        Index(value = ["occurredAtEpochMillis"]),
        Index(value = ["subjectId"]),
        Index(value = ["correctionOfId"])
    ]
)
@TypeConverters(AuditConverters::class)
data class AuditEventEntity(
    @PrimaryKey val id: String,
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
        require(id.isNotBlank() && operationId.isNotBlank())
        require(occurredAtEpochMillis >= 0L)
        require(subjectId.isNotBlank() && description.isNotBlank())
        require(actorId == null || actorId.isNotBlank())
        require(correctionOfId == null || correctionOfId.isNotBlank())

        if (action == AuditAction.CORRECTION &&
            outcome == AuditOutcome.COMMITTED
        ) {
            require(correctionOfId != null)
        }
    }
}

class AuditConverters {
    @TypeConverter
    fun fromAction(value: AuditAction): String = value.name

    @TypeConverter
    fun toAction(value: String): AuditAction =
        AuditAction.valueOf(value)

    @TypeConverter
    fun fromOutcome(value: AuditOutcome): String = value.name

    @TypeConverter
    fun toOutcome(value: String): AuditOutcome =
        AuditOutcome.valueOf(value)
}