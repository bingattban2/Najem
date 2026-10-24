package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najimwifi.app.data.local.entity.ArchiveBatchEntity
import com.najimwifi.app.data.local.entity.ArchiveMembershipEntity
import com.najimwifi.app.data.local.entity.SettlementGroupEntity
import com.najimwifi.app.data.local.entity.FinancialEntryEntity
import kotlinx.coroutines.flow.Flow

/** Archive membership points to posted rows; it creates no sales or opening stock. */
@Dao
interface ArchiveDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: ArchiveBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemberships(memberships: List<ArchiveMembershipEntity>)

    @Query("SELECT * FROM archive_batches WHERE operationId = :operationId LIMIT 1")
    suspend fun findBatchByOperation(operationId: String): ArchiveBatchEntity?

    @Query("SELECT * FROM archive_batches ORDER BY archivedAtEpochMillis DESC, id DESC")
    fun observeBatches(): Flow<List<ArchiveBatchEntity>>

    @Query("SELECT * FROM archive_memberships WHERE archiveBatchId = :batchId ORDER BY recordType, recordId")
    suspend fun findMemberships(batchId: String): List<ArchiveMembershipEntity>

    @Query("""
        SELECT * FROM archive_memberships
        WHERE recordType = 'SETTLEMENT_GROUP' AND recordId = :groupId LIMIT 1
    """)
    suspend fun findArchivedGroup(groupId: String): ArchiveMembershipEntity?

    @Query("""
        SELECT g.* FROM settlement_groups AS g
        WHERE NOT EXISTS (
            SELECT 1 FROM archive_memberships AS m
            WHERE m.recordType = 'SETTLEMENT_GROUP' AND m.recordId = g.id
        )
          AND NOT EXISTS (
            SELECT 1 FROM audit_events AS a
            WHERE a.action = 'CORRECTION' AND a.outcome = 'COMMITTED'
              AND a.correctionOfId = g.id
          )
        ORDER BY g.committedAtEpochMillis DESC, g.id DESC
    """)
    suspend fun findNotYetArchivedGroups(): List<SettlementGroupEntity>

    /** Historical deliveries for a package that has at least one completed settlement.
     *  Archive membership is metadata only; balances are never recalculated or reset.
     */
    @Query("""
        SELECT d.* FROM card_movements AS d
        WHERE d.customerId = :customerId AND d.packageId = :packageId
          AND d.kind = 'DELIVERY'
          AND d.committedAtEpochMillis <= :throughMillis
          AND NOT EXISTS (
              SELECT 1 FROM archive_memberships AS a
              WHERE a.recordType = 'CARD_MOVEMENT' AND a.recordId = d.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM card_movements AS r
              WHERE r.reversesMovementId = d.id
          )
        ORDER BY d.committedAtEpochMillis, d.id
    """)
    suspend fun findUnarchivedDeliveries(
        customerId: String,
        packageId: String,
        throughMillis: Long
    ): List<com.najimwifi.app.data.local.entity.CardMovementEntity>

    /** Stand-alone collections, never the one payment inside a settlement group. */
    @Query("""
        SELECT f.* FROM financial_entries AS f
        WHERE f.kind = 'COLLECTION' AND f.settlementGroupId IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM financial_entries AS r
              WHERE r.reversesEntryId = f.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM archive_memberships AS m
              WHERE m.recordType = 'FINANCIAL_ENTRY' AND m.recordId = f.id
          )
        ORDER BY f.committedAtEpochMillis DESC, f.id DESC
    """)
    suspend fun findNotYetArchivedPayments(): List<FinancialEntryEntity>
}