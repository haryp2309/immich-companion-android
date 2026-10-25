package app.immichcompanion.library

import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.immich.ImmichApi
import app.immichcompanion.immich.SyncLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.OffsetDateTime

/**
 * Keeps [LibraryDb] in step with the server through Immich's sync stream, the same
 * incremental protocol the official app uses. The server remembers (per login
 * session) which changes were acknowledged, so each sync only gets what's new.
 */
class LibrarySync(
    private val context: Context,
    private val settings: SettingsStore,
    private val api: ImmichApi,
    val db: LibraryDb,
) {
    private val lock = Mutex()

    @Volatile
    private var lastSyncAt = 0L

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Syncs unless that happened in the last [maxAgeMs]. */
    suspend fun syncIfStale(maxAgeMs: Long = 30_000) {
        if (SystemClock.elapsedRealtime() - lastSyncAt > maxAgeMs || lastSyncAt == 0L) sync()
    }

    /** Fetches changes from the server. Returns whether anything changed. */
    suspend fun sync(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            _syncing.value = true
            try {
                syncLocked().also { changed ->
                    lastSyncAt = SystemClock.elapsedRealtime()
                    if (changed) notifyPicker()
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun syncLocked(): Boolean {
        val session = settings.currentSession()
        // The server keeps the sync position per login session, so a new login starts a new copy.
        val account = session?.let { "${it.apiBaseUrl}|${it.userEmail}|${it.accessToken.hashCode()}" }
        if (session == null) {
            if (db.account == null && db.collectionId != "empty") return false
            db.reset(null)
            return true
        }
        // A new account (or a fresh database) needs everything again, so ask the server to start over.
        var reset = db.account != account
        if (reset) db.reset(account)

        val apiUrl = session.apiBaseUrl.toHttpUrl()
        var changed = reset
        while (true) {
            val batch = ArrayList<SyncLine>(BATCH_SIZE)
            var serverReset = false
            suspend fun flush() {
                if (batch.isEmpty()) return
                // Marker lines (complete, ack) carry no data; don't start a new generation for them.
                val hasData = batch.any { it.type != "SyncCompleteV1" && it.type != "SyncAckV1" }
                if (hasData) db.write { batch.forEach { apply(it) } }
                // Only acknowledge what's stored. The server keeps the last ack of each type.
                val acks = batch.asReversed().map { it.ack }.distinctBy { it.substringBefore('|') }
                api.syncAck(apiUrl, session.accessToken, acks)
                if (hasData) changed = true
                batch.clear()
            }
            api.syncStream(apiUrl, session.accessToken, SYNC_TYPES, reset) { line ->
                if (line.type == "SyncResetV1") {
                    serverReset = true
                    return@syncStream
                }
                batch += line
                if (batch.size >= BATCH_SIZE) flush()
            }
            flush()
            if (!serverReset) break
            // The server dropped our sync position; rebuild from scratch.
            Log.i(TAG, "server asked for a full resync")
            db.reset(account)
            api.syncAck(apiUrl, session.accessToken, listOf("SyncResetV1|reset"))
            reset = true
            changed = true
        }
        return changed
    }

    private fun LibraryDb.Writer.apply(line: SyncLine) {
        val d = line.data
        when (line.type) {
            "AssetV2" -> {
                val asset = d.toRemoteAsset()
                if (asset != null) upsert(asset) else delete(d.str("id") ?: return)
            }
            "AssetDeleteV1" -> delete(d.str("assetId") ?: return)
            "AssetExifV1" -> d.long("fileSizeInByte")?.let { setSize(d.str("assetId") ?: return, it) }
            "AlbumV2" -> upsertAlbum(d.str("id") ?: return, d.str("name") ?: "")
            "AlbumDeleteV1" -> deleteAlbum(d.str("albumId") ?: return)
            "AlbumToAssetV1", "AlbumToAssetBackfillV1" -> addToAlbum(d.str("albumId") ?: return, d.str("assetId") ?: return)
            "AlbumToAssetDeleteV1" -> removeFromAlbum(d.str("albumId") ?: return, d.str("assetId") ?: return)
        }
    }

    private fun notifyPicker() {
        // Throws unless this app is the picker's current cloud provider; nothing to tell then.
        runCatching { MediaStore.notifyCloudMediaChangedEvent(context.contentResolver, CLOUD_AUTHORITY, db.collectionId) }
    }

    companion object {
        private const val TAG = "LibrarySync"
        private const val BATCH_SIZE = 500
        const val CLOUD_AUTHORITY = "app.immichcompanion.cloudpicker"
        private val SYNC_TYPES = listOf("AssetsV2", "AlbumsV2", "AlbumToAssetsV1", "AssetExifsV1")

        /**
         * The picker-visible asset in a sync line, or null when the asset shouldn't be
         * shown (trashed, hidden, locked, or not a photo or video).
         */
        internal fun JsonObject.toRemoteAsset(): RemoteAsset? {
            val id = str("id") ?: return null
            val type = str("type")
            if (type != "IMAGE" && type != "VIDEO") return null
            if (str("deletedAt") != null) return null
            if (str("visibility") !in setOf("timeline", "archive")) return null
            val fileName = str("originalFileName") ?: id
            val isVideo = type == "VIDEO"
            val takenAt = listOf("fileCreatedAt", "localDateTime", "createdAt")
                .firstNotNullOfOrNull { key -> str(key)?.let { runCatching { parseTime(it) }.getOrNull() } } ?: 0
            return RemoteAsset(
                id = id,
                fileName = fileName,
                mimeType = mimeTypeFor(fileName, isVideo),
                isVideo = isVideo,
                takenAt = takenAt,
                durationMs = long("duration") ?: 0,
                isFavorite = (this["isFavorite"] as? JsonPrimitive)?.booleanOrNull ?: false,
                width = (this["width"] as? JsonPrimitive)?.intOrNull,
                height = (this["height"] as? JsonPrimitive)?.intOrNull,
            )
        }

        private fun parseTime(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

        private val MIME_TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
            "dng" to "image/x-adobe-dng", "bmp" to "image/bmp", "tif" to "image/tiff", "tiff" to "image/tiff",
            "jxl" to "image/jxl",
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp",
            "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo", "mts" to "video/mp2t",
        )

        /** Immich doesn't sync MIME types, so they come from the file extension. */
        fun mimeTypeFor(fileName: String, isVideo: Boolean): String =
            MIME_TYPES[fileName.substringAfterLast('.', "").lowercase()]
                ?.takeIf { it.startsWith(if (isVideo) "video/" else "image/") }
                ?: if (isVideo) "video/mp4" else "image/jpeg"
    }
}
