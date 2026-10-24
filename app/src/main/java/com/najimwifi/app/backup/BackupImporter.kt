package com.najimwifi.app.backup

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import com.najimwifi.app.data.settings.PortableSettingsCodec
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode only into a private temporary location; NEVER modify the active DB here. */
data class PreparedRestore(
    val file: File,
    val preview: BackupPreview,
    val verified: VerifiedDatabase,
    val portableSettings: ByteArray?
)

class BackupImporter(
    private val context: Context,
    private val validator: RestoreValidator
) {
    suspend fun preview(source: Uri, recoveryPhrase: CharArray): BackupPreview =
        withContext(Dispatchers.IO) {
            openAndVerify(source, recoveryPhrase).let { prepared ->
                try { prepared.preview } finally { prepared.file.delete() }
            }
        }

    internal fun openAndVerify(source: Uri, phrase: CharArray): PreparedRestore {
        val stream = context.contentResolver.openInputStream(source)
            ?: error("تعذر فتح النسخة الاحتياطية")
        return stream.use { unpack(it, phrase) }
    }

    private fun unpack(source: InputStream, phrase: CharArray): PreparedRestore {
        val folder = File(context.noBackupFilesDir, "najim_backup_staging")
        require(folder.isDirectory || folder.mkdirs())
        val staging = File.createTempFile("import_", ".sqlite", folder)
        try {
            var settingsPayload: ByteArray? = null
            val manifest = BackupEncryption.decrypt(source, phrase) { plaintext ->
                val zip = ZipInputStream(plaintext)
                val first = zip.nextEntry
                    ?: error("وصف النسخة الاحتياطية غير موجود")
                require(first.name == BackupArchiveFormat.MANIFEST_ENTRY && !first.isDirectory) {
                    "أول جزء من النسخة غير صالح"
                }
                val manifestBytes = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    require(manifestBytes.size() + read <= BackupArchiveFormat.MAX_MANIFEST_BYTES) {
                        "وصف النسخة أكبر من الحد المسموح"
                    }
                    manifestBytes.write(buffer, 0, read)
                }
                val parsed = BackupArchiveFormat.readManifest(
                    ByteArrayInputStream(manifestBytes.toByteArray())
                )
                zip.closeEntry()
                val second = zip.nextEntry ?: error("قاعدة البيانات غير موجودة في النسخة")
                require(second.name == BackupArchiveFormat.DATABASE_ENTRY && !second.isDirectory) {
                    "اسم ملف قاعدة البيانات غير صالح"
                }
                var length = 0L
                FileOutputStream(staging).use { output ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read == -1) break
                        length = Math.addExact(length, read.toLong())
                        require(length <= BackupArchiveFormat.MAX_DATABASE_BYTES) {
                            "حجم قاعدة البيانات يتجاوز الحد المسموح"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                require(length == parsed.databaseBytes) {
                    "حجم ملف النسخة لا يطابق وصفه"
                }
                zip.closeEntry()
                if (parsed.formatVersion >= 2) {
                    val third = zip.nextEntry ?: error("إعدادات النسخة غير موجودة")
                    require(third.name == BackupArchiveFormat.PREFERENCES_ENTRY &&
                        !third.isDirectory) { "اسم جزء الإعدادات غير صالح" }
                    val settingsBuffer = ByteArrayOutputStream()
                    while (true) {
                        val read = zip.read(buffer)
                        if (read == -1) break
                        require(settingsBuffer.size() + read <= BackupArchiveFormat.MAX_PREFERENCES_BYTES) {
                            "إعدادات النسخة تتجاوز الحد المسموح"
                        }
                        settingsBuffer.write(buffer, 0, read)
                    }
                    val bytes = settingsBuffer.toByteArray()
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it) }
                    require(bytes.size == parsed.preferencesBytes && hash == parsed.preferencesSha256) {
                        "إعدادات النسخة لا تطابق بصمتها الموثقة"
                    }
                    PortableSettingsCodec.decode(bytes) // Validate BEFORE any DB write.
                    settingsPayload = bytes
                    zip.closeEntry()
                }
                require(zip.nextEntry == null) { "ملفات إضافية غير متوقعة في النسخة" }
                // Do not close ZIP here: BackupEncryption must consume ZIP's
                // central directory and verify its authenticated terminal tag.
                parsed
            }
            val verified = validator.verify(staging, manifest)
            return PreparedRestore(
                staging, BackupArchiveFormat.preview(manifest), verified, settingsPayload
            )
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }
}
