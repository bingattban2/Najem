package com.najimwifi.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Manages the device-bound key used for NEW Room databases.
 *
 * Pre-existing unencrypted databases remain on their original SQLite driver
 * until a separate, verified, backup-first migration has been completed.
 * Never delete, overwrite, or silently convert an existing database here.
 *
 * This key is deliberately device-bound. The portable encrypted backup in
 * PHASE 10 must use an independent recovery-phrase-derived key instead.
 */
class EncryptedDatabaseSupport(context: Context) {
    companion object {
        const val DATABASE_NAME = "najim_wifi.db"
        private const val KEY_ALIAS = "najim_room_wrap_v1"
        private const val KEY_RECORD = "najim_room_wrapped_key_v1.bin"
        private const val PASSPHRASE_BYTES = 32
        private const val IV_BYTES = 12
        private val FORMAT = byteArrayOf(0x4e, 0x4a, 0x44, 0x31) // NJD1
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        @Synchronized
        fun loadNativeLibrary() {
            System.loadLibrary("sqlcipher")
        }
    }

    enum class Mode { NEW_DATABASE, ENCRYPTED, LEGACY_PLAINTEXT }

    data class Status(
        val mode: Mode,
        val description: String,
        val protectedAtRest: Boolean
    )

    private val app = context.applicationContext
    private val databaseFile: File get() = app.getDatabasePath(DATABASE_NAME)
    private val keyFile = AtomicFile(File(app.noBackupFilesDir, KEY_RECORD))
    private val keyRecord: File get() = keyFile.baseFile

    private fun isPlaintextDatabase(file: File): Boolean {
        if (!file.exists()) return false
        require(file.isFile && file.length() >= SQLITE_HEADER.size) {
            "ملف قاعدة البيانات غير صالح؛ لن يتم استبداله أو مسحه"
        }
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                require(read > 0) { "تعذر قراءة ترويسة قاعدة البيانات" }
                offset += read
            }
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    @Synchronized
    fun inspect(): Status {
        val database = databaseFile
        if (!database.exists()) {
            // An orphan WAL indicates an interrupted/incomplete recovery.
            require(!File(database.path + "-wal").exists() &&
                !File(database.path + "-journal").exists()) {
                "ملفات استرجاع SQLite موجودة دون القاعدة الأصلية"
            }
            return Status(
                Mode.NEW_DATABASE,
                "قاعدة جديدة: سيُنشأ ملف مشفر عند أول فتح",
                true
            )
        }
        val plain = isPlaintextDatabase(database)
        if (plain) {
            require(!keyRecord.exists()) {
                "تعارض بين مفتاح تشفير وملف SQLite عادي؛ لم يُعدل أي ملف"
            }
            return Status(
                Mode.LEGACY_PLAINTEXT,
                "قاعدة قديمة غير مشفرة؛ تحتاج نسخة احتياطية وترحيلًا مجرّبًا",
                false
            )
        }
        require(keyRecord.exists()) {
            "ملف مشفر بلا مفتاح الجهاز الأصلي؛ لا يمكن فتحه أو استبداله"
        }
        return Status(Mode.ENCRYPTED, "قاعدة مشفرة بواسطة SQLCipher", true)
    }

    /** Null means preserve existing plaintext Room database exactly. */
    @Synchronized
    fun openHelperFactoryOrNull(): SupportSQLiteOpenHelper.Factory? {
        if (inspect().mode == Mode.LEGACY_PLAINTEXT) return null
        loadNativeLibrary()
        val passphrase = readOrCreatePassphrase()
        // SupportOpenHelperFactory retains this array for future Room opens.
        // Do not clear the array before Room has finished using the factory.
        return SupportOpenHelperFactory(passphrase)
    }

    /** Only the cold-start cutover may install an already-verified candidate key. */
    @Synchronized
    fun installPreparedPassphrase(raw: ByteArray) {
        require(raw.size == PASSPHRASE_BYTES) { "طول مفتاح الترحيل غير صحيح" }
        require(databaseFile.exists() && isPlaintextDatabase(databaseFile)) {
            "يجب أن تبقى قاعدة SQLite الأصلية موجودة قبل اعتماد المفتاح"
        }
        require(!keyRecord.exists()) { "مفتاح إنتاج سابق موجود؛ أوقف الترحيل" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, findWrappingKey() ?: createWrappingKey())
        val sealed = FORMAT + c.iv + c.doFinal(raw)
        val out = keyFile.startWrite()
        try {
            out.write(sealed)
            keyFile.finishWrite(out)
        } catch (failure: Exception) {
            keyFile.failWrite(out)
            throw failure
        } finally {
            sealed.fill(0)
        }
    }

    /** For cutover recovery only: never create another key for an existing DB. */
    @Synchronized
    fun installedPassphraseOrNull(): ByteArray? =
        if (keyRecord.exists()) readOrCreatePassphrase() else null

    /** Invoked after restoring the original plaintext DB, never on SQLCipher. */
    @Synchronized
    fun removeInstalledPassphraseForLegacyRollback() {
        require(databaseFile.isFile && isPlaintextDatabase(databaseFile)) {
            "لا يمكن إسقاط مفتاح التشفير إلا بعد استرجاع SQLite الأصلي"
        }
        if (keyRecord.exists()) require(keyRecord.delete()) {
            "تعذر إزالة سجل مفتاح الترحيل بعد التراجع"
        }
    }

    private fun findWrappingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun createWrappingKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
    }.generateKey()

    private fun readOrCreatePassphrase(): ByteArray {
        val wrapped = try {
            keyFile.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        }
        if (wrapped != null) {
            require(wrapped.size >= FORMAT.size + IV_BYTES + 16 &&
                wrapped.copyOfRange(0, FORMAT.size).contentEquals(FORMAT)) {
                "سجل مفتاح قاعدة البيانات غير سليم"
            }
            val key = findWrappingKey()
                ?: error("مفتاح Keystore غير متاح؛ لن يتم استبدال القاعدة")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = wrapped.copyOfRange(FORMAT.size, FORMAT.size + IV_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val raw = cipher.doFinal(wrapped, FORMAT.size + IV_BYTES,
                wrapped.size - FORMAT.size - IV_BYTES)
            require(raw.size == PASSPHRASE_BYTES) { "طول مفتاح قاعدة البيانات غير صحيح" }
            return raw
        }
        require(!databaseFile.exists()) {
            "قاعدة بيانات موجودة دون مفتاحها؛ لن تُنشأ قاعدة بديلة"
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, findWrappingKey() ?: createWrappingKey())
        val record = FORMAT + cipher.iv + cipher.doFinal(passphrase)
        val stream = keyFile.startWrite()
        try {
            stream.write(record)
            keyFile.finishWrite(stream)
        } catch (failure: Exception) {
            keyFile.failWrite(stream)
            passphrase.fill(0)
            throw failure
        }
        return passphrase
    }
}
