package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najimwifi.app.data.local.entity.FinancialEntryEntity
import com.najimwifi.app.domain.service.CustomerAlertSnapshot
import com.najimwifi.app.domain.model.FinancialEntryKind
import kotlinx.coroutines.flow.Flow

/** Only financial_entries contributes to customer debt; never add group totals again. */
@Dao
interface FinancialDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: FinancialEntryEntity)

    @Query("SELECT * FROM financial_entries WHERE id = :entryId LIMIT 1")
    suspend fun findById(entryId: String): FinancialEntryEntity?

    @Query("SELECT * FROM financial_entries WHERE reversesEntryId = :sourceId LIMIT 1")
    suspend fun findReversalOf(sourceId: String): FinancialEntryEntity?

    @Query("SELECT * FROM financial_entries WHERE receiptNumber = :receipt LIMIT 1")
    suspend fun findByReceipt(receipt: String): FinancialEntryEntity?

    @Query("SELECT * FROM financial_entries WHERE operationId = :operationId ORDER BY committedAtEpochMillis, id")
    suspend fun findByOperation(operationId: String): List<FinancialEntryEntity>

    @Query("""
        SELECT * FROM financial_entries
        WHERE settlementGroupId = :groupId AND kind = :kind LIMIT 1
    """)
    suspend fun findGroupEntry(
        groupId: String,
        kind: FinancialEntryKind
    ): FinancialEntryEntity?

    @Query("""
        SELECT * FROM financial_entries WHERE customerId = :customerId
        ORDER BY committedAtEpochMillis DESC, id DESC
    """)
    fun observeCustomerHistory(customerId: String): Flow<List<FinancialEntryEntity>>

    @Query("""
        SELECT COALESCE(SUM(CASE kind
            WHEN 'SETTLEMENT_RECEIVABLE' THEN amountMinor
            WHEN 'COLLECTION_REVERSAL' THEN amountMinor
            WHEN 'COLLECTION' THEN -amountMinor
            WHEN 'RECEIVABLE_REVERSAL' THEN -amountMinor ELSE 0 END), 0)
        FROM financial_entries WHERE customerId = :customerId
    """)
    suspend fun currentDebt(customerId: String): Long

    @Query("""
        SELECT COALESCE(SUM(CASE kind
            WHEN 'SETTLEMENT_RECEIVABLE' THEN amountMinor
            WHEN 'COLLECTION_REVERSAL' THEN amountMinor
            WHEN 'COLLECTION' THEN -amountMinor
            WHEN 'RECEIVABLE_REVERSAL' THEN -amountMinor ELSE 0 END), 0)
        FROM financial_entries WHERE customerId = :customerId
    """)
    fun observeDebt(customerId: String): Flow<Long>

    @Query("""
        SELECT COALESCE(SUM(CASE kind
            WHEN 'SETTLEMENT_RECEIVABLE' THEN amountMinor
            WHEN 'COLLECTION_REVERSAL' THEN amountMinor
            WHEN 'COLLECTION' THEN -amountMinor
            WHEN 'RECEIVABLE_REVERSAL' THEN -amountMinor ELSE 0 END), 0)
        FROM financial_entries
        WHERE customerId = :customerId
          AND committedAtEpochMillis < :exclusiveEndEpochMillis
    """)
    suspend fun debtBefore(customerId: String, exclusiveEndEpochMillis: Long): Long


    /**
     * Read-only aggregate snapshot. Never add settlement group totals to debt:
     * the financial journal is its sole source of truth.
     */
    @Query("""
        SELECT c.id AS customerId,
               c.name AS customerName,
               COALESCE((
                   SELECT SUM(CASE f.kind
                       WHEN 'SETTLEMENT_RECEIVABLE' THEN f.amountMinor
                       WHEN 'COLLECTION_REVERSAL' THEN f.amountMinor
                       WHEN 'COLLECTION' THEN -f.amountMinor
                       WHEN 'RECEIVABLE_REVERSAL' THEN -f.amountMinor
                       ELSE 0 END)
                   FROM financial_entries AS f WHERE f.customerId = c.id
               ), 0) AS debtMinor,
               COALESCE((
                   SELECT SUM(CASE m.effect
                       WHEN 'INCREASE' THEN m.quantity
                       WHEN 'DECREASE' THEN -m.quantity ELSE 0 END)
                   FROM card_movements AS m WHERE m.customerId = c.id
               ), 0) AS consignmentCards,
               (SELECT MIN(d.committedAtEpochMillis)
                  FROM card_movements AS d
                 WHERE d.customerId = c.id AND d.kind = 'DELIVERY')
                   AS firstDeliveryAtEpochMillis,
               (SELECT MAX(s.committedAtEpochMillis)
                  FROM settlement_groups AS s WHERE s.customerId = c.id)
                   AS lastSettlementAtEpochMillis
        FROM customers AS c
        WHERE EXISTS (SELECT 1 FROM financial_entries AS f WHERE f.customerId = c.id)
           OR EXISTS (SELECT 1 FROM card_movements AS m WHERE m.customerId = c.id)
        ORDER BY c.name, c.id
    """)
    suspend fun customerAlertSnapshots(): List<CustomerAlertSnapshot>
}
