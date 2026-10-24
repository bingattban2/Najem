package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najimwifi.app.data.local.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

data class OperationLogEntry(
    val id: String,
    val atMillis: Long,
    val operationType: String,
    val description: String,
    val subjectName: String,
    val packageName: String?,
    val cards: Long?,
    val amountMinor: Long?
)

/** Immutable audit postings, including rejected and compensating operations. */
@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AuditEventEntity)

    /** Automatically includes each committed action, independently of manual archive membership. */
    @Query("""
        SELECT a.id AS id, a.occurredAtEpochMillis AS atMillis,
               a.action AS operationType, a.description AS description,
               COALESCE(c.name, p.name,
                   (SELECT m.customer_name FROM card_movements m
                    WHERE m.operationId = a.operationId LIMIT 1),
                   (SELECT g.customer_name FROM settlement_groups g
                    WHERE g.operationId = a.operationId LIMIT 1), '') AS subjectName,
               CASE
                   WHEN a.action = 'DELIVERY' THEN
                     (SELECT m.package_name FROM card_movements m
                      WHERE m.operationId = a.operationId LIMIT 1)
                   WHEN a.action = 'SETTLEMENT' THEN
                     (SELECT GROUP_CONCAT(l.package_name, '، ')
                      FROM settlement_lines l
                      JOIN settlement_groups g ON g.id = l.settlementGroupId
                      WHERE g.operationId = a.operationId)
                   ELSE NULL END AS packageName,
               CASE
                   WHEN a.action = 'DELIVERY' THEN
                     (SELECT SUM(m.quantity) FROM card_movements m
                      WHERE m.operationId = a.operationId AND m.kind = 'DELIVERY')
                   WHEN a.action = 'SETTLEMENT' THEN
                     (SELECT SUM(l.soldQuantity)
                      FROM settlement_lines l
                      JOIN settlement_groups g ON g.id = l.settlementGroupId
                      WHERE g.operationId = a.operationId)
                   ELSE NULL END AS cards,
               CASE
                   WHEN a.action = 'SETTLEMENT' THEN
                     (SELECT g.totalAmountDueMinor FROM settlement_groups g
                      WHERE g.operationId=a.operationId LIMIT 1)
                   ELSE NULL END AS amountMinor
        FROM audit_events a
        LEFT JOIN customers c ON c.id = a.subjectId
        LEFT JOIN packages p ON p.id = a.subjectId
        WHERE a.outcome = 'COMMITTED'
        ORDER BY a.occurredAtEpochMillis DESC, a.id DESC
    """)
    fun observeOperationLog(): Flow<List<OperationLogEntry>>

    @Query("""
        SELECT * FROM audit_events
        WHERE action = 'CORRECTION' AND outcome = 'COMMITTED'
          AND correctionOfId = :sourceId LIMIT 1
    """)
    suspend fun findCorrectionOf(sourceId: String): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE id = :auditId LIMIT 1")
    suspend fun findById(auditId: String): AuditEventEntity?

    @Query("""
        SELECT * FROM audit_events WHERE operationId = :operationId
        ORDER BY occurredAtEpochMillis, id
    """)
    suspend fun findByOperation(operationId: String): List<AuditEventEntity>

    @Query("""
        SELECT * FROM audit_events WHERE subjectId = :subjectId
        ORDER BY occurredAtEpochMillis DESC, id DESC
    """)
    fun observeSubjectHistory(subjectId: String): Flow<List<AuditEventEntity>>

    @Query("""
        SELECT * FROM audit_events
        ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit
    """)
    suspend fun findLatest(limit: Int): List<AuditEventEntity>
}