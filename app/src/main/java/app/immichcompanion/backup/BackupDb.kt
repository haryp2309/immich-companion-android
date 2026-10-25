package app.immichcompanion.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * What the app knows about a local media item for one Immich account. An item
 * counts as backed up when [remoteId] is set and the file hasn't changed since.
 */
data class BackupRecord(
    val mediaId: Long,
    val modifiedAt: Long,
    val size: Long,
    /** Base64 SHA-1 of the file, as Immich computes it. */
    val checksum: String?,
    val remoteId: String? = null,
    /** Why the server won't take the file (e.g. unsupported-format); it isn't retried. */
    val skipReason: String? = null,
    val error: String? = null,
) {
    fun matches(media: LocalMedia) = modifiedAt == media.modifiedAt && size == media.size
    fun isDone(media: LocalMedia) = matches(media) && (remoteId != null || skipReason != null)
}

/** Local backup state. Plain SQLite; the data is one small table. */
class BackupDb(context: Context) : SQLiteOpenHelper(context, "backup.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE assets (
                account TEXT NOT NULL,
                media_id INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                size INTEGER NOT NULL,
                checksum TEXT,
                remote_id TEXT,
                skip_reason TEXT,
                error TEXT,
                PRIMARY KEY (account, media_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(account: String): Map<Long, BackupRecord> {
        val out = HashMap<Long, BackupRecord>()
        readableDatabase.query(
            "assets",
            arrayOf("media_id", "modified_at", "size", "checksum", "remote_id", "skip_reason", "error"),
            "account = ?", arrayOf(account), null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = BackupRecord(
                    mediaId = c.getLong(0),
                    modifiedAt = c.getLong(1),
                    size = c.getLong(2),
                    checksum = c.getString(3),
                    remoteId = c.getString(4),
                    skipReason = c.getString(5),
                    error = c.getString(6),
                )
            }
        }
        return out
    }

    /** Local MediaStore IDs by Immich asset ID, for files this app uploaded. */
    fun uploaded(account: String): Map<String, Long> = readableDatabase.query(
        "assets", arrayOf("remote_id", "media_id"),
        "account = ? AND remote_id IS NOT NULL", arrayOf(account), null, null, null,
    ).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
    }

    fun save(account: String, record: BackupRecord) {
        val values = ContentValues().apply {
            put("account", account)
            put("media_id", record.mediaId)
            put("modified_at", record.modifiedAt)
            put("size", record.size)
            put("checksum", record.checksum)
            put("remote_id", record.remoteId)
            put("skip_reason", record.skipReason)
            put("error", record.error)
        }
        writableDatabase.insertWithOnConflict("assets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
