package com.najimwifi.app.backup

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Checks the actual SQLite file, not only claimed archive metadata. */
data class VerifiedDatabase(
    val sha256: String,
    val sizeBytes: Long,
    val version: Int,
    val identity: String,
    val tableCounts: Map<String, Long>
)

class RestoreValidator {
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(block)
                if (n == -1) break
                digest.update(block, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkCursor(db: SQLiteDatabase, sql: String): List<String> =
        db.rawQuery(sql, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty()) }
        }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use {
            require(it.moveToFirst()); it.getLong(0)
        }

    /** Open a detached plaintext snapshot, never the active Room database. */
    fun verify(file: File, manifest: BackupManifest? = null): VerifiedDatabase {
        require(file.isFile && file.length() in 1..BackupArchiveFormat.MAX_DATABASE_BYTES) {
            "حجم قاعدة البيانات الاحتياطية غير صالح"
        }
        val result = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            require(checkCursor(db, "PRAGMA integrity_check") == listOf("ok")) {
                "فحص سلامة SQLite فشل"
            }
            require(checkCursor(db, "PRAGMA foreign_key_check").isEmpty()) {
                "علاقات الجداول في النسخة غير متسقة"
            }
            val version = db.rawQuery("PRAGMA user_version", null).use {
                require(it.moveToFirst()); it.getInt(0)
            }
            val identity = db.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id=42", null
            ).use { require(it.moveToFirst()); it.getString(0) }
            val tableNames = checkCursor(
                db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='room_master_table' AND name!='room_table_modification_log' ORDER BY name"
            ).toSet()
            require(tableNames == BackupArchiveFormat.tables.toSet()) {
                "مخطط القاعدة لا يطابق جداول نجم واي فاي"
            }
            val counts = BackupArchiveFormat.tables.associateWith { count(db, it) }
            checkBusinessInvariants(db)
            VerifiedDatabase(digest(file), file.length(), version, identity, counts)
        }
        if (manifest != null) {
            BackupArchiveFormat.validate(manifest)
            require(result.sha256 == manifest.databaseSha256 &&
                result.sizeBytes == manifest.databaseBytes &&
                result.version == manifest.schemaVersion &&
                result.identity == manifest.roomIdentityHash &&
                result.tableCounts == manifest.tableCounts) {
                "محتوى القاعدة لا يطابق وصف النسخة الاحتياطية الموثق"
            }
        }
        return result
    }

    /**
     * The identical invariant checks run on the detached backup AND on the
     * destination database before the restore transaction commits. No writes.
     * Each query yields one row only when a specific invariant is violated.
     */
    internal fun verifyLedgerQueries(hasViolation: (String) -> Boolean) {
        fun ensure(message: String, sql: String) {
            require(!hasViolation(sql.trimIndent())) { message }
        }

        ensure("النسخة تحتوي دينًا سالبًا", """
            SELECT customerId FROM financial_entries GROUP BY customerId
            HAVING SUM(CASE kind
                WHEN 'SETTLEMENT_RECEIVABLE' THEN amountMinor
                WHEN 'COLLECTION_REVERSAL' THEN amountMinor
                WHEN 'COLLECTION' THEN -amountMinor
                WHEN 'RECEIVABLE_REVERSAL' THEN -amountMinor ELSE 0 END) < 0 LIMIT 1
        """)
        ensure("النسخة تحتوي عهدة كروت سالبة", """
            SELECT customerId, packageId FROM card_movements
            GROUP BY customerId, packageId
            HAVING SUM(CASE effect
                WHEN 'INCREASE' THEN quantity
                WHEN 'DECREASE' THEN -quantity ELSE 0 END) < 0 LIMIT 1
        """)
        ensure("رصيد المخزون المركزي النهائي سالب", """
            SELECT packageId FROM central_stock_movements GROUP BY packageId
            HAVING SUM(CASE effect
                WHEN 'INCREASE' THEN quantity
                WHEN 'DECREASE' THEN -quantity ELSE 0 END) < 0 LIMIT 1
        """)
        ensure("مجموع التسويات لا يطابق بنودها", """
            SELECT g.id FROM settlement_groups g
            LEFT JOIN settlement_lines l ON l.settlementGroupId = g.id
            GROUP BY g.id HAVING COUNT(l.id) = 0
                OR COALESCE(SUM(l.amountDueMinor), 0) != g.totalAmountDueMinor LIMIT 1
        """)
        ensure("تعارض في رصيد محاسبة أو عدد الكروت في بند", """
            SELECT id FROM settlement_groups
            WHERE previousDebtMinor < 0 OR totalAmountDueMinor < 0
              OR singlePaymentMinor < 0 OR endingDebtMinor < 0
              OR previousDebtMinor + totalAmountDueMinor - singlePaymentMinor != endingDebtMinor
            UNION ALL
            SELECT id FROM settlement_lines
            WHERE stockBefore <= 0 OR stockRemaining < 0
              OR stockRemaining > stockBefore OR soldQuantity != stockBefore - stockRemaining
              OR amountDueMinor < 0
              OR amountDueMinor != soldQuantity * package_generalSettlementPriceMinor
            LIMIT 1
        """)
        ensure("قيود محاسبة لا تتطابق مع المستحق والدفعة الواحدة", """
            SELECT g.id FROM settlement_groups g
            LEFT JOIN financial_entries due ON due.settlementGroupId = g.id
                AND due.kind = 'SETTLEMENT_RECEIVABLE'
            LEFT JOIN financial_entries pay ON pay.settlementGroupId = g.id
                AND pay.kind = 'COLLECTION'
            WHERE (g.totalAmountDueMinor = 0 AND due.id IS NOT NULL)
               OR (g.totalAmountDueMinor > 0 AND
                  (due.id IS NULL OR due.amountMinor != g.totalAmountDueMinor
                   OR due.customerId != g.customerId))
               OR (g.singlePaymentMinor = 0 AND
                   (pay.id IS NOT NULL OR g.paymentEntryId IS NOT NULL))
               OR (g.singlePaymentMinor > 0 AND
                   (pay.id IS NULL OR pay.id != g.paymentEntryId
                    OR pay.amountMinor != g.singlePaymentMinor
                    OR pay.customerId != g.customerId))
            LIMIT 1
        """)
        ensure("حركات بيع المحاسبة لا تطابق بنودها", """
            SELECT l.id FROM settlement_lines l
            JOIN settlement_groups g ON g.id = l.settlementGroupId
            LEFT JOIN card_movements m ON m.settlementLineId = l.id
                AND m.kind = 'SETTLEMENT_SALE'
            WHERE (l.soldQuantity = 0 AND m.id IS NOT NULL)
               OR (l.soldQuantity > 0 AND (m.id IS NULL
                   OR m.quantity != l.soldQuantity OR m.packageId != l.packageId
                   OR m.customerId != g.customerId OR m.effect != 'DECREASE'))
            LIMIT 1
        """)
        ensure("قيد عكس مالي غير مطابق للقيد الأصلي", """
            SELECT rev.id FROM financial_entries rev
            LEFT JOIN financial_entries orig ON orig.id = rev.reversesEntryId
            WHERE rev.kind IN ('RECEIVABLE_REVERSAL','COLLECTION_REVERSAL')
              AND (orig.id IS NULL OR rev.amountMinor != orig.amountMinor
                   OR rev.customerId != orig.customerId
                   OR (rev.kind = 'RECEIVABLE_REVERSAL'
                       AND orig.kind != 'SETTLEMENT_RECEIVABLE')
                   OR (rev.kind = 'COLLECTION_REVERSAL'
                       AND orig.kind != 'COLLECTION'))
            LIMIT 1
        """)
        ensure("حركة تصحيح عهدة لا تطابق الحركة الأصلية", """
            SELECT rev.id FROM card_movements rev
            LEFT JOIN card_movements orig ON orig.id = rev.reversesMovementId
            WHERE rev.kind = 'CORRECTION'
              AND (orig.id IS NULL OR rev.quantity != orig.quantity
                  OR rev.customerId != orig.customerId
                  OR rev.packageId != orig.packageId
                  OR rev.effect = orig.effect
                  OR orig.kind NOT IN ('DELIVERY','UNSOLD_RETURN','SETTLEMENT_SALE'))
            LIMIT 1
        """)
        ensure("عدد سجلات دفعة الأرشيف غير مطابق", """
            SELECT b.id FROM archive_batches b
            LEFT JOIN archive_memberships m ON m.archiveBatchId = b.id
            GROUP BY b.id HAVING COUNT(m.recordId) != b.recordCount LIMIT 1
        """)
    }

    private fun checkBusinessInvariants(db: SQLiteDatabase) {
        verifyLedgerQueries { sql -> db.rawQuery(sql, null).use { it.moveToFirst() } }
    }

    /** Called on the live DB before ANY restore write. */
    fun ensureSameSchema(live: SupportSQLiteDatabase, incoming: VerifiedDatabase) {
        val version = live.query("PRAGMA user_version").use {
            require(it.moveToFirst()); it.getInt(0)
        }
        val identity = live.query(
            "SELECT identity_hash FROM room_master_table WHERE id=42"
        ).use { require(it.moveToFirst()); it.getString(0) }
        require(version == incoming.version && identity == incoming.identity) {
            "مخطط قاعدة النسخة مختلف؛ لا يمكن الاستعادة دون ترحيل مخطط معتمد"
        }
    }

    /** Used before commit while the restore transaction is still reversible. */
    fun verifyRestoredTransaction(db: SupportSQLiteDatabase, expected: VerifiedDatabase) {
        require(db.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }) {
            "الاستعادة أدت إلى علاقة مرجعية غير صالحة"
        }
        for ((table, count) in expected.tableCounts) {
            val current = db.query("SELECT COUNT(*) FROM \"$table\"").use {
                require(it.moveToFirst()); it.getLong(0)
            }
            require(current == count) { "عدد سجلات $table لا يطابق النسخة" }
        }
        verifyLedgerQueries { sql -> db.query(sql).use { it.moveToFirst() } }
    }
}
