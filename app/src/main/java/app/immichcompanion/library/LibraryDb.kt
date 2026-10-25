package app.immichcompanion.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** A photo or video in the user's Immich library. */
data class RemoteAsset(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** Milliseconds since the epoch. */
    val takenAt: Long,
    val durationMs: Long = 0,
    val isFavorite: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    /** From the EXIF data; 0 until that arrived. */
    val size: Long = 0,
)

data class RemoteAlbum(val id: String, val name: String, val count: Int, val coverId: String?, val latestTakenAt: Long)

/**
 * Local copy of the Immich library, kept current by [LibrarySync]. Each change gets
 * a sync generation, so the photo picker can ask for just what changed since its
 * last sync.
 */
class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE assets (
                id TEXT PRIMARY KEY,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                is_video INTEGER NOT NULL,
                taken_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                favorite INTEGER NOT NULL,
                width INTEGER,
                height INTEGER,
                size INTEGER NOT NULL DEFAULT 0,
                generation INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX assets_taken ON assets (taken_at DESC, id)")
        db.execSQL("CREATE INDEX assets_generation ON assets (generation)")
        db.execSQL("CREATE TABLE deleted (id TEXT PRIMARY KEY, generation INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE albums (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE album_assets (album_id TEXT NOT NULL, asset_id TEXT NOT NULL, PRIMARY KEY (album_id, asset_id))")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Identifies this copy of the library. It changes when the copy is rebuilt from scratch. */
    val collectionId: String get() = meta("collection_id") ?: "empty"

    /** Which account this copy belongs to, or null if it's empty. */
    val account: String? get() = meta("account")

    val generation: Long get() = meta("generation")?.toLong() ?: 0

    /** Empties the library and starts a new collection for [account] (null: signed out). */
    fun reset(account: String?) = writableDatabase.inTx {
        for (table in listOf("assets", "deleted", "albums", "album_assets", "meta")) delete(table, null, null)
        setMeta("collection_id", UUID.randomUUID().toString())
        if (account != null) setMeta("account", account)
    }

    /** Applies changes in one transaction; they all get the next sync generation. */
    fun <T> write(block: Writer.() -> T): T = writableDatabase.inTx {
        val gen = (meta("generation")?.toLong() ?: 0) + 1
        setMeta("generation", gen.toString())
        Writer(this, gen).block()
    }

    class Writer(private val db: SQLiteDatabase, private val gen: Long) {
        fun upsert(a: RemoteAsset) {
            val size = db.longOrNull("SELECT size FROM assets WHERE id = ?", a.id) ?: a.size
            db.insertWithOnConflict(
                "assets", null,
                ContentValues().apply {
                    put("id", a.id)
                    put("file_name", a.fileName)
                    put("mime_type", a.mimeType)
                    put("is_video", a.isVideo)
                    put("taken_at", a.takenAt)
                    put("duration_ms", a.durationMs)
                    put("favorite", a.isFavorite)
                    put("width", a.width)
                    put("height", a.height)
                    put("size", maxOf(size, a.size))
                    put("generation", gen)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("deleted", "id = ?", arrayOf(a.id))
        }

        fun delete(assetId: String) {
            val removed = db.delete("assets", "id = ?", arrayOf(assetId))
            db.delete("album_assets", "asset_id = ?", arrayOf(assetId))
            if (removed > 0) {
                db.insertWithOnConflict(
                    "deleted", null,
                    ContentValues().apply { put("id", assetId); put("generation", gen) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }

        fun setSize(assetId: String, size: Long) {
            db.update(
                "assets",
                ContentValues().apply { put("size", size); put("generation", gen) },
                "id = ? AND size != ?", arrayOf(assetId, size.toString()),
            )
        }

        fun upsertAlbum(id: String, name: String) {
            db.insertWithOnConflict(
                "albums", null,
                ContentValues().apply { put("id", id); put("name", name) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

        fun deleteAlbum(id: String) {
            db.delete("albums", "id = ?", arrayOf(id))
            db.delete("album_assets", "album_id = ?", arrayOf(id))
        }

        fun addToAlbum(albumId: String, assetId: String) {
            db.insertWithOnConflict(
                "album_assets", null,
                ContentValues().apply { put("album_id", albumId); put("asset_id", assetId) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }

        fun removeFromAlbum(albumId: String, assetId: String) {
            db.delete("album_assets", "album_id = ? AND asset_id = ?", arrayOf(albumId, assetId))
        }
    }

    /**
     * Assets newest first. [afterGeneration] limits it to changes after a sync
     * generation; [mimePrefixes] (e.g. "image/") to some types.
     */
    fun assets(
        afterGeneration: Long = 0,
        albumId: String? = null,
        mimePrefixes: List<String> = emptyList(),
        limit: Int = -1,
        offset: Int = 0,
    ): List<RemoteAsset> {
        val where = mutableListOf("a.generation > ?")
        val args = mutableListOf(afterGeneration.toString())
        if (albumId != null) {
            where += "a.id IN (SELECT asset_id FROM album_assets WHERE album_id = ?)"
            args += albumId
        }
        if (mimePrefixes.isNotEmpty()) {
            where += mimePrefixes.joinToString(" OR ", "(", ")") { "a.mime_type LIKE ?" }
            args += mimePrefixes.map { "$it%" }
        }
        val sql = "SELECT a.id, a.file_name, a.mime_type, a.is_video, a.taken_at, a.duration_ms, a.favorite, " +
            "a.width, a.height, a.size FROM assets a WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY a.taken_at DESC, a.id LIMIT $limit OFFSET $offset"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toAsset()) }
        }
    }

    fun asset(id: String): RemoteAsset? = readableDatabase.rawQuery(
        "SELECT id, file_name, mime_type, is_video, taken_at, duration_ms, favorite, width, height, size " +
            "FROM assets WHERE id = ?",
        arrayOf(id),
    ).use { c -> if (c.moveToFirst()) c.toAsset() else null }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM assets", null).use { it.moveToFirst(); it.getInt(0) }

    /** IDs removed after [afterGeneration]. */
    fun deleted(afterGeneration: Long, limit: Int = -1, offset: Int = 0): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM deleted WHERE generation > ? ORDER BY generation, id LIMIT $limit OFFSET $offset",
        arrayOf(afterGeneration.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Albums that contain at least one asset, newest first. */
    fun albums(): List<RemoteAlbum> = readableDatabase.rawQuery(
        """
        SELECT al.id, al.name, COUNT(a.id), MAX(a.taken_at),
            (SELECT a2.id FROM album_assets aa2 JOIN assets a2 ON a2.id = aa2.asset_id
             WHERE aa2.album_id = al.id ORDER BY a2.taken_at DESC LIMIT 1)
        FROM albums al
        JOIN album_assets aa ON aa.album_id = al.id
        JOIN assets a ON a.id = aa.asset_id
        GROUP BY al.id
        ORDER BY MAX(a.taken_at) DESC
        """.trimIndent(),
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(RemoteAlbum(c.getString(0), c.getString(1), c.getInt(2), c.getString(4), c.getLong(3))) }
    }

    private fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun Cursor.toAsset() = RemoteAsset(
        id = getString(0),
        fileName = getString(1),
        mimeType = getString(2),
        isVideo = getInt(3) == 1,
        takenAt = getLong(4),
        durationMs = getLong(5),
        isFavorite = getInt(6) == 1,
        width = if (isNull(7)) null else getInt(7),
        height = if (isNull(8)) null else getInt(8),
        size = getLong(9),
    )
}

private fun SQLiteDatabase.setMeta(key: String, value: String) {
    insertWithOnConflict(
        "meta", null,
        ContentValues().apply { put("key", key); put("value", value) },
        SQLiteDatabase.CONFLICT_REPLACE,
    )
}

private fun SQLiteDatabase.longOrNull(sql: String, vararg args: String): Long? =
    rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

private inline fun <T> SQLiteDatabase.inTx(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
