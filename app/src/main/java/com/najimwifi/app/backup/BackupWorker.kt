package com.najimwifi.app.backup

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.najimwifi.app.data.di.AppContainer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Fresh consent grant each run; no OAuth access token or phrase in WorkManager Data. */
class BackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scheduler = BackupScheduler(applicationContext)
        if (!scheduler.status().enabled) return Result.success()
        var phrase: CharArray? = null
        var archive: File? = null
        return try {
            phrase = scheduler.scheduledPhrase()
            val token = DriveAuthorization(applicationContext).tokenWithoutUi()
            val dir = File(applicationContext.cacheDir, "scheduled_backups")
                .apply { require(isDirectory || mkdirs()) }
            val buffer = File.createTempFile("pending_", ".njbak", dir)
            archive = buffer
            val manager = AppContainer(applicationContext).localBackupManager
            manager.createBackup(Uri.fromFile(buffer), requireNotNull(phrase))
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now())
            val name = "Najim_${stamp}_${UUID.randomUUID().toString().take(8)}.njbak"
            val client = DriveBackupClient(applicationContext)
            client.upload(token, buffer, name)
            // Read the latest owner consent *after* upload. Never delete by default.
            val policy = scheduler.status()
            val outcome = if (BackupRetentionPolicy.mayDeleteOldVersions(
                    policy.enabled, policy.allowCleanup
                )) {
                runCatching { client.prune(token, policy.keep) }.fold(
                    onSuccess = { "نُظّفت $it إصدارات قديمة وفق موافقة المالك" },
                    onFailure = { "تعذر تنظيف الإصدارات القديمة؛ الرفع نفسه ناجح" }
                )
            } else {
                "لم تُحذف أي نسخة قديمة"
            }
            scheduler.recordStatus("آخر رفع ناجح: $name؛ $outcome")
            Result.success()
        } catch (e: Exception) {
            val issue = e.message ?: "تعذر النسخ المجدول"
            scheduler.recordStatus("آخر محاولة فشلت: $issue")
            if (issue.startsWith("AUTH_REQUIRED") || issue.contains("مفتاح الجدولة")) {
                Result.failure()
            } else if (runAttemptCount < 4 && e is IOException) {
                Result.retry()
            } else {
                Result.failure()
            }
        } finally {
            phrase?.fill('\u0000')
            archive?.delete()
        }
    }
}
