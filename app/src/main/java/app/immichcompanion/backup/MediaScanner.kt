package app.immichcompanion.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns

/** A photo or video on the device, as MediaStore describes it. */
data class LocalMedia(
    val id: Long,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val size: Long,
    /** Milliseconds since the epoch. */
    val takenAt: Long,
    val modifiedAt: Long,
    val durationMs: Long,
    val isFavorite: Boolean,
    val bucketId: String,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

/** A MediaStore bucket (a folder such as Camera or Screenshots). */
data class Album(val id: String, val name: String, val count: Int, val cover: Uri)

/** Reads photos and videos from MediaStore. Needs READ_MEDIA_IMAGES/VIDEO (or a partial selection). */
class MediaScanner(private val context: Context) {
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun albums(): List<Album> {
        val byBucket = LinkedHashMap<String, Album>()
        query(selection = null, args = null) { media, bucketName ->
            val a = byBucket[media.bucketId]
            byBucket[media.bucketId] = a?.copy(count = a.count + 1)
                ?: Album(media.bucketId, bucketName ?: "Unknown", 1, media.uri)
        }
        return byBucket.values.sortedByDescending { it.count }
    }

    /** All photos and videos in [bucketIds], newest first. */
    fun media(bucketIds: Set<String>): List<LocalMedia> {
        if (bucketIds.isEmpty()) return emptyList()
        val out = ArrayList<LocalMedia>()
        query(
            selection = "${FileColumns.BUCKET_ID} IN (${bucketIds.joinToString(",") { "?" }})",
            args = bucketIds.toTypedArray(),
        ) { media, _ -> out += media }
        return out
    }

    private fun query(selection: String?, args: Array<String>?, row: (LocalMedia, String?) -> Unit) {
        val typeFilter = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${FileColumns.IS_PENDING} = 0 AND ${FileColumns.IS_TRASHED} = 0"
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.MIME_TYPE, FileColumns.MEDIA_TYPE,
            FileColumns.SIZE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DATE_MODIFIED,
            FileColumns.DURATION, FileColumns.IS_FAVORITE, FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME,
        )
        context.contentResolver.query(
            collection,
            projection,
            if (selection == null) typeFilter else "$typeFilter AND $selection",
            args,
            "${FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(3) == FileColumns.MEDIA_TYPE_VIDEO
                val added = c.getLong(6) * 1000
                val modified = c.getLong(7) * 1000
                val media = LocalMedia(
                    id = id,
                    name = c.getString(1) ?: "media-$id",
                    mimeType = c.getString(2) ?: "application/octet-stream",
                    isVideo = isVideo,
                    size = c.getLong(4),
                    takenAt = c.getLong(5).takeIf { it > 0 } ?: added.takeIf { it > 0 } ?: modified,
                    modifiedAt = modified.takeIf { it > 0 } ?: added,
                    durationMs = c.getLong(8),
                    isFavorite = c.getInt(9) == 1,
                    bucketId = c.getString(10) ?: "",
                )
                row(media, c.getString(11))
            }
        }
    }
}
