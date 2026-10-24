package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.najimwifi.app.data.local.entity.PackageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageDao {
    @Query("SELECT * FROM packages WHERE id = :packageId LIMIT 1")
    suspend fun findById(packageId: String): PackageEntity?

    @Query("SELECT * FROM packages WHERE id = :packageId LIMIT 1")
    fun observeById(packageId: String): Flow<PackageEntity?>

    @Query("SELECT * FROM packages ORDER BY name, id")
    fun observeAll(): Flow<List<PackageEntity>>

    @Query("SELECT * FROM packages WHERE isActive = 1 ORDER BY name, id")
    fun observeActiveForDelivery(): Flow<List<PackageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(packageEntity: PackageEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(packageEntity: PackageEntity): Int

    @Query("""
        UPDATE packages
        SET isActive = 0, updatedAtEpochMillis = :atEpochMillis
        WHERE id = :packageId AND isActive = 1
          AND updatedAtEpochMillis <= :atEpochMillis
    """)
    suspend fun deactivate(
        packageId: String,
        atEpochMillis: Long
    ): Int

    /** RESTRICT keys and these checks protect all historical activity. */
    @Query("""
        DELETE FROM packages WHERE id = :packageId
          AND NOT EXISTS (
              SELECT 1 FROM card_movements
              WHERE packageId = :packageId
          )
          AND NOT EXISTS (
              SELECT 1 FROM settlement_lines
              WHERE packageId = :packageId
          )
          AND NOT EXISTS (
              SELECT 1 FROM central_stock_movements
              WHERE packageId = :packageId
          )
          AND NOT EXISTS (
              SELECT 1 FROM central_stock_policies
              WHERE packageId = :packageId
          )
          AND NOT EXISTS (
              SELECT 1 FROM audit_events
              WHERE subjectId = :packageId
          )
    """)
    suspend fun deleteNeverUsed(packageId: String): Int
}