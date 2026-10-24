package com.najimwifi.app.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Daily unattended backups require a separate, explicitly authorized secret. */
class BackupScheduler(context: Context) {
    companion object {
        private const val WORK = "najim_wifi_encrypted_drive_daily"
        private const val ALIAS = "najim_scheduled_backup_phrase_v1"
        private const val FILE = "najim_scheduled_backup_phrase_v1.bin"
        private const val RECORD_VERSION = 1
    }
    private val app = context.applicationContext
    private val record = AtomicFile(File(app.noBackupFilesDir, FILE))
    private val prefs = app.getSharedPreferences("najim_backup_job_status_v1", Context.MODE_PRIVATE)

    data class JobStatus(
        val enabled: Boolean, val result: String, val at: Long,
        val weekly: Boolean, val keep: Int, val allowCleanup: Boolean
    )

    fun status(): JobStatus = JobStatus(
        prefs.getBoolean("enabled", false),
        prefs.getString("result", "لم تُنفذ نسخة مجدولة بعد").orEmpty(),
        prefs.getLong("at", 0L),
        prefs.getBoolean("weekly", false),
        prefs.getInt("keep", 10).coerceIn(2, 20),
        // No implicit consent when migrating schedules from earlier builds.
        prefs.getBoolean("cleanup_approved", false)
    )

    fun recordStatus(result: String) {
        prefs.edit().putString("result", result.take(240))
            .putLong("at", System.currentTimeMillis()).apply()
    }

    fun configureHistory(keep: Int) {
        require(keep in listOf(3, 5, 10, 20))
        prefs.edit().putInt("keep", keep).apply()
    }

    fun enable(phrase: CharArray, weekly: Boolean, keep: Int, allowCleanup: Boolean = false) {
        require(phrase.size in 12..256) { "عبارة استرداد من 12 حرفًا على الأقل مطلوبة" }
        configureHistory(keep)
        saveSecret(phrase)
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = PeriodicWorkRequestBuilder<BackupWorker>(if (weekly) 7 else 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.UPDATE, work
            )
            prefs.edit().putBoolean("enabled", true)
                .putBoolean("weekly", weekly)
                .putBoolean("cleanup_approved", allowCleanup).apply()
            recordStatus(
                "النسخ ${if (weekly) "الأسبوعي" else "اليومي"} مفعّل؛ " +
                    if (allowCleanup) "تم السماح بالاحتفاظ بآخر $keep نسخ" 
                    else "لن تُحذف أي نسخة قديمة تلقائيًا"
            )
        } catch (e: Exception) {
            disable()
            throw e
        }
    }

    /** Withdrawing consent stops future cleanup immediately, even for a running schedule. */
    fun revokeCleanupConsent() {
        prefs.edit().putBoolean("cleanup_approved", false).commit().also { saved ->
            check(saved) { "تعذر حفظ إلغاء الموافقة على حذف النسخ" }
        }
    }

    fun disable() {
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        record.baseFile.delete()
        prefs.edit().putBoolean("enabled", false)
            .putBoolean("cleanup_approved", false).apply()
        recordStatus("النسخ المجدول متوقف؛ النسخ السابقة في Drive لم تُحذف")
    }

    /** Read for a single worker execution; always wipe returned chars after use. */
    fun scheduledPhrase(): CharArray {
        require(status().enabled) { "الجدولة غير مفعلة" }
        val bytes = record.openRead().use { it.readBytes() }
        require(bytes.size > 1 + 12 + 16 && bytes[0].toInt() == RECORD_VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("مفتاح الجدولة مفقود"),
            GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val raw = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try { String(raw, Charsets.UTF_8).toCharArray() } finally { raw.fill(0) }
    }

    private fun saveSecret(phrase: CharArray) {
        val plaintext = String(phrase).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            val encoded = byteArrayOf(RECORD_VERSION.toByte()) + cipher.iv +
                cipher.doFinal(plaintext)
            val out = record.startWrite()
            try {
                out.write(encoded)
                record.finishWrite(out)
            } catch (e: Exception) { record.failWrite(out); throw e }
        } finally { plaintext.fill(0) }
    }

    private fun existingKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}
