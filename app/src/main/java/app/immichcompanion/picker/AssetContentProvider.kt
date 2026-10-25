package app.immichcompanion.picker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.immichcompanion.AppContainer
import app.immichcompanion.ImmichCompanionApp
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves Immich originals to apps that picked them in [PickerActivity]. Not exported;
 * callers get a per-URI read grant. URIs look like `content://<authority>/asset/<id>/<file name>`.
 */
class AssetContentProvider : ContentProvider() {
    private val container: AppContainer by lazy { (context!!.applicationContext as ImmichCompanionApp).container }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = assetId(uri)?.let { container.library.db.asset(it)?.mimeType }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val asset = assetId(uri)?.let { container.library.db.asset(it) } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> asset.fileName
                OpenableColumns.SIZE -> asset.size.takeIf { s -> s > 0 }
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(values) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only")
        val id = assetId(uri) ?: throw FileNotFoundException(uri.toString())
        val file = try {
            runBlocking { container.assetFiles.original(id) }
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Download failed")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    private fun assetId(uri: Uri) = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "asset" }?.get(1)

    companion object {
        const val AUTHORITY = "app.immichcompanion.assets"

        fun uriFor(assetId: String, fileName: String): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY)
            .appendPath("asset").appendPath(assetId).appendPath(fileName)
            .build()
    }
}
