package com.najimwifi.app.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory only; never opens or mutates najim_wifi.db. */
@RunWith(AndroidJUnit4::class)
class BackupLedgerInvariantTest {
    private fun fixture(): SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE financial_entries (id TEXT, customerId TEXT, kind TEXT, amountMinor INTEGER, settlementGroupId TEXT, reversesEntryId TEXT)")
        execSQL("CREATE TABLE card_movements (id TEXT, customerId TEXT, packageId TEXT, kind TEXT, effect TEXT, quantity INTEGER, settlementLineId TEXT, reversesMovementId TEXT)")
        execSQL("CREATE TABLE central_stock_movements (id TEXT, packageId TEXT, effect TEXT, quantity INTEGER)")
        execSQL("CREATE TABLE settlement_groups (id TEXT, customerId TEXT, previousDebtMinor INTEGER, totalAmountDueMinor INTEGER, singlePaymentMinor INTEGER, endingDebtMinor INTEGER, paymentEntryId TEXT)")
        execSQL("CREATE TABLE settlement_lines (id TEXT, settlementGroupId TEXT, packageId TEXT, stockBefore INTEGER, stockRemaining INTEGER, soldQuantity INTEGER, amountDueMinor INTEGER, package_generalSettlementPriceMinor INTEGER)")
        execSQL("CREATE TABLE archive_batches (id TEXT, recordCount INTEGER)")
        execSQL("CREATE TABLE archive_memberships (recordType TEXT, recordId TEXT, archiveBatchId TEXT)")
    }

    private fun verify(db: SQLiteDatabase) {
        RestoreValidator().verifyLedgerQueries { sql ->
            db.rawQuery(sql, null).use { it.moveToFirst() }
        }
    }

    private fun seed(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO financial_entries VALUES ('due','a','SETTLEMENT_RECEIVABLE',10000,'g',NULL)")
        db.execSQL("INSERT INTO financial_entries VALUES ('paid','a','COLLECTION',2000,'g',NULL)")
        db.execSQL("INSERT INTO card_movements VALUES ('delivery','a','p','DELIVERY','INCREASE',20,NULL,NULL)")
        db.execSQL("INSERT INTO card_movements VALUES ('sale','a','p','SETTLEMENT_SALE','DECREASE',10,'line',NULL)")
        db.execSQL("INSERT INTO settlement_groups VALUES ('g','a',0,10000,2000,8000,'paid')")
        db.execSQL("INSERT INTO settlement_lines VALUES ('line','g','p',20,10,10,10000,1000)")
        db.execSQL("INSERT INTO central_stock_movements VALUES ('opening','p','INCREASE',50)")
        db.execSQL("INSERT INTO central_stock_movements VALUES ('deliver','p','DECREASE',20)")
        db.execSQL("INSERT INTO archive_batches VALUES ('b',3)")
        db.execSQL("INSERT INTO archive_memberships VALUES ('SETTLEMENT_GROUP','g','b')")
        db.execSQL("INSERT INTO archive_memberships VALUES ('CARD_MOVEMENT','sale','b')")
        db.execSQL("INSERT INTO archive_memberships VALUES ('FINANCIAL_ENTRY','paid','b')")
    }

    @Test fun consistentSettlementAndArchivePass() {
        fixture().use { db ->
            seed(db)
            verify(db)
        }
    }

    @Test fun rejectsCorruptSettlementQuantities() {
        fixture().use { db ->
            seed(db)
            db.execSQL("UPDATE settlement_lines SET soldQuantity = 11 WHERE id = 'line'")
            assertTrue(runCatching { verify(db) }.isFailure)
        }
    }

    @Test fun rejectsNegativeConsignment() {
        fixture().use { db ->
            db.execSQL("INSERT INTO card_movements VALUES ('bad','a','p','UNSOLD_RETURN','DECREASE',2,NULL,NULL)")
            assertTrue(runCatching { verify(db) }.isFailure)
        }
    }

    @Test fun rejectsDuplicateOrUnmatchedArchiveCount() {
        fixture().use { db ->
            seed(db)
            db.execSQL("UPDATE archive_batches SET recordCount = 5 WHERE id='b'")
            assertTrue(runCatching { verify(db) }.isFailure)
        }
    }

    @Test fun rejectsMismatchedFinancialReversal() {
        fixture().use { db ->
            seed(db)
            db.execSQL("INSERT INTO financial_entries VALUES ('reverse','a','RECEIVABLE_REVERSAL',999,'g','due')")
            assertTrue(runCatching { verify(db) }.isFailure)
        }
    }
}
