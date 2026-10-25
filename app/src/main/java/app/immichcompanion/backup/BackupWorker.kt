package app.immichcompanion.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.immichcompanion.ImmichCompanionApp
import app.immichcompanion.ui.MainActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Runs one backup with a progress notification. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as ImmichCompanionApp).container
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val backup = container.backup
        val isTrigger = inputData.getBoolean(KEY_TRIGGER, false)
        // Android refuses a foreground service when the work started in the background;
        // the backup still runs, just without the service.
        val foreground = runCatching { setForeground(foregroundInfo(BackupProgress(running = true))) }
            .onFailure { Log.i(TAG, "running without a foreground service: $it") }
            .isSuccess
        val outcome = coroutineScope {
            val notifier = launch {
                backup.progress.collect { p ->
                    if (!p.running) return@collect
                    if (foreground) runCatching { setForeground(foregroundInfo(p)) }
                    else runCatching { notifications.notify(NOTIFICATION_ID, notification(p)) }
                }
            }
            backup.run().also { notifier.cancel() }
        }
        notifications.cancel(NOTIFICATION_ID)
        if (isTrigger) {
            // A content trigger fires once. Retries come from the periodic work.
            backup.rearmTrigger(container.settings.backup.first())
            return Result.success()
        }
        return if (outcome == BackupOutcome.Retry) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo(BackupProgress(running = true))

    private fun foregroundInfo(p: BackupProgress) =
        ForegroundInfo(NOTIFICATION_ID, notification(p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(p: BackupProgress): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up to Immich")
            .setContentText(
                if (p.total == 0) "Looking for new photos and videos…"
                else "${p.processed} of ${p.total}" + (p.current?.let { " · $it" } ?: ""),
            )
            .setProgress(p.total, p.processed, p.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) != null) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of photo and video backups"
            },
        )
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "BackupWorker"
        private const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 1001
    }
}
