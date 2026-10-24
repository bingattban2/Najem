package com.najimwifi.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.najimwifi.app.data.local.dao.ArchiveDao
import com.najimwifi.app.data.local.dao.AuditDao
import com.najimwifi.app.data.local.dao.CardMovementDao
import com.najimwifi.app.data.local.dao.CentralStockDao
import com.najimwifi.app.data.local.dao.ColumnDefinitionDao
import com.najimwifi.app.data.local.dao.ConsignmentSnapshotDao
import com.najimwifi.app.data.local.dao.CustomerDao
import com.najimwifi.app.data.local.dao.FinancialDao
import com.najimwifi.app.data.local.dao.PackageDao
import com.najimwifi.app.data.local.dao.HistoryPurgeDao
import com.najimwifi.app.data.local.dao.SettlementDao
import com.najimwifi.app.data.local.entity.ArchiveBatchEntity
import com.najimwifi.app.data.local.entity.ArchiveMembershipEntity
import com.najimwifi.app.data.local.entity.AuditEventEntity
import com.najimwifi.app.data.local.entity.CardMovementEntity
import com.najimwifi.app.data.local.entity.CentralStockEntity
import com.najimwifi.app.data.local.entity.CentralStockPolicyEntity
import com.najimwifi.app.data.local.entity.ColumnDefinitionEntity
import com.najimwifi.app.data.local.entity.CustomerEntity
import com.najimwifi.app.data.local.entity.FinancialEntryEntity
import com.najimwifi.app.data.local.entity.OptionalColumnCellEntity
import com.najimwifi.app.data.local.entity.PackageEntity
import com.najimwifi.app.data.local.entity.SettlementGroupEntity
import com.najimwifi.app.data.local.entity.SettlementLineEntity
import com.najimwifi.app.security.EncryptedDatabaseSupport
import com.najimwifi.app.backup.LegacyEncryptionCutover

@Database(
    entities = [
        CustomerEntity::class,
        PackageEntity::class,
        CardMovementEntity::class,
        SettlementGroupEntity::class,
        SettlementLineEntity::class,
        FinancialEntryEntity::class,
        ArchiveBatchEntity::class,
        ArchiveMembershipEntity::class,
        ColumnDefinitionEntity::class,
        OptionalColumnCellEntity::class,
        AuditEventEntity::class,
        CentralStockEntity::class,
        CentralStockPolicyEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(LedgerConverters::class)
abstract class NajimDatabase : RoomDatabase() {

    abstract fun customerDao(): CustomerDao

    abstract fun historyPurgeDao(): HistoryPurgeDao

    abstract fun packageDao(): PackageDao

    abstract fun cardMovementDao(): CardMovementDao

    abstract fun settlementDao(): SettlementDao

    abstract fun financialDao(): FinancialDao

    abstract fun archiveDao(): ArchiveDao

    abstract fun auditDao(): AuditDao

    abstract fun centralStockDao(): CentralStockDao

    abstract fun columnDefinitionDao(): ColumnDefinitionDao

    abstract fun consignmentSnapshotDao(): ConsignmentSnapshotDao

    companion object {
        private const val DATABASE_NAME = "najim_wifi.db"

        fun create(context: Context): NajimDatabase {
            val appContext = context.applicationContext
            LegacyEncryptionCutover.assertNoScheduledCutover(appContext)
            LegacyEncryptionCutover.noteRoomRequested()
            val encryption = EncryptedDatabaseSupport(appContext)
            val builder = Room.databaseBuilder(
                appContext,
                NajimDatabase::class.java,
                DATABASE_NAME
            )
            // Do not automatically convert existing SQLite data. That needs
            // a separately tested, backup-first migration and recovery path.
            encryption.openHelperFactoryOrNull()?.let { factory ->
                builder.openHelperFactory(factory)
            }
            return builder.build()
        }
    }
}