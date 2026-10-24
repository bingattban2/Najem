package com.najimwifi.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.najimwifi.app.domain.model.ArchivedRecordType

@Entity(
    tableName = "archive_batches",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class ArchiveBatchEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val archivedAtEpochMillis: Long,
    val confirmedBy: String,
    val recordCount: Int
) {
    init {
        require(id.isNotBlank() && operationId.isNotBlank())
        require(archivedAtEpochMillis >= 0L)
        require(confirmedBy.isNotBlank() && recordCount > 0)
    }
}

/** One archived record can belong to only one batch, even across retries. */
@Entity(
    tableName = "archive_memberships",
    primaryKeys = ["recordType", "recordId"],
    foreignKeys = [ForeignKey(
        entity = ArchiveBatchEntity::class,
        parentColumns = ["id"],
        childColumns = ["archiveBatchId"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index(value = ["archiveBatchId"])]
)
@TypeConverters(ArchiveRecordConverters::class)
data class ArchiveMembershipEntity(
    val recordType: ArchivedRecordType,
    val recordId: String,
    val archiveBatchId: String
) {
    init {
        require(recordId.isNotBlank() && archiveBatchId.isNotBlank())
    }
}

class ArchiveRecordConverters {
    @TypeConverter
    fun fromRecordType(value: ArchivedRecordType): String = value.name

    @TypeConverter
    fun toRecordType(value: String): ArchivedRecordType =
        ArchivedRecordType.valueOf(value)
}