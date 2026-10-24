package com.najimwifi.app.backup

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No Google account, no cloud access and no plaintext external backup. */
data class RestoreOutcome(
    val restoredBackupId: String,
    val restoredRecords: Map<String, Long>,
    val encryptedRescuePath: String,
    val restartRequired: Boolean = true
)

class LocalBackupManager(context: Context, private val database: NajimDatabase) {
    private val app = context.applicationContext
    private val validator = RestoreValidator()
    private val exporter = BackupExporter(app, database, validator)
    private val importer = BackupImporter(app, validator)
    private val settingsStore = SettingsStore(app)
    private val mutex = Mutex()

    suspend fun createBackup(destination: Uri, recoveryPhrase: CharArray): BackupPreview =
        mutex.withLock { exporter.export(destination, recoveryPhrase) }

    suspend fun inspectBackup(source: Uri, recoveryPhrase: CharArray): BackupPreview =
        mutex.withLock { importer.preview(source, recoveryPhrase) }

    /**
     * Must follow a user-visible preview and a fresh explicit confirmation.
     * Data changes occur in ONE SQLite transaction, even for SQLCipher.
     * DB schema, Keystore and authentication credentials are not replaced.
     */
    suspend fun restoreBackup(
        source: Uri,
        recoveryPhrase: CharArray,
        approvedPreview: BackupPreview,
        explicitConfirmation: String
    ): RestoreOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(explicitConfirmation == "استعادة") {
                "يتطلب استبدال البيانات موافقة صريحة بعد المعاينة"
            }
            val prepared = importer.openAndVerify(source, recoveryPhrase)
            try {
                require(prepared.preview == approvedPreview) {
                    "تغيّر ملف النسخة بعد المعاينة؛ أعد معاينته قبل الاستعادة"
                }
                val live = database.openHelper.writableDatabase
                validator.ensureSameSchema(live, prepared.verified)
                // Saved before committing any database or DataStore changes.
                val originalSettings = settingsStore.portableSnapshot()
                require(!live.inTransaction()) { "انتظر انتهاء العملية المحاسبية الجارية" }

                // Rescue is itself a full, phrase-encrypted backup, not a raw DB.
                // It is saved BEFORE any destructive SQL is attempted.
                val rescue = File(
                    File(app.noBackupFilesDir, "najim_restore_rescue").apply {
                        require(isDirectory || mkdirs())
                    },
                    "rescue_${UUID.randomUUID()}.njbak"
                )
                try {
                    FileOutputStream(rescue).use { output ->
                        exporter.exportTo(output, recoveryPhrase)
                        output.fd.sync()
                    }
                    require(rescue.length() > 48L) { "تعذر إنشاء نسخة الإنقاذ" }
                } catch (failure: Exception) {
                    rescue.delete()
                    throw failure
                }

                // Emptying/repopulating ordinary Room tables is atomic in
                // SQLite. Unlike replacing files, it retains SQLCipher-at-rest
                // encryption on the DESTINATION device and its own Keystore key.
                // Two different persistence engines cannot share one SQLite
                // transaction. If DataStore rejects the restored preferences,
                // compensate by restoring the verified encrypted rescue DB.
                // Cancellation is postponed until this critical block finishes.
                withContext(NonCancellable + Dispatchers.IO) {
                    applyToExistingDatabase(live, prepared.file, prepared.verified)
                    if (prepared.portableSettings != null) {
                        try {
                            settingsStore.restorePortable(prepared.portableSettings)
                        } catch (settingsFailure: Exception) {
                            try {
                                val original = importer.openAndVerify(
                                    Uri.fromFile(rescue), recoveryPhrase
                                )
                                try {
                                    validator.ensureSameSchema(live, original.verified)
                                    applyToExistingDatabase(
                                        live, original.file, original.verified
                                    )
                                } finally {
                                    original.file.delete()
                                }
                                settingsStore.restorePortable(originalSettings)
                            } catch (recoveryFailure: Exception) {
                                settingsFailure.addSuppressed(recoveryFailure)
                                throw IllegalStateException(
                                    "تعذرت استعادة الإعدادات والتراجع التلقائي؛ " +
                                        "احتفظ بنسخة الإنقاذ ولا تتابع إدخال بيانات جديدة: " +
                                        rescue.absolutePath,
                                    settingsFailure
                                )
                            }
                            throw IllegalStateException(
                                "تعذر حفظ إعدادات النسخة، فتراجع البرنامج عن " +
                                    "استعادة قاعدة البيانات إلى نسختها الأصلية",
                                settingsFailure
                            )
                        }
                    }
                }
                RestoreOutcome(
                    restoredBackupId = prepared.preview.manifest.backupId,
                    restoredRecords = prepared.verified.tableCounts,
                    encryptedRescuePath = rescue.absolutePath
                )
            } finally {
                // Source DB plaintext is only ever stored in a private scratch
                // file; never leave decrypted staging files after import.
                prepared.file.delete()
            }
        }
    }

    private fun applyToExistingDatabase(
        db: SupportSQLiteDatabase,
        snapshot: File,
        verified: VerifiedDatabase
    ) {
        // Only the application creates the temporary path; no external SQL identifiers.
        val cipherMode = EncryptedDatabaseSupport(app).inspect().mode ==
            EncryptedDatabaseSupport.Mode.ENCRYPTED
        val attach = if (cipherMode) {
            "ATTACH DATABASE '${snapshot.absolutePath.sqlQuote()}' AS incoming KEY ''"
        } else {
            "ATTACH DATABASE '${snapshot.absolutePath.sqlQuote()}' AS incoming"
        }

        db.execSQL(attach)
        try {
            validator.ensureSameSchema(db, verified)
            // Defend against a crafted backup with a copied Room identity hash
            // but incompatible field ordering/types. Never execute its DDL.
            for (table in BackupArchiveFormat.tables) {
                fun structure(schema: String): List<String> =
                    db.query("PRAGMA $schema.table_info('$table')").use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add((1..5).joinToString("|") { i -> cursor.getString(i).orEmpty() })
                            }
                        }
                    }
                require(structure("main") == structure("incoming")) {
                    "بنية أعمدة $table غير متوافقة مع إصدار البرنامج"
                }
            }
            val originalFk = db.query("PRAGMA foreign_keys").use {
                require(it.moveToFirst()); it.getInt(0) != 0
            }
            // FK checks are deferred until ALL dependent, self-referencing
            // records have been replaced. The final FK check is mandatory.
            if (originalFk) db.execSQL("PRAGMA foreign_keys=OFF")
            try {
                val disabled = db.query("PRAGMA foreign_keys").use {
                    require(it.moveToFirst()); it.getInt(0) == 0
                }
                require(disabled) { "لم يمكن تعليق القيود مؤقتًا لأجل الاستعادة الذرية" }
                db.beginTransaction()
                try {
                    for (table in BackupArchiveFormat.tables) {
                        db.execSQL("DELETE FROM main.\"$table\"")
                    }
                    for (table in BackupArchiveFormat.tables) {
                        db.execSQL(
                            "INSERT INTO main.\"$table\" " +
                                "SELECT * FROM incoming.\"$table\""
                        )
                    }
                    validator.verifyRestoredTransaction(db, verified)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction() // rolls back ALL changes on failure
                }
            } finally {
                if (originalFk) db.execSQL("PRAGMA foreign_keys=ON")
            }
        } finally {
            db.execSQL("DETACH DATABASE incoming")
        }
    }
}
