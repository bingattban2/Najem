package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** Destructive removal is allowed only after a zero-consignment check in one Room transaction.
 * Foreign keys and the transaction roll back partial deletion if any dependency survives.
 */
@Dao
interface HistoryPurgeDao {
    @Query("""SELECT COUNT(*) FROM (SELECT packageId, SUM(CASE effect WHEN 'INCREASE' THEN quantity ELSE -quantity END) balance FROM card_movements WHERE customerId = :id GROUP BY packageId HAVING balance <> 0)""")
    suspend fun unsettledCustomer(id: String): Int

    @Query("""SELECT COUNT(*) FROM (SELECT customerId, SUM(CASE effect WHEN 'INCREASE' THEN quantity ELSE -quantity END) balance FROM card_movements WHERE packageId = :id GROUP BY customerId HAVING balance <> 0)""")
    suspend fun unsettledPackage(id: String): Int

    @Query("SELECT COUNT(*) FROM central_stock_movements WHERE customerId=:id")
    suspend fun customerCentralHistory(id: String): Int

    @Query("SELECT COUNT(*) FROM central_stock_movements WHERE packageId=:id")
    suspend fun packageCentralHistory(id: String): Int

    @Query("""SELECT COUNT(*) FROM settlement_lines l WHERE l.packageId = :id AND EXISTS (SELECT 1 FROM settlement_lines other WHERE other.settlementGroupId = l.settlementGroupId AND other.packageId <> :id)""")
    suspend fun mixedPackageSettlements(id: String): Int

    @Query("""SELECT COALESCE(SUM(CASE effect WHEN 'INCREASE' THEN quantity ELSE -quantity END), 0) FROM central_stock_movements WHERE packageId = :id""")
    suspend fun packageCentralBalance(id: String): Long

    @Query("""DELETE FROM optional_column_cells WHERE (rowKind = 'CARD_MOVEMENT' AND rowId IN (SELECT id FROM card_movements WHERE customerId = :id)) OR (rowKind = 'SETTLEMENT_GROUP' AND rowId IN (SELECT id FROM settlement_groups WHERE customerId = :id)) OR (rowKind = 'SETTLEMENT_LINE' AND rowId IN (SELECT l.id FROM settlement_lines l JOIN settlement_groups g ON g.id=l.settlementGroupId WHERE g.customerId = :id)) OR (rowKind = 'FINANCIAL_ENTRY' AND rowId IN (SELECT id FROM financial_entries WHERE customerId = :id))""")
    suspend fun clearCustomerOptional(id: String): Unit

    @Query("""DELETE FROM optional_column_cells WHERE (rowKind = 'CARD_MOVEMENT' AND rowId IN (SELECT id FROM card_movements WHERE packageId = :id)) OR (rowKind = 'SETTLEMENT_GROUP' AND rowId IN (SELECT g.id FROM settlement_groups g JOIN settlement_lines l ON l.settlementGroupId=g.id WHERE l.packageId = :id)) OR (rowKind = 'SETTLEMENT_LINE' AND rowId IN (SELECT id FROM settlement_lines WHERE packageId = :id)) OR (rowKind = 'FINANCIAL_ENTRY' AND rowId IN (SELECT f.id FROM financial_entries f JOIN settlement_lines l ON f.settlementGroupId=l.settlementGroupId WHERE l.packageId = :id))""")
    suspend fun clearPackageOptional(id: String): Unit

    @Query("""DELETE FROM archive_memberships WHERE (recordType='CARD_MOVEMENT' AND recordId IN (SELECT id FROM card_movements WHERE customerId=:id)) OR (recordType='SETTLEMENT_GROUP' AND recordId IN (SELECT id FROM settlement_groups WHERE customerId=:id)) OR (recordType='FINANCIAL_ENTRY' AND recordId IN (SELECT id FROM financial_entries WHERE customerId=:id))""")
    suspend fun clearCustomerMemberships(id: String): Unit

    @Query("""DELETE FROM archive_memberships WHERE (recordType='CARD_MOVEMENT' AND recordId IN (SELECT id FROM card_movements WHERE packageId=:id)) OR (recordType='SETTLEMENT_GROUP' AND recordId IN (SELECT g.id FROM settlement_groups g JOIN settlement_lines l ON l.settlementGroupId=g.id WHERE l.packageId=:id)) OR (recordType='FINANCIAL_ENTRY' AND recordId IN (SELECT f.id FROM financial_entries f JOIN settlement_lines l ON f.settlementGroupId=l.settlementGroupId WHERE l.packageId=:id))""")
    suspend fun clearPackageMemberships(id: String): Unit

    @Query("""UPDATE archive_batches SET recordCount = (SELECT COUNT(*) FROM archive_memberships m WHERE m.archiveBatchId = archive_batches.id)""")
    suspend fun recountArchives(): Unit

    @Query("""DELETE FROM archive_batches WHERE NOT EXISTS (SELECT 1 FROM archive_memberships m WHERE m.archiveBatchId = archive_batches.id)""")
    suspend fun removeEmptyArchives(): Unit

    @Query("""DELETE FROM audit_events WHERE subjectId = :id OR operationId IN (SELECT operationId FROM card_movements WHERE customerId=:id) OR operationId IN (SELECT operationId FROM settlement_groups WHERE customerId=:id) OR operationId IN (SELECT operationId FROM financial_entries WHERE customerId=:id)""")
    suspend fun clearCustomerAudit(id: String): Unit

    @Query("""DELETE FROM audit_events WHERE subjectId = :id OR operationId IN (SELECT operationId FROM card_movements WHERE packageId=:id) OR operationId IN (SELECT g.operationId FROM settlement_groups g JOIN settlement_lines l ON l.settlementGroupId=g.id WHERE l.packageId=:id) OR correctionOfId IN (SELECT g.id FROM settlement_groups g JOIN settlement_lines l ON l.settlementGroupId=g.id WHERE l.packageId=:id)""")
    suspend fun clearPackageAudit(id: String): Unit

    @Query("""DELETE FROM central_stock_movements WHERE customerId=:id AND reversesMovementId IS NOT NULL""")
    suspend fun clearCustomerStockCorrections(id: String): Unit

    @Query("""DELETE FROM central_stock_movements WHERE customerId=:id""")
    suspend fun clearCustomerStock(id: String): Unit

    @Query("""DELETE FROM central_stock_movements WHERE packageId=:id AND reversesMovementId IS NOT NULL""")
    suspend fun clearPackageStockCorrections(id: String): Unit

    @Query("""DELETE FROM central_stock_movements WHERE packageId=:id""")
    suspend fun clearPackageStock(id: String): Unit

    @Query("""DELETE FROM central_stock_policies WHERE packageId=:id""")
    suspend fun clearPackagePolicy(id: String): Unit

    @Query("""DELETE FROM financial_entries WHERE customerId=:id AND reversesEntryId IS NOT NULL""")
    suspend fun clearCustomerFinanceReversals(id: String): Unit

    @Query("""DELETE FROM financial_entries WHERE customerId=:id""")
    suspend fun clearCustomerFinance(id: String): Unit

    @Query("""DELETE FROM financial_entries WHERE reversesEntryId IN (SELECT f.id FROM financial_entries f JOIN settlement_lines l ON f.settlementGroupId=l.settlementGroupId WHERE l.packageId=:id)""")
    suspend fun clearPackageFinanceReversals(id: String): Unit

    @Query("""DELETE FROM financial_entries WHERE settlementGroupId IN (SELECT settlementGroupId FROM settlement_lines WHERE packageId=:id)""")
    suspend fun clearPackageFinance(id: String): Unit

    @Query("""DELETE FROM card_movements WHERE customerId=:id AND reversesMovementId IS NOT NULL""")
    suspend fun clearCustomerCardCorrections(id: String): Unit

    @Query("""DELETE FROM card_movements WHERE customerId=:id""")
    suspend fun clearCustomerCards(id: String): Unit

    @Query("""DELETE FROM card_movements WHERE packageId=:id AND reversesMovementId IS NOT NULL""")
    suspend fun clearPackageCardCorrections(id: String): Unit

    @Query("""DELETE FROM card_movements WHERE packageId=:id""")
    suspend fun clearPackageCards(id: String): Unit

    @Query("""DELETE FROM settlement_lines WHERE settlementGroupId IN (SELECT id FROM settlement_groups WHERE customerId=:id)""")
    suspend fun clearCustomerLines(id: String): Unit

    @Query("""DELETE FROM settlement_groups WHERE customerId=:id""")
    suspend fun clearCustomerGroups(id: String): Unit

    @Query("""DELETE FROM settlement_lines WHERE packageId=:id""")
    suspend fun clearPackageLines(id: String): Unit

    @Query("SELECT DISTINCT customerId FROM card_movements WHERE packageId=:id UNION SELECT DISTINCT g.customerId FROM settlement_groups g JOIN settlement_lines l ON g.id=l.settlementGroupId WHERE l.packageId=:id")
    suspend fun affectedCustomersOfPackage(id: String): List<String>

    @Query("SELECT DISTINCT settlementGroupId FROM settlement_lines WHERE packageId=:id")
    suspend fun packageGroupIds(id: String): List<String>

    @Query("DELETE FROM settlement_groups WHERE id=:id AND NOT EXISTS (SELECT 1 FROM settlement_lines WHERE settlementGroupId=:id)")
    suspend fun deletePackageGroup(id: String)

    @Query("""DELETE FROM customers WHERE id=:id""")
    suspend fun deleteCustomer(id: String): Unit

    @Query("""DELETE FROM packages WHERE id=:id""")
    suspend fun deletePackage(id: String): Unit

}
