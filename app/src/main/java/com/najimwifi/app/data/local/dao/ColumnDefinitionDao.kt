package com.najimwifi.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import com.najimwifi.app.data.local.entity.ColumnConverters
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.data.local.entity.OptionalColumnCellEntity
import com.najimwifi.app.domain.model.ColumnTarget
import kotlinx.coroutines.flow.Flow

@Dao
@TypeConverters(ColumnConverters::class)
interface ColumnDefinitionDao {

    @Query("""
        SELECT * FROM column_definitions
        WHERE target = :target
        ORDER BY displayOrder, id
    """)
    fun observeColumns(
        target: ColumnTarget
    ): Flow<List<ColumnDefinitionEntity>>

    @Query("""
        SELECT * FROM column_definitions
        WHERE id = :id LIMIT 1
    """)
    suspend fun findColumn(
        id: String
    ): ColumnDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertColumn(
        column: ColumnDefinitionEntity
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRequiredIfAbsent(
        column: ColumnDefinitionEntity
    ): Long

    @Query("""
        UPDATE column_definitions
        SET title = :title,
            displayOrder = :position,
            isActive = :active
        WHERE id = :id AND requiredKey IS NULL
    """)
    suspend fun editOptionalColumn(
        id: String,
        title: String,
        position: Int,
        active: Boolean
    ): Int

    @Query("""
        UPDATE column_definitions
        SET title = :title
        WHERE id = :id
    """)
    suspend fun renameDisplayLabel(
        id: String,
        title: String
    ): Int

    @Query("""
        UPDATE column_definitions
        SET isActive = 0
        WHERE id = :id AND requiredKey IS NULL
    """)
    suspend fun hideOptionalColumn(
        id: String
    ): Int

    @Query("""
        SELECT * FROM optional_column_cells
        WHERE rowId = :rowId
        ORDER BY columnId
    """)
    suspend fun cellsForRow(
        rowId: String
    ): List<OptionalColumnCellEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCell(
        cell: OptionalColumnCellEntity
    )

    @Query("""
        DELETE FROM optional_column_cells
        WHERE columnId = :columnId
          AND rowKind = :kind
          AND rowId = :rowId
    """)
    suspend fun removeCell(
        columnId: String,
        kind: String,
        rowId: String
    ): Int

    @Query("""
        SELECT c.* FROM optional_column_cells AS c
        WHERE (
            c.rowKind = 'CARD_MOVEMENT'
            AND c.rowId IN (
                SELECT m.id FROM card_movements AS m
                WHERE m.customerId = :customerId
                  AND m.packageId = :packageId
            )
        ) OR (
            c.rowKind = 'SETTLEMENT_LINE'
            AND c.rowId IN (
                SELECT l.id
                FROM settlement_lines AS l
                INNER JOIN settlement_groups AS g
                    ON g.id = l.settlementGroupId
                WHERE g.customerId = :customerId
                  AND l.packageId = :packageId
            )
        )
        ORDER BY c.rowKind, c.rowId, c.columnId
    """)
    fun observePackageJournalCells(
        customerId: String,
        packageId: String
    ): Flow<List<OptionalColumnCellEntity>>
}

data class PositiveConsignmentRow(
    val packageId: String,
    val onHand: Long
)

@Dao
interface ConsignmentSnapshotDao {

    @Query("""
        SELECT packageId,
            SUM(
                CASE
                    WHEN effect = 'INCREASE' THEN quantity
                    ELSE -quantity
                END
            ) AS onHand
        FROM card_movements
        WHERE customerId = :customerId
        GROUP BY packageId
        HAVING SUM(
            CASE
                WHEN effect = 'INCREASE' THEN quantity
                ELSE -quantity
            END
        ) > 0
        ORDER BY packageId
    """)
    suspend fun positiveBalances(
        customerId: String
    ): List<PositiveConsignmentRow>
}