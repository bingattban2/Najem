package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najimwifi.app.data.local.entity.CardMovementEntity
import kotlinx.coroutines.flow.Flow

data class CustomerConsignmentRow(
    val customerId: String,
    val quantity: Long
)

/** A single aggregated query replaces one observer per card package. */
data class PackageConsignmentRow(
    val packageId: String,
    val quantity: Long
)

@Dao
interface CardMovementDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: CardMovementEntity)

    @Query("""
        SELECT * FROM card_movements
        WHERE id = :movementId LIMIT 1
    """)
    suspend fun findById(
        movementId: String
    ): CardMovementEntity?

    @Query("""
        SELECT * FROM card_movements
        WHERE operationId = :operationId
        ORDER BY committedAtEpochMillis, id
    """)
    suspend fun findByOperation(
        operationId: String
    ): List<CardMovementEntity>

    @Query("""
        SELECT * FROM card_movements
        WHERE customerId = :customerId
          AND packageId = :packageId
        ORDER BY committedAtEpochMillis DESC, id DESC
    """)
    fun observeJournal(
        customerId: String,
        packageId: String
    ): Flow<List<CardMovementEntity>>

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity
            ELSE 0 END), 0)
        FROM card_movements
        WHERE customerId = :customerId
          AND packageId = :packageId
    """)
    suspend fun currentBalance(
        customerId: String,
        packageId: String
    ): Long

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity
            ELSE 0 END), 0)
        FROM card_movements
        WHERE customerId = :customerId
          AND packageId = :packageId
    """)
    fun observeBalance(
        customerId: String,
        packageId: String
    ): Flow<Long>

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity
            ELSE 0 END), 0)
        FROM card_movements
        WHERE packageId = :packageId
    """)
    fun observeAllShopsBalance(
        packageId: String
    ): Flow<Long>

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity
            ELSE 0 END), 0)
        FROM card_movements
        WHERE customerId = :customerId
          AND packageId = :packageId
          AND committedAtEpochMillis < :exclusiveEndEpochMillis
    """)
    suspend fun balanceBefore(
        customerId: String,
        packageId: String,
        exclusiveEndEpochMillis: Long
    ): Long

    @Query("""
        SELECT packageId,
            SUM(CASE effect
                WHEN 'INCREASE' THEN quantity
                WHEN 'DECREASE' THEN -quantity
                ELSE 0 END) AS quantity
        FROM card_movements
        GROUP BY packageId
    """)
    fun observePackageTotals(): Flow<List<PackageConsignmentRow>>

    @Query("""
        SELECT packageId,
            SUM(CASE effect
                WHEN 'INCREASE' THEN quantity
                WHEN 'DECREASE' THEN -quantity
                ELSE 0 END) AS quantity
        FROM card_movements
        WHERE customerId = :customerId
        GROUP BY packageId
    """)
    fun observeCustomerPackageTotals(customerId: String): Flow<List<PackageConsignmentRow>>

    @Query("""
        SELECT customerId,
            SUM(CASE effect
                WHEN 'INCREASE' THEN quantity
                WHEN 'DECREASE' THEN -quantity
                ELSE 0 END) AS quantity
        FROM card_movements
        GROUP BY customerId
    """)
    fun observeCustomerTotals():
        Flow<List<CustomerConsignmentRow>>
}