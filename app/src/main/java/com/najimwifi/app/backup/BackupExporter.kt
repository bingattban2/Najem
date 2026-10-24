package com.najimwifi.app.backup

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Consistent SQLite snapshot -> validated portable encrypted archive. */
class BackupExporter(
    private val context: Context,
    private val database: NajimDatabase,
    private val validator: RestoreValidator
) {
    suspend fun export(destination: Uri, recoveryPhrase: CharArray): BackupPreview =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("تعذر فتح ملف وجهة النسخة الاحتياطية")
            stream.use { exportTo(it, recoveryPhrase) }
        }

    internal suspend fun exportTo(output: OutputStream, recoveryPhrase: CharArray): BackupPreview {
        // DataStore settings are captured before any archive bytes are emitted.
        // They contain neither authentication data nor a scheduled recovery phrase.
        val settings = SettingsStore(context).portableSnapshot()
        val snapshot = createPlainSnapshot()
        try {
            val verified = validator.verify(snapshot)
            val manifest = BackupArchiveFormat.newManifest(
                System.currentTimeMillis(), verified.sizeBytes, verified.sha256,
                verified.version, verified.identity, verified.tableCounts,
                preferencesBytes = settings.size,
                preferencesSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(settings).joinToString("") { "%02x".format(it) }
            )
            BackupEncryption.encrypt(output, recoveryPhrase) { encrypted ->
                ZipOutputStream(encrypted).use { zip ->
                    zip.putNextEntry(ZipEntry(BackupArchiveFormat.MANIFEST_ENTRY))
                    BackupArchiveFormat.writeManifest(zip, manifest)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BackupArchiveFormat.DATABASE_ENTRY))
                    snapshot.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BackupArchiveFormat.PREFERENCES_ENTRY))
                    zip.write(settings)
                    zip.closeEntry()
                }
            }
            return BackupArchiveFormat.preview(manifest)
        } finally {
            snapshot.delete()
        }
    }

    /**
     * For plaintext SQLite, VACUUM INTO gives a consistent point-in-time copy.
     * For SQLCipher, export to an attached plaintext database while holding an
     * SQLite write transaction on the source connection, then validate it.
     */
    private fun createPlainSnapshot(): File {
        val folder = File(context.noBackupFilesDir, "najim_backup_staging")
        require(folder.isDirectory || folder.mkdirs())
        val snapshot = File.createTempFile("export_", ".sqlite", folder)
        check(snapshot.delete()) { "تعذر تجهيز ملف اللقطة" }
        val db = database.openHelper.writableDatabase
        val mode = EncryptedDatabaseSupport(context).inspect().mode
        try {
            if (mode == EncryptedDatabaseSupport.Mode.LEGACY_PLAINTEXT) {
                db.execSQL("VACUUM INTO '${snapshot.absolutePath.sqlQuote()}'")
            } else {
                // SQLCipher ATTACH KEY '' creates an unencrypted SQLite database.
                db.execSQL(
                    "ATTACH DATABASE '${snapshot.absolutePath.sqlQuote()}' AS portable KEY ''"
                )
                try {
                    db.beginTransaction()
                    try {
                        db.query("SELECT sqlcipher_export('portable')").use {
                            check(it.moveToFirst()) { "SQLCipher لم يصدر نسخة" }
                        }
                        val version = db.query("PRAGMA main.user_version").use {
                            check(it.moveToFirst()); it.getInt(0)
                        }
                        db.execSQL("PRAGMA portable.user_version = $version")
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                } finally {
                    db.execSQL("DETACH DATABASE portable")
                }
            }
            require(snapshot.isFile && snapshot.length() > 0L) {
                "فشل إنشاء لقطة قاعدة البيانات"
            }
            return snapshot
        } catch (error: Exception) {
            snapshot.delete()
            throw error
        }
    }
}

/** SQL input is a private system-created path, never supplied by the user. */
internal fun String.sqlQuote(): String = replace("'", "''")
