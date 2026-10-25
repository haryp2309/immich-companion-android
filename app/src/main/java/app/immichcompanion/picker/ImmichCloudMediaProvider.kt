package app.immichcompanion.picker

import android.content.ContentResolver
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract.AlbumColumns
import android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID
import android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID
import android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE
import android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN
import android.provider.CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL
import android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION
import android.provider.CloudMediaProviderContract.MediaCollectionInfo
import android.provider.CloudMediaProviderContract.MediaColumns
import android.provider.MediaStore
import android.util.Log
import app.immichcompanion.AppContainer
import app.immichcompanion.ImmichCompanionApp
import app.immichcompanion.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Makes the Immich library a cloud media source of the Android photo picker. The
 * picker copies the list of media into its own database (incrementally, by sync
 * generation) and asks for thumbnails and files as needed.
 *
 * The picker only uses allowlisted providers; see scripts/enable-cloud-picker.sh.
 */
class ImmichCloudMediaProvider : CloudMediaProvider() {
    // Providers are created before Application.onCreate, so look the container up lazily.
    private val container: AppContainer by lazy { (context!!.applicationContext as ImmichCompanionApp).container }

    override fun onCreate() = true

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        // Answer from the local copy right away; a sync in the background tells the picker if it changed.
        container.scope.launch { runCatching { container.library.syncIfStale() } }
        val db = container.library.db
        val session = runBlocking { container.settings.currentSession() }
        return Bundle().apply {
            putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, db.collectionId)
            putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, db.generation)
            session?.let { putString(MediaCollectionInfo.ACCOUNT_NAME, it.userEmail) }
            putParcelable(
                MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT,
                Intent(context, MainActivity::class.java),
            )
        }
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        val db = container.library.db
        val page = Page(extras)
        val assets = db.assets(
            afterGeneration = extras.getLong(EXTRA_SYNC_GENERATION, 0),
            albumId = extras.getString(EXTRA_ALBUM_ID),
            limit = page.limit,
            offset = page.offset,
        )
        // Lets the picker show a backed-up photo once, as the local copy.
        val localIds = runBlocking { container.backup.localIdsByAssetId() }
        val cursor = MatrixCursor(
            arrayOf(
                MediaColumns.ID, MediaColumns.DATE_TAKEN_MILLIS, MediaColumns.SYNC_GENERATION,
                MediaColumns.MIME_TYPE, MediaColumns.STANDARD_MIME_TYPE_EXTENSION, MediaColumns.SIZE_BYTES,
                MediaColumns.MEDIA_STORE_URI, MediaColumns.DURATION_MILLIS, MediaColumns.IS_FAVORITE,
                MediaColumns.WIDTH, MediaColumns.HEIGHT, MediaColumns.ORIENTATION,
            ),
        )
        val generation = db.generation
        for (a in assets) {
            cursor.addRow(
                arrayOf<Any?>(
                    a.id, a.takenAt, generation, a.mimeType,
                    if (a.mimeType == "image/gif") MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF
                    else MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE,
                    a.size,
                    localIds[a.id]?.let { MediaStore.Files.getContentUri("external", it).toString() },
                    a.durationMs, if (a.isFavorite) 1 else 0, a.width, a.height, 0,
                ),
            )
        }
        cursor.extras = page.extras(extras, db.collectionId, assets.size)
        return cursor
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        val db = container.library.db
        val page = Page(extras)
        val ids = db.deleted(extras.getLong(EXTRA_SYNC_GENERATION, 0), page.limit, page.offset)
        val cursor = MatrixCursor(arrayOf(MediaColumns.ID))
        ids.forEach { cursor.addRow(arrayOf(it)) }
        cursor.extras = page.extras(extras, db.collectionId, ids.size)
        return cursor
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        val db = container.library.db
        val cursor = MatrixCursor(
            arrayOf(
                AlbumColumns.ID, AlbumColumns.DISPLAY_NAME, AlbumColumns.DATE_TAKEN_MILLIS,
                AlbumColumns.MEDIA_COVER_ID, AlbumColumns.MEDIA_COUNT,
            ),
        )
        db.albums().forEach { cursor.addRow(arrayOf<Any?>(it.id, it.name, it.latestTakenAt, it.coverId, it.count)) }
        cursor.extras = Page(Bundle()).extras(extras, db.collectionId, 0)
        return cursor
    }

    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        // The grid asks for small sizes; a preview (long-press) for screen-sized images.
        val large = extras?.getBoolean(EXTRA_PREVIEW_THUMBNAIL) == true || maxOf(size.x, size.y) > 300
        val file = download(signal) { container.assetFiles.thumbnail(mediaId, if (large) "preview" else "thumbnail") }
        return AssetFileDescriptor(open(file), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun onOpenMedia(mediaId: String, extras: Bundle?, signal: CancellationSignal?): ParcelFileDescriptor =
        open(download(signal) { container.assetFiles.original(mediaId) })

    private fun open(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /** Runs a download on this binder thread; the picker can cancel it through [signal]. */
    private fun download(signal: CancellationSignal?, block: suspend () -> File): File = runBlocking {
        val job = async { block() }
        signal?.setOnCancelListener { job.cancel() }
        try {
            job.await()
        } catch (e: CancellationException) {
            throw FileNotFoundException("Cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            throw FileNotFoundException(e.message ?: "Download failed")
        }
    }

    /** Offset paging; the page token is the offset of the next page. */
    private class Page(extras: Bundle) {
        val limit = extras.getInt(EXTRA_PAGE_SIZE, -1).takeIf { it > 0 } ?: -1
        val offset = extras.getString(EXTRA_PAGE_TOKEN)?.toIntOrNull() ?: 0

        fun extras(query: Bundle, collectionId: String, rows: Int) = Bundle().apply {
            putString(EXTRA_MEDIA_COLLECTION_ID, collectionId)
            putStringArrayList(
                ContentResolver.EXTRA_HONORED_ARGS,
                ArrayList(HONORED.filter { query.containsKey(it) }),
            )
            if (limit > 0 && rows == limit) putString(EXTRA_PAGE_TOKEN, (offset + rows).toString())
        }
    }

    private companion object {
        const val TAG = "CloudMediaProvider"
        val HONORED = listOf(EXTRA_SYNC_GENERATION, EXTRA_ALBUM_ID, EXTRA_PAGE_SIZE, EXTRA_PAGE_TOKEN)
    }
}
