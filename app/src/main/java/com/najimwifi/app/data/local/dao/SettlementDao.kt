package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najimwifi.app.data.local.entity.SettlementGroupEntity
import com.najimwifi.app.data.local.entity.SettlementLineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(
        group: SettlementGroupEntity
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLines(
        lines: List<SettlementLineEntity>
    )

    @Query("""
        SELECT * FROM settlement_groups
        WHERE id = :groupId LIMIT 1
    """)
    suspend fun findGroup(
        groupId: String
    ): SettlementGroupEntity?

    @Query("""
        SELECT * FROM settlement_groups
        WHERE operationId = :operationId LIMIT 1
    """)
    suspend fun findGroupByOperation(
        operationId: String
    ): SettlementGroupEntity?

    /** Later physical counts depend on the earlier settlement and must be undone first. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM settlement_lines AS l
            INNER JOIN settlement_groups AS g ON g.id = l.settlementGroupId
            WHERE g.customerId = :customerId AND l.packageId = :packageId
              AND g.id != :sourceGroupId
              AND (g.committedAtEpochMillis > :sourceAt OR
                   (g.committedAtEpochMillis = :sourceAt AND g.id > :sourceGroupId))
        )
    """)
    suspend fun hasLaterPhysicalCount(
        customerId: String,
        packageId: String,
        sourceAt: Long,
        sourceGroupId: String
    ): Boolean

    @Query("""
        SELECT * FROM settlement_lines
        WHERE settlementGroupId = :groupId
        ORDER BY packageId, id
    """)
    suspend fun findLines(
        groupId: String
    ): List<SettlementLineEntity>

    @Query("""
        SELECT * FROM settlement_lines
        WHERE settlementGroupId = :groupId
        ORDER BY packageId, id
    """)
    fun observeLines(
        groupId: String
    ): Flow<List<SettlementLineEntity>>

    @Query("""
        SELECT * FROM settlement_groups
        WHERE customerId = :customerId
        ORDER BY committedAtEpochMillis DESC, id DESC
    """)
    fun observeCustomerSettlements(
        customerId: String
    ): Flow<List<SettlementGroupEntity>>

    @Query("""
        SELECT COALESCE(SUM(amountDueMinor), 0)
        FROM settlement_lines
        WHERE settlementGroupId = :groupId
    """)
    suspend fun sumLineAmounts(
        groupId: String
    ): Long

    @Query("""
        SELECT l.* FROM settlement_lines AS l
        INNER JOIN settlement_groups AS g
            ON g.id = l.settlementGroupId
        WHERE g.customerId = :customerId
          AND l.packageId = :packageId
        ORDER BY g.committedAtEpochMillis DESC, l.id DESC
    """)
    fun observeLinesForCustomerPackage(
        customerId: String,
        packageId: String
    ): Flow<List<SettlementLineEntity>>

    @Query("""
        SELECT l.* FROM settlement_lines AS l
        INNER JOIN settlement_groups AS g
            ON g.id = l.settlementGroupId
        WHERE l.id = :lineId
          AND g.customerId = :customerId
          AND l.packageId = :packageId
        LIMIT 1
    """)
    suspend fun findLineForCustomerPackage(
        lineId: String,
        customerId: String,
        packageId: String
    ): SettlementLineEntity?
}