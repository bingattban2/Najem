package com.najimwifi.app.ui.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.settings.SettingsStore
import com.najimwifi.app.domain.service.DebtAlertOptions
import com.najimwifi.app.domain.service.DebtAlertPolicy
import com.najimwifi.app.domain.service.LocalAlert
import com.najimwifi.app.domain.service.LocalAlertKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Local-only alert coordination: no network calls, no ledger writes. */
class LocalAlertCoordinator(context: Context) {
    companion object {
        private const val WORK_NAME = "najim_local_financial_alerts_daily_v1"
        private const val CHANNEL_ID = "najim_financial_alerts_v1"
        private const val NOTIFICATION_ID = 11202
    }

    private val app = context.applicationContext

    suspend fun preview(options: DebtAlertOptions): List<LocalAlert> =
        withContext(Dispatchers.IO) {
            if (!options.anyAlertEnabled) return@withContext emptyList()
            val database = NajimDatabase.create(app)
            try {
                DebtAlertPolicy.evaluate(
                    database.financialDao().customerAlertSnapshots(),
                    options,
                    System.currentTimeMillis()
                )
            } finally {
                database.close()
            }
        }

    /** Only enqueue background checks for opt-in system notifications. */
    fun reconcile(options: DebtAlertOptions) {
        val work = WorkManager.getInstance(app)
        if (!options.anyAlertEnabled || !options.systemNotificationsEnabled) {
            work.cancelUniqueWork(WORK_NAME)
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
            return
        }
        val request = PeriodicWorkRequestBuilder<LocalFinancialAlertWorker>(
            1, TimeUnit.DAYS
        ).build()
        work.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun notificationsGranted(): Boolean {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
    }

    /** No customer name or financial amount leaks onto a lock-screen notification. */
    fun publishSummary(items: List<LocalAlert>) {
        if (!notificationsGranted()) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (items.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "تنبيهات نجم واي فاي", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "تنبيهات اختيارية للديون وتأخر المحاسبة" }
        )
        val debtCount = items.count { it.kind == LocalAlertKind.DEBT_THRESHOLD }
        val overdueCount = items.count { it.kind == LocalAlertKind.SETTLEMENT_DELAY }
        val summary = "ديون تجاوزت الحد: $debtCount | تأخر محاسبة: $overdueCount"
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                app, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notice = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("تنبيهات نجم واي فاي")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        manager.notify(NOTIFICATION_ID, notice)
    }

    class LocalFinancialAlertWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = try {
            val app = applicationContext
            val policy = SettingsStore(app).settings.first().alerts
            val alerts = LocalAlertCoordinator(app)
            if (policy.anyAlertEnabled && policy.systemNotificationsEnabled) {
                // OS permission can be revoked without changing app preferences.
                if (alerts.notificationsGranted()) {
                    alerts.publishSummary(alerts.preview(policy))
                }
            }
            Result.success()
        } catch (failure: Exception) {
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }
}
