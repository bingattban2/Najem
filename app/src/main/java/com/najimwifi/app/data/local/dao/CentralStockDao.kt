package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.najimwifi.app.data.local.entity.CentralStockEntity
import com.najimwifi.app.data.local.entity.CentralStockPolicyEntity
import kotlinx.coroutines.flow.Flow

/** Optional central warehouse; shop balances are computed from card_movements. */
@Dao
interface CentralStockDao {
    @Upsert
    suspend fun savePolicy(policy: CentralStockPolicyEntity)

    @Query("SELECT * FROM central_stock_policies WHERE packageId = :packageId LIMIT 1")
    suspend fun findPolicy(packageId: String): CentralStockPolicyEntity?

    @Query("SELECT * FROM central_stock_policies WHERE packageId = :packageId LIMIT 1")
    fun observePolicy(packageId: String): Flow<CentralStockPolicyEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovement(movement: CentralStockEntity)

    @Query("SELECT * FROM central_stock_movements WHERE id = :movementId LIMIT 1")
    suspend fun findMovement(movementId: String): CentralStockEntity?

    @Query("""
        SELECT * FROM central_stock_movements WHERE operationId = :operationId
        ORDER BY committedAtEpochMillis, id
    """)
    suspend fun findByOperation(operationId: String): List<CentralStockEntity>

    @Query("""
        SELECT * FROM central_stock_movements WHERE packageId = :packageId
        ORDER BY committedAtEpochMillis DESC, id DESC
    """)
    fun observeMovements(packageId: String): Flow<List<CentralStockEntity>>

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity ELSE 0 END), 0)
        FROM central_stock_movements WHERE packageId = :packageId
    """)
    suspend fun currentBalance(packageId: String): Long

    @Query("""
        SELECT COALESCE(SUM(CASE effect
            WHEN 'INCREASE' THEN quantity
            WHEN 'DECREASE' THEN -quantity ELSE 0 END), 0)
        FROM central_stock_movements WHERE packageId = :packageId
    """)
    fun observeBalance(packageId: String): Flow<Long>
}