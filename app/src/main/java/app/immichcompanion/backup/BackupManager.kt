package app.immichcompanion.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.immichcompanion.data.BackupSettings
import app.immichcompanion.data.LastBackup
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.immich.BulkUploadCheckItem
import app.immichcompanion.immich.ImmichApi
import app.immichcompanion.immich.ImmichApiException
import app.immichcompanion.immich.UploadMetadata
import app.immichcompanion.tailscale.TailscaleNotConnectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant

/** The current or last backup run. */
data class BackupProgress(
    val running: Boolean = false,
    /** Files this run looked at, out of [total] that weren't backed up yet. */
    val processed: Int = 0,
    val total: Int = 0,
    val uploaded: Int = 0,
    /** Files the server already had. */
    val duplicates: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val finishedAt: Instant? = null,
    val error: String? = null,
    /** The backup can't continue until the user does something (sign in, grant access). */
    val needsUser: Boolean = false,
)

/** How many of the selected files are on the server. */
data class BackupSummary(val selected: Int, val backedUp: Int) {
    val remaining get() = selected - backedUp
}

enum class BackupOutcome { Done, Retry }

/** Backs up the selected albums to the signed-in Immich account. */
class BackupManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: ImmichApi,
) {
    private val scanner = MediaScanner(context)
    private val files = MediaFiles(context)
    private val db = BackupDb(context)
    private val scheduler = BackupScheduler(context)
    private val runLock = Mutex()

    private val _progress = MutableStateFlow(BackupProgress())
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    /** Brings the scheduled work in line with the saved settings, without interrupting a running backup. */
    suspend fun init() {
        settings.lastBackup.first()?.let { last ->
            _progress.compareAndSet(
                BackupProgress(),
                BackupProgress(
                    uploaded = last.uploaded, duplicates = last.duplicates, failed = last.failed,
                    finishedAt = Instant.ofEpochMilli(last.finishedAtMs), error = last.error, needsUser = last.needsUser,
                ),
            )
        }
        scheduler.apply(settings.backup.first(), replace = false)
    }

    suspend fun updateSettings(backup: BackupSettings) {
        settings.setBackup(backup)
        scheduler.apply(backup, replace = true)
    }

    fun backUpNow() = scheduler.runNow()

    internal fun rearmTrigger(backup: BackupSettings) = scheduler.rearmTrigger(backup)

    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) { scanner.albums() }

    suspend fun summary(): BackupSummary? = withContext(Dispatchers.IO) {
        val session = settings.currentSession() ?: return@withContext null
        val media = scanner.media(settings.backup.first().albums)
        val records = db.load(accountKey(session.apiBaseUrl, session.userEmail))
        BackupSummary(media.size, media.count { records[it.id]?.isDone(it) == true })
    }

    /** Local MediaStore IDs of backed-up files, by Immich asset ID. */
    suspend fun localIdsByAssetId(): Map<String, Long> = withContext(Dispatchers.IO) {
        val session = settings.currentSession() ?: return@withContext emptyMap()
        db.uploaded(accountKey(session.apiBaseUrl, session.userEmail))
    }

    /** Runs one backup. Concurrent calls wait for each other, so a file is never uploaded twice. */
    suspend fun run(): BackupOutcome = runLock.withLock {
        withContext(Dispatchers.IO) {
            _progress.value = BackupProgress(running = true)
            val outcome = try {
                backUp()
                BackupOutcome.Done
            } catch (e: ImmichApiException) {
                if (e.code == 401) fail("Your Immich session expired. Sign in again.", needsUser = true)
                else fail(e.message)
                if (e.code >= 500) BackupOutcome.Retry else BackupOutcome.Done
            } catch (e: TailscaleNotConnectedException) {
                fail(e.message, needsUser = e.needsUser)
                // Retrying soon won't help when it's waiting for the user; the periodic run checks again.
                if (e.needsUser) BackupOutcome.Done else BackupOutcome.Retry
            } catch (e: IOException) {
                Log.w(TAG, "backup stopped", e)
                fail(e.message ?: "Network error")
                BackupOutcome.Retry
            } catch (e: SecurityException) {
                fail("Allow access to photos and videos to back them up", needsUser = true)
                BackupOutcome.Done
            }
            val done = _progress.updateAndGet { it.copy(running = false, current = null, finishedAt = Instant.now()) }
            settings.setLastBackup(
                LastBackup(
                    done.finishedAt!!.toEpochMilli(), done.uploaded, done.duplicates, done.failed,
                    done.error, done.needsUser,
                ),
            )
            outcome
        }
    }

    private fun fail(message: String?, needsUser: Boolean = false) =
        _progress.update { it.copy(error = message ?: "Backup failed", needsUser = needsUser) }

    private suspend fun backUp() {
        val session = settings.currentSession() ?: return fail("Sign in to Immich to back up", needsUser = true)
        // Without access MediaStore just returns nothing, which would look like a finished backup.
        if (!hasMediaAccess()) return fail("Allow access to photos and videos to back them up", needsUser = true)
        val backup = settings.backup.first()
        val apiUrl = session.apiBaseUrl.toHttpUrl()
        val account = accountKey(session.apiBaseUrl, session.userEmail)
        val records = db.load(account)

        // Oldest first, so an interrupted backup leaves no gaps in the timeline.
        val pending = scanner.media(backup.albums)
            .filter { records[it.id]?.isDone(it) != true }
            .sortedBy { it.takenAt }
        _progress.update { it.copy(total = pending.size) }

        for (batch in pending.chunked(BATCH_SIZE)) {
            // Hash the batch, reusing checksums of files that haven't changed.
            val hashed = batch.mapNotNull { media ->
                val known = records[media.id]?.takeIf { it.matches(media) }?.checksum
                val checksum = known ?: try {
                    _progress.update { it.copy(current = media.name) }
                    files.sha1(media).also {
                        db.save(account, BackupRecord(media.id, media.modifiedAt, media.size, it))
                    }
                } catch (e: FileNotFoundException) {
                    skipLocal(media, e)
                    return@mapNotNull null
                }
                media to checksum
            }
            if (hashed.isEmpty()) continue

            val results = api.bulkUploadCheck(
                apiUrl, session.accessToken,
                hashed.map { (media, checksum) -> BulkUploadCheckItem(media.id.toString(), checksum) },
            ).associateBy { it.id }

            for ((media, checksum) in hashed) {
                val result = results[media.id.toString()]
                val record = BackupRecord(media.id, media.modifiedAt, media.size, checksum)
                when {
                    result?.action == "reject" && result.reason == "duplicate" -> {
                        db.save(account, record.copy(remoteId = result.assetId ?: "duplicate"))
                        _progress.update { it.copy(processed = it.processed + 1, duplicates = it.duplicates + 1) }
                    }
                    result?.action == "reject" -> {
                        db.save(account, record.copy(skipReason = result.reason ?: "rejected"))
                        _progress.update { it.copy(processed = it.processed + 1, failed = it.failed + 1) }
                    }
                    else -> upload(apiUrl, session.accessToken, account, media, record)
                }
            }
        }
    }

    private suspend fun upload(
        apiUrl: HttpUrl,
        token: String,
        account: String,
        media: LocalMedia,
        record: BackupRecord,
    ) {
        _progress.update { it.copy(current = media.name) }
        val meta = UploadMetadata(
            filename = media.name,
            fileCreatedAt = Instant.ofEpochMilli(media.takenAt).toString(),
            fileModifiedAt = Instant.ofEpochMilli(media.modifiedAt).toString(),
            isFavorite = media.isFavorite,
            durationMs = media.durationMs.takeIf { media.isVideo && it > 0 },
            checksum = record.checksum,
        )
        try {
            val resp = api.upload(apiUrl, token, meta, files.requestBody(media))
            db.save(account, record.copy(remoteId = resp.id))
            _progress.update {
                if (resp.status == "duplicate") it.copy(processed = it.processed + 1, duplicates = it.duplicates + 1)
                else it.copy(processed = it.processed + 1, uploaded = it.uploaded + 1)
            }
        } catch (e: ImmichApiException) {
            // The server refused this file (e.g. a format it can't read); carry on with the rest.
            if (e.code == 401 || e.code >= 500) throw e
            Log.w(TAG, "upload of ${media.name} rejected", e)
            db.save(account, record.copy(error = e.message))
            _progress.update { it.copy(processed = it.processed + 1, failed = it.failed + 1) }
        } catch (e: FileNotFoundException) {
            skipLocal(media, e)
        }
    }

    /** The file went away or can't be read; try again next run. */
    private fun skipLocal(media: LocalMedia, e: Exception) {
        Log.w(TAG, "couldn't read ${media.name}", e)
        _progress.update { it.copy(processed = it.processed + 1, failed = it.failed + 1) }
    }

    private fun hasMediaAccess() = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun accountKey(apiBaseUrl: String, email: String) = "$apiBaseUrl|$email"

    private companion object {
        const val TAG = "BackupManager"
        const val BATCH_SIZE = 50
    }
}
