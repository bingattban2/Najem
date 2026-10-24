package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.najimwifi.app.data.local.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun findById(customerId: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    fun observeById(customerId: String): Flow<CustomerEntity?>

    @Query("SELECT * FROM customers ORDER BY name, id")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY name, id")
    fun observeActive(): Flow<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(customer: CustomerEntity): Int

    @Query("""
        UPDATE customers
        SET isActive = 0, updatedAtEpochMillis = :atEpochMillis
        WHERE id = :customerId AND isActive = 1
          AND updatedAtEpochMillis <= :atEpochMillis
    """)
    suspend fun deactivate(
        customerId: String,
        atEpochMillis: Long
    ): Int

    /** Physical removal only for customers with no posted history. */
    @Query("""
        DELETE FROM customers WHERE id = :customerId
          AND NOT EXISTS (
              SELECT 1 FROM card_movements
              WHERE customerId = :customerId
          )
          AND NOT EXISTS (
              SELECT 1 FROM settlement_groups
              WHERE customerId = :customerId
          )
          AND NOT EXISTS (
              SELECT 1 FROM financial_entries
              WHERE customerId = :customerId
          )
          AND NOT EXISTS (
              SELECT 1 FROM central_stock_movements
              WHERE customerId = :customerId
          )
          AND NOT EXISTS (
              SELECT 1 FROM audit_events
              WHERE subjectId = :customerId
          )
    """)
    suspend fun deleteNeverUsed(customerId: String): Int
}