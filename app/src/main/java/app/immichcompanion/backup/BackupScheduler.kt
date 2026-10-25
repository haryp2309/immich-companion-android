package app.immichcompanion.backup

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.immichcompanion.data.BackupSettings
import java.time.Duration

/**
 * Schedules backups with WorkManager:
 * - a content trigger that runs shortly after new photos or videos appear,
 * - a periodic run as a fallback (and to retry after errors),
 * - a one-off run when the user taps "Back up now".
 */
class BackupScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)

    /** [replace] restarts scheduled work with new settings; otherwise it's kept as is. */
    fun apply(backup: BackupSettings, replace: Boolean) {
        if (!backup.enabled || backup.albums.isEmpty()) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(TRIGGER)
            return
        }
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(Duration.ofHours(6))
                .setConstraints(constraints(backup).build())
                .build(),
        )
        work.enqueueUniqueWork(TRIGGER, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, trigger(backup))
    }

    /** A content trigger fires once; the worker calls this to wait for the next change. */
    fun rearmTrigger(backup: BackupSettings) {
        if (!backup.enabled || backup.albums.isEmpty()) return
        work.enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, trigger(backup))
    }

    fun runNow() {
        work.enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    private fun trigger(backup: BackupSettings) = OneTimeWorkRequestBuilder<BackupWorker>()
        .setConstraints(
            constraints(backup)
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                // Wait for a burst of new photos to settle.
                .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
                .setTriggerContentMaxDelay(Duration.ofMinutes(5))
                .build(),
        )
        .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to true))
        .build()

    private fun constraints(backup: BackupSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (backup.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(backup.chargingOnly)

    private companion object {
        const val PERIODIC = "backup-periodic"
        const val TRIGGER = "backup-trigger"
        const val NOW = "backup-now"
    }
}
