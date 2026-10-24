package com.najimwifi.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * Explicitly armed cold-process conversion of a fully verified legacy database.
 * The original live Room connection is NEVER converted or swapped while open.
 * All paths are app-private; a separate device-key-encrypted rescue is retained.
 *
 * IMPORTANT: Device testing of abrupt-process-death and SQLite/SQLCipher open
 * is mandatory before permitting this feature on valuable production data.
 */
class LegacyEncryptionCutover(context: Context) {
    private val app = context.applicationContext
    private val cipher = EncryptedDatabaseSupport(app)
    private val folder = File(app.noBackupFilesDir, "najim_cipher_preparation")
    private val candidate = File(folder, "candidate.sqlcipher")
    private val candidateKey = AtomicFile(File(folder, "candidate.key"))
    private val request = AtomicFile(File(folder, "cutover.request"))
    private val rescue = File(folder, "legacy_rescue.aesgcm")
    private val old = File(folder, "legacy-original.sqlite")
    private val live get() = app.getDatabasePath(EncryptedDatabaseSupport.DATABASE_NAME)
    private val rescueAlias = "najim_legacy_rescue_v1"

    companion object {
        /** Conservative in-process gate: only a completely fresh process can swap. */
        @Volatile private var roomWasRequested = false
        fun noteRoomRequested() { roomWasRequested = true }
        /** Cheap no-I/O-database startup gate: real recovery still runs if armed. */
        fun hasScheduledCutover(context: Context): Boolean =
            File(context.noBackupFilesDir, "najim_cipher_preparation/cutover.request").isFile

        fun assertNoScheduledCutover(context: Context) {
            check(!hasScheduledCutover(context)) {
                "يوجد تحويل قاعدة بيانات مجدول؛ أغلق التطبيق بالكامل وأعد فتحه أولًا"
            }
        }
    }

    data class Outcome(val converted: Boolean, val note: String? = null)

    fun hasPendingRequest() = request.baseFile.isFile
    fun hasUnusedRescue(): Boolean = rescue.isFile && !hasPendingRequest() &&
        live.isFile && isLegacy(live)

    /** Scheduling does not close Room, alter keys, or touch the live DB. */
    suspend fun schedule(): Unit = withContext(Dispatchers.IO) {
        require(cipher.inspect().mode == EncryptedDatabaseSupport.Mode.LEGACY_PLAINTEXT) {
            "القاعدة غير مؤهلة للتحويل من SQLite القديم"
        }
        require(candidate.isFile && candidateKey.baseFile.isFile) {
            "حضّر قاعدة SQLCipher أولًا مع التحقق من نسخة .njbak"
        }
        require(!old.exists() && !rescue.exists() && !hasPendingRequest()) {
            "توجد عملية تحويل أو نسخة إنقاذ سابقة؛ يجب مراجعتها أولًا"
        }
        val fingerprint = sha256(candidate)
        require(fingerprint.length == 64)
        writeAtomic(request, "NJ-CUTOVER-1\n$fingerprint\n".toByteArray(Charsets.US_ASCII))
    }

    /** Cancel ONLY an untouched scheduled request while original is still intact. */
    suspend fun cancelScheduled(): Unit = withContext(Dispatchers.IO) {
        require(live.isFile && isLegacy(live) && !old.exists()) {
            "لا يمكن إلغاء تحويل بدأ بالفعل بهذه الطريقة"
        }
        require(request.baseFile.delete() || !request.baseFile.exists()) {
            "تعذر إلغاء طلب التحويل"
        }
    }

    /** Invoked before composing UI and before any app Room access. */
    suspend fun performOnColdStart(): Outcome = withContext(Dispatchers.IO) {
        if (!hasPendingRequest()) return@withContext Outcome(false)
        if (roomWasRequested) return@withContext Outcome(
            false,
            "لم يبدأ التحويل: توجد جلسة قاعدة بيانات في هذه العملية. " +
                "أغلق التطبيق إغلاقًا كاملًا ثم افتحه من جديد."
        )
        require(folder.isDirectory)
        RandomAccessFile(File(folder, "cutover.lock"), "rw").use { handle ->
            handle.channel.use { channel ->
                channel.lock().use {
                    try {
                        migrateOrRecover()
                    } catch (failure: Exception) {
                        // Pre-swap failures are safe to cancel only if the original
                        // plaintext file is still present and no old-file move began.
                        if (live.isFile && isLegacy(live) && !old.exists()) {
                            cipher.removeInstalledPassphraseForLegacyRollback()
                            check(request.baseFile.delete() || !request.baseFile.exists())
                            Outcome(false, "لم ينفذ تحويل القاعدة: ${failure.message}. " +
                                "البيانات الأصلية محفوظة. راجع نسخة التحضير قبل إعادة المحاولة.")
                        } else throw failure  // An ambiguous state MUST block Room.
                    }
                }
            }
        }
    }

    private fun migrateOrRecover(): Outcome {
        val fingerprint = readRequest()
        // An interrupted rename is recovered before any Room connection.
        if (old.exists() || (live.exists() && !isLegacy(live))) {
            return recoverInterrupted(fingerprint)
        }
        if (!live.isFile || !isLegacy(live)) {
            error("تعذر تحديد القاعدة الأصلية؛ لم تُنشأ قاعدة بديلة")
        }
        require(candidate.isFile && candidateKey.baseFile.isFile) {
            "ملف التحضير أو مفتاحه غير موجود؛ لا يمكن إجراء تحويل"
        }
        require(sha256(candidate) == fingerprint) {
            "ملف التحضير تغير بعد موافقتك؛ أُلغيت محاولة التحويل"
        }
        val secret = unwrapCandidateKey()
        val fresh = File.createTempFile("cutover_recent_", ".sqlite", folder)
        try {
            // A fresh process and an exclusive checkpoint are mandatory.
            // If a worker still holds a live connection, fail instead of swapping.
            checkpointLegacy()
            require(noActiveSidecars(live)) {
                "يوجد سجل SQLite نشط؛ لا يمكن استبدال القاعدة مع احتمالية كتابة جارية"
            }
            require(fresh.delete())
            SQLiteDatabase.openDatabase(
                live.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                db.execSQL("VACUUM INTO '${fresh.absolutePath.escapeSql()}'")
            }
            require(noActiveSidecars(live)) { "تغيرت حالة قاعدة البيانات خلال التحقق" }
            val verified = RestoreValidator().verify(fresh)
            validateCipher(candidate, secret, fresh)
            require(sha256(candidate) == fingerprint) { "تغير ملف SQLCipher التمهيدي" }
            // An encrypted local rescue remains available independent of Google.
            val free = folder.usableSpace
            require(free > Math.multiplyExact(maxOf(fresh.length(), live.length()), 4L)) {
                "المساحة الحرة غير كافية للتحويل والإنقاذ"
            }
            encryptRescue(fresh)
            verifyRescueMatches(fresh)
            // Protect the candidate key under the production Keystore alias first.
            // An interrupted pre-swap leaves plaintext live, recoverable on next boot.
            cipher.installPreparedPassphrase(secret)
            check(live.renameTo(old)) { "تعذر حفظ القاعدة الأصلية قبل التبديل" }
            try {
                check(candidate.renameTo(live)) { "تعذر وضع قاعدة SQLCipher مكان القديمة" }
                require(sha256(live) == fingerprint)
                validateCipher(live, secret, fresh)
                require(!isLegacy(live))
                // Commit: keep only the AES-GCM rescue; never retain plaintext old.
                check(old.delete()) { "تعذر إزالة ملف SQLite القديم بعد التحقق" }
                check(request.baseFile.delete()) { "تعذر إنهاء حالة التحويل" }
                return Outcome(true, "اكتمل تحويل قاعدة البيانات إلى SQLCipher مع نسخة إنقاذ مشفرة")
            } catch (failure: Exception) {
                rollbackFromOld()
                throw IllegalStateException(
                    "فشل التبديل وتمت محاولة إعادة القاعدة القديمة دون حذف سجلاتها", failure
                )
            }
        } finally {
            secret.fill(0)
            fresh.delete()
        }
    }

    /** Recovery after unexpected process death between key write and commit. */
    private fun recoverInterrupted(fingerprint: String): Outcome {
        // New database fully moved: commit only if its fingerprint still matches.
        if (live.exists() && !isLegacy(live)) {
            val secret = cipher.installedPassphraseOrNull()
            val valid = secret != null && try {
                require(sha256(live) == fingerprint) { "الملف المشفر تغيّر أثناء الاعتماد" }
                validateCipher(live, secret, null)
                true
            } catch (_: Exception) { false }
            secret?.fill(0)
            if (valid) {
                if (old.exists()) check(old.delete()) { "تعذر إزالة القاعدة القديمة" }
                check(request.baseFile.delete()) { "تعذر تثبيت نتيجة التحويل" }
                return Outcome(true, "اكتمل التحقق من تحويل توقف أثناء تشغيل سابق")
            }
            // A different encrypted database is never silently overwritten.
            val suspect = File(folder, "failed-cipher.sqlite")
            check(!suspect.exists() && live.renameTo(suspect)) {
                "تعذر حفظ الملف المشفر غير المؤكد للتحقيق"
            }
            if (old.exists()) {
                rollbackFromOld()
            } else {
                // Crash after removal of plaintext original: restore the
                // authenticated encrypted rescue; never create an empty DB.
                restoreEncryptedRescueToLegacy()
            }
            return Outcome(false, "أُعيدت قاعدة البيانات القديمة بعد فشل التحقق من الملف المشفر")
        }
        if (old.exists()) {
            require(!live.exists()) { "ملفان أصليان متعارضان؛ مطلوب تدخل يدوي" }
            rollbackFromOld()
            return Outcome(false, "أُعيدت قاعدة البيانات القديمة بعد توقف التبديل")
        }
        // A crash after recording the production key but before renaming.
        if (live.isFile && isLegacy(live)) {
            cipher.removeInstalledPassphraseForLegacyRollback()
            // Re-run validation rather than silently approving stale data.
            return migrateOrRecoverAfterReset()
        }
        error("حالة استعادة غير متوقعة؛ لن يفتح التطبيق قاعدة بديلة")
    }

    private fun migrateOrRecoverAfterReset(): Outcome {
        // A single fresh attempt is safe after the partial key-write is undone.
        return migrateOrRecover()
    }

    private fun rollbackFromOld() {
        if (!old.exists()) error("نسخة SQLite الأصلية غير متاحة للتراجع")
        check(!live.exists()) { "قاعدة الهدف موجودة؛ لا يمكن الكتابة فوقها" }
        check(old.renameTo(live)) { "تعذر إرجاع ملف SQLite الأصلي" }
        cipher.removeInstalledPassphraseForLegacyRollback()
        check(request.baseFile.delete()) { "تعذر تنظيف طلب تحويل فاشل" }
    }

    private fun checkpointLegacy() {
        SQLiteDatabase.openDatabase(
            live.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                if (c.moveToFirst()) require(c.getInt(0) == 0) {
                    "قاعدة البيانات مشغولة؛ أُوقف التحويل لحماية سجل WAL"
                }
            }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { c ->
                require(c.moveToFirst() && c.getString(0).equals("delete", true)) {
                    "تعذر إغلاق وضع WAL قبل التبديل"
                }
            }
        }
    }

    private fun noActiveSidecars(file: File) =
        listOf("-wal", "-journal", "-shm").none { File(file.path + it).exists() }

    private fun validateCipher(dbFile: File, secret: ByteArray, source: File?) {
        EncryptedDatabaseSupport.loadNativeLibrary()
        CipherDatabase.openDatabase(dbFile.absolutePath, secret, null,
            CipherDatabase.OPEN_READWRITE, null).use { db ->
            db.rawQuery("PRAGMA cipher_integrity_check", null).use { c ->
                require(c.moveToFirst() && c.getString(0) == "ok") {
                    "فشل فحص تشفير قاعدة SQLCipher"
                }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                require(!c.moveToFirst()) { "خطأ علاقات في SQLCipher" }
            }
            val version = db.rawQuery("PRAGMA user_version", null).use {
                require(it.moveToFirst()); it.getInt(0)
            }
            require(version == 1) { "إصدار مخطط القاعدة تغير" }
            if (source != null) {
                db.execSQL("ATTACH DATABASE '${source.absolutePath.escapeSql()}' AS source_data KEY ''")
                try {
                    BackupArchiveFormat.tables.forEach { table ->
                        val quoted = "\"$table\""
                        for ((a, b) in listOf("main" to "source_data", "source_data" to "main")) {
                            db.rawQuery(
                                "SELECT EXISTS(SELECT * FROM $a.$quoted EXCEPT " +
                                    "SELECT * FROM $b.$quoted)", null
                            ).use { c ->
                                require(c.moveToFirst() && c.getInt(0) == 0) {
                                    "النسخة المشفرة لا تطابق $table من القاعدة الحالية"
                                }
                            }
                        }
                    }
                    db.rawQuery(
                        "SELECT (SELECT identity_hash FROM main.room_master_table WHERE id=42) = " +
                            "(SELECT identity_hash FROM source_data.room_master_table WHERE id=42)", null
                    ).use { c ->
                        require(c.moveToFirst() && c.getInt(0) == 1) {
                            "بصمة Room لم تتطابق"
                        }
                    }
                } finally {
                    db.execSQL("DETACH DATABASE source_data")
                }
            }
        }
    }

    private fun unwrapCandidateKey(): ByteArray {
        val content = candidateKey.openRead().use { it.readBytes() }
        require(content.size == 4 + 12 + 48 &&
            content.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4e, 0x4a, 0x4d, 0x31))) {
            "سجل مفتاح التحضير غير صالح"
        }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey("najim_migration_candidate_wrap_v1", null) as? SecretKey
            ?: error("مفتاح التحضير غير موجود على هذا الجهاز")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, content.copyOfRange(4, 16)))
        return c.doFinal(content, 16, content.size - 16).also {
            require(it.size == 32) { "طول مفتاح التحضير غير صحيح" }
        }
    }

    private fun rescueKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(rescueAlias, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(KeyGenParameterSpec.Builder(rescueAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build())
                }.generateKey()
    }

    private fun encryptRescue(source: File) {
        if (rescue.exists()) {
            // Reuse interrupted rescue only if it authenticates and matches the
            // latest logical snapshot byte-for-byte.
            verifyRescueMatches(source)
            return
        }
        val temp = File(folder, "rescue.tmp")
        // An abandoned partial ciphertext cannot be a trusted rescue.
        if (temp.exists()) require(temp.delete()) { "تعذر تنظيف إنقاذ جزئي" }
        try {
            MigrationRescueCipher.encrypt(source, temp, rescueKey())
            check(temp.renameTo(rescue)) { "تعذر حفظ الإنقاذ المشفر" }
        } finally { temp.delete() }
    }

    private fun decryptRescueTo(destination: File) =
        MigrationRescueCipher.decrypt(rescue, destination, rescueKey())

    private fun verifyRescueMatches(source: File) {
        val verify = File.createTempFile("rescue_verify_", ".sqlite", folder)
        try {
            decryptRescueTo(verify)
            require(sha256(verify) == sha256(source)) { "تعذر التحقق من إنقاذ القاعدة" }
        } finally { verify.delete() }
    }

    private fun restoreEncryptedRescueToLegacy() {
        check(!live.exists() && rescue.isFile) { "لا يوجد موقع آمن لاستعادة الإنقاذ" }
        val plain = File.createTempFile("restored_legacy_", ".sqlite", folder)
        try {
            decryptRescueTo(plain)
            RestoreValidator().verify(plain)
            check(isLegacy(plain)) { "ملف الإنقاذ ليس SQLite أصليًا" }
            check(plain.renameTo(live)) { "تعذر إعادة قاعدة الإنقاذ إلى مسارها" }
            cipher.removeInstalledPassphraseForLegacyRollback()
            check(request.baseFile.delete()) { "تعذر إنهاء عملية التراجع" }
        } finally { plain.delete() }
    }

    /** Discard an unused device-bound rescue only while the legacy DB remains. */
    suspend fun discardUnusedRescue() = withContext(Dispatchers.IO) {
        require(!hasPendingRequest() && !old.exists() &&
            live.isFile && isLegacy(live)) {
            "لا يمكن حذف الإنقاذ أثناء تحويل قائم أو بعد التشفير"
        }
        if (rescue.exists()) require(rescue.delete()) { "تعذر حذف إنقاذ التحويل الملغى" }
    }

    private fun readRequest(): String {
        val content = request.openRead().use { it.readBytes() }
            .toString(Charsets.US_ASCII).trim().lines()
        require(content.size == 2 && content[0] == "NJ-CUTOVER-1" &&
            content[1].matches(Regex("[0-9a-f]{64}"))) {
            "بيانات طلب الترحيل غير صالحة"
        }
        return content[1]
    }

    private fun isLegacy(file: File): Boolean =
        file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == 16 &&
                header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
        }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }

    private fun String.escapeSql(): String = replace("'", "''")
}
