package app.immichcompanion.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Reads media files the way they're uploaded, so the checksum matches what the server computes. */
class MediaFiles(private val context: Context) {

    /**
     * Opens the file. With ACCESS_MEDIA_LOCATION this is the original, including GPS
     * data; without it Android hides the location from the file.
     */
    fun open(media: LocalMedia): InputStream {
        val resolver = context.contentResolver
        val original = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val stream = if (original) {
            try {
                resolver.openInputStream(MediaStore.setRequireOriginal(media.uri))
            } catch (_: SecurityException) {
                resolver.openInputStream(media.uri)
            }
        } else {
            resolver.openInputStream(media.uri)
        }
        return stream ?: throw FileNotFoundException("Couldn't open ${media.name}")
    }

    /** Base64 SHA-1 of the file. */
    fun sha1(media: LocalMedia): String = open(media).use { sha1(it) }

    /** Streams the file as an upload body. [onProgress] gets the bytes sent so far. */
    fun requestBody(media: LocalMedia, onProgress: (Long) -> Unit = {}): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType? = media.mimeType.toMediaTypeOrNull()

        // Unknown up front: the size in MediaStore can be stale, so send it chunked.
        override fun contentLength() = -1L

        override fun writeTo(sink: BufferedSink) {
            open(media).source().use { source ->
                var sent = 0L
                while (true) {
                    val n = source.read(sink.buffer, BUFFER_SIZE)
                    if (n == -1L) break
                    sink.emitCompleteSegments()
                    sent += n
                    onProgress(sent)
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64L * 1024

        fun sha1(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(BUFFER_SIZE.toInt())
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
            return Base64.getEncoder().encodeToString(digest.digest())
        }
    }
}
