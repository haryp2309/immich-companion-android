package app.immichcompanion.library

import android.content.Context
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.immich.ImmichApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/** Downloads thumbnails and originals from Immich into the cache, once each. */
class AssetFiles(context: Context, private val settings: SettingsStore, private val api: ImmichApi) {
    private val thumbs = File(context.cacheDir, "thumbs")
    private val originals = File(context.cacheDir, "originals")
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** `thumbnail` is a small WebP for grids, `preview` a large JPEG. */
    suspend fun thumbnail(assetId: String, size: String = "thumbnail"): File =
        cached(File(thumbs, "$assetId-$size")) { api, token, dest ->
            this.api.downloadThumbnail(api, token, assetId, size, dest)
        }

    suspend fun original(assetId: String): File =
        cached(File(originals, assetId)) { api, token, dest ->
            this.api.downloadOriginal(api, token, assetId, dest)
        }.also { trim(originals, MAX_ORIGINALS_BYTES, keep = it) }

    private suspend fun cached(
        file: File,
        download: suspend (api: HttpUrl, token: String, dest: File) -> Unit,
    ): File = locks.getOrPut(file.path) { Mutex() }.withLock {
        if (file.exists()) return@withLock file.also { it.setLastModified(System.currentTimeMillis()) }
        val session = settings.currentSession() ?: throw FileNotFoundException("Not signed in to Immich")
        file.parentFile!!.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.part")
        try {
            download(session.apiBaseUrl.toHttpUrl(), session.accessToken, tmp)
            if (!tmp.renameTo(file)) throw FileNotFoundException("Couldn't store ${file.name}")
        } finally {
            tmp.delete()
        }
        file
    }

    /** Deletes the least recently used files until the folder fits in [maxBytes]. */
    private fun trim(dir: File, maxBytes: Long, keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val MAX_ORIGINALS_BYTES = 1L shl 30
    }
}
