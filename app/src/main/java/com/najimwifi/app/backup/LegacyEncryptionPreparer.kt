package com.najimwifi.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PREPARATION ONLY. Creates and verifies a separate SQLCipher candidate.
 * Does not install it, rename the live DB, change Room's open helper, or
 * schedule a cutover. An independent, verified .njbak file is mandatory.
 *
 * The current legacy DB stays usable and UNENCRYPTED until a separately
 * tested cold-start cutover protocol can re-validate the current data.
 */
class LegacyEncryptionPreparer(
    context: Context,
    private val database: NajimDatabase
) {
    private val app = context.applicationContext
    private val validator = RestoreValidator()
    private val importer = BackupImporter(app, validator)
    private val engine = EncryptedDatabaseSupport(app)

    private val folder: File get() = File(app.noBackupFilesDir, "najim_cipher_preparation")
    private val prepared: File get() = File(folder, "candidate.sqlcipher")
    private val keyFile: AtomicFile get() = AtomicFile(File(folder, "candidate.key"))

    data class PreparationResult(
        val backupId: String,
        val customerCount: Long,
        val ledgerCount: Long,
        val encryptedFileBytes: Long,
        val candidateSha256: String
    )

    fun isPrepared(): Boolean = prepared.isFile && keyFile.baseFile.isFile

    suspend fun prepare(backupUri: Uri, phrase: CharArray): PreparationResult =
        withContext(Dispatchers.IO) {
            require(engine.inspect().mode == EncryptedDatabaseSupport.Mode.LEGACY_PLAINTEXT) {
                "التحضير خاص بقواعد البيانات القديمة غير المشفرة فقط"
            }
            require(!isPrepared() && !prepared.exists() && !keyFile.baseFile.exists()) {
                "ملف التشفير التمهيدي موجود بالفعل؛ لا يُستبدل دون إلغائه صراحة"
            }
            require(folder.isDirectory || folder.mkdirs()) {
                "تعذر إنشاء مساحة التحضير الآمنة"
            }
            // Incomplete scratch files from an interrupted earlier attempt have
            // no effect on Room and must not accumulate on disk.
            folder.listFiles()?.filter {
                it.name.startsWith("legacy_current_") ||
                    it.name.startsWith("cipher_candidate_")
            }?.forEach { require(it.delete()) { "تعذر تنظيف ملف تحضير مؤقت" } }
            val opened = importer.openAndVerify(backupUri, phrase)
            val recent = File.createTempFile("legacy_current_", ".sqlite", folder)
            val encryptedTemp = File.createTempFile("cipher_candidate_", ".db", folder)
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                val live = database.openHelper.writableDatabase
                require(!live.inTransaction()) { "انتظر انتهاء العملية المحاسبية الجارية" }
                validator.ensureSameSchema(live, opened.verified)

                // VACUUM INTO supplies a consistent, independent SQLite snapshot.
                require(recent.delete()) { "تعذر إنشاء لقطة جديدة للفحص" }
                val free = folder.usableSpace
                val needed = Math.multiplyExact(
                    maxOf(opened.verified.sizeBytes, 1024L * 1024L), 4L
                )
                require(free > needed) { "المساحة المتاحة لا تكفي لتحضير التشفير" }
                live.execSQL("VACUUM INTO '${recent.absolutePath.sqlQuote()}'")
                val current = validator.verify(recent)
                require(current.version == opened.verified.version &&
                    current.identity == opened.verified.identity &&
                    current.tableCounts == opened.verified.tableCounts) {
                    "النسخة المحددة لا تطابق قاعدة التطبيق الحالية؛ أنشئ نسخة جديدة أولًا"
                }
                comparePlainDatabases(recent, opened.file)

                // SQLCipher is opened with the same BYTE ARRAY form used by
                // SupportOpenHelperFactory, avoiding ambiguous SQL passphrase escaping.
                require(encryptedTemp.delete()) { "تعذر تهيئة الملف المشفر المؤقت" }
                EncryptedDatabaseSupport.loadNativeLibrary()
                CipherDatabase.openDatabase(
                    encryptedTemp.absolutePath, secret, null,
                    CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY,
                    null
                ).use { target ->
                    target.execSQL(
                        "ATTACH DATABASE '${opened.file.absolutePath.sqlQuote()}' " +
                            "AS legacy_source KEY ''"
                    )
                    try {
                        target.rawQuery(
                            "SELECT sqlcipher_export('main','legacy_source')", null
                        ).use { cursor ->
                            require(cursor.moveToFirst()) { "فشل تصدير SQLCipher" }
                        }
                        target.execSQL("PRAGMA main.user_version = ${current.version}")
                        compareEncryptedToSource(target)
                    } finally {
                        target.execSQL("DETACH DATABASE legacy_source")
                    }
                }
                require(encryptedTemp.isFile && encryptedTemp.length() > 64L) {
                    "فشل إنشاء ملف قاعدة مشفر"
                }
                require(!hasPlaintextHeader(encryptedTemp)) {
                    "ملف التحضير ليس مشفرًا؛ رُفض حفظه"
                }
                CipherDatabase.openDatabase(
                    encryptedTemp.absolutePath, secret, null,
                    CipherDatabase.OPEN_READONLY, null
                ).use { db ->
                    db.rawQuery("PRAGMA cipher_integrity_check", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                            "فشل فحص سلامة قاعدة SQLCipher"
                        }
                    }
                    db.rawQuery("PRAGMA foreign_key_check", null).use {
                        require(!it.moveToFirst()) { "المرجعيات داخل الملف المشفر غير متسقة" }
                    }
                }
                require(!File(encryptedTemp.path + "-wal").exists() &&
                    !File(encryptedTemp.path + "-journal").exists()) {
                    "ملف التشفير له سجل كتابة غير مُدمج؛ أوقف التحضير"
                }
                val digest = sha256(encryptedTemp)
                // Staging files are never used automatically by Room.
                saveWrappedKey(secret)
                require(encryptedTemp.renameTo(prepared)) {
                    "تعذر اعتماد الملف التمهيدي المشفر"
                }
                PreparationResult(
                    backupId = opened.preview.manifest.backupId,
                    customerCount = current.tableCounts["customers"] ?: 0L,
                    ledgerCount = current.tableCounts["financial_entries"] ?: 0L,
                    encryptedFileBytes = prepared.length(),
                    candidateSha256 = digest
                )
            } catch (failure: Exception) {
                // Never touch the production database on any failure.
                encryptedTemp.delete()
                if (!prepared.exists()) keyFile.baseFile.delete()
                throw failure
            } finally {
                secret.fill(0)
                recent.delete()
                encryptedTemp.delete()
                opened.file.delete()
            }
        }

    /** Only deletes disposable staging, NEVER the active Room database. */
    suspend fun discardPreparedCandidate() = withContext(Dispatchers.IO) {
        require(engine.inspect().mode == EncryptedDatabaseSupport.Mode.LEGACY_PLAINTEXT) {
            "قاعدة التطبيق لم تعد قديمة؛ لن يُحذف أي ملف"
        }
        if (prepared.exists()) require(prepared.delete()) { "تعذر حذف الملف التمهيدي" }
        val record = keyFile.baseFile
        if (record.exists()) require(record.delete()) { "تعذر حذف مفتاح التحضير" }
        Unit
    }

    private fun comparePlainDatabases(actual: File, backedUp: File) {
        SQLiteDatabase.openDatabase(
            actual.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.execSQL("ATTACH DATABASE '${backedUp.absolutePath.sqlQuote()}' AS archived")
            try {
                BackupArchiveFormat.tables.forEach { table ->
                    val column = "\"$table\""
                    // EXCEPT compares every actual value, not just table counts.
                    for ((a, b) in listOf("main" to "archived", "archived" to "main")) {
                        db.rawQuery(
                            "SELECT EXISTS(SELECT * FROM $a.$column EXCEPT SELECT * FROM $b.$column)",
                            null
                        ).use { cursor ->
                            require(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                                "محتوى $table تغير منذ إنشاء النسخة؛ أنشئ نسخة أحدث"
                            }
                        }
                    }
                }
            } finally {
                db.execSQL("DETACH DATABASE archived")
            }
        }
    }

    private fun compareEncryptedToSource(db: CipherDatabase) {
        BackupArchiveFormat.tables.forEach { table ->
            val quoted = "\"$table\""
            for ((a, b) in listOf("main" to "legacy_source", "legacy_source" to "main")) {
                db.rawQuery(
                    "SELECT EXISTS(SELECT * FROM $a.$quoted EXCEPT SELECT * FROM $b.$quoted)",
                    null
                ).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "محتوى $table المشفر لا يطابق المصدر"
                    }
                }
            }
        }
        db.rawQuery(
            "SELECT (SELECT identity_hash FROM main.room_master_table WHERE id=42) = " +
                "(SELECT identity_hash FROM legacy_source.room_master_table WHERE id=42)", null
        ).use { cursor ->
            require(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "بصمة مخطط Room اختلفت بعد التحويل"
            }
        }
    }

    private fun saveWrappedKey(raw: ByteArray) {
        val alias = "najim_migration_candidate_wrap_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            ).apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = byteArrayOf(0x4e, 0x4a, 0x4d, 0x31) + cipher.iv + cipher.doFinal(raw)
        val output = keyFile.startWrite()
        try {
            output.write(bytes)
            keyFile.finishWrite(output)
        } catch (failure: Exception) {
            keyFile.failWrite(output)
            throw failure
        } finally {
            bytes.fill(0)
        }
    }

    private fun hasPlaintextHeader(file: File): Boolean =
        file.inputStream().use { input ->
            val bytes = ByteArray(16)
            input.read(bytes) == 16 &&
                bytes.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
        }

    private fun sha256(file: File): String {
        val digester = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digester.update(buffer, 0, size)
            }
        }
        return digester.digest().joinToString("") { "%02x".format(it) }
    }
}
