package app.immichcompanion.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val userId: String,
    val userEmail: String,
    val name: String,
    val isAdmin: Boolean = false,
)

@Serializable
data class UserResponse(val id: String, val email: String, val name: String)

@Serializable
data class ServerVersion(val major: Int, val minor: Int, val patch: Int) {
    override fun toString() = "v$major.$minor.$patch"
}

@Serializable
private data class PingResponse(val res: String)

@Serializable
private data class WellKnown(val api: Api) {
    @Serializable
    data class Api(val endpoint: String)
}

@Serializable
data class BulkUploadCheckItem(val id: String, val checksum: String)

@Serializable
private data class BulkUploadCheckRequest(val assets: List<BulkUploadCheckItem>)

@Serializable
data class BulkUploadCheckResult(
    val id: String,
    /** accept or reject. */
    val action: String,
    /** duplicate or unsupported-format, when rejected. */
    val reason: String? = null,
    val assetId: String? = null,
    val isTrashed: Boolean = false,
)

@Serializable
private data class BulkUploadCheckResponse(val results: List<BulkUploadCheckResult>)

@Serializable
data class UploadResponse(
    val id: String,
    /** created or duplicate. */
    val status: String,
)

/** Metadata Immich stores with an uploaded file. Times are ISO-8601 instants. */
data class UploadMetadata(
    val filename: String,
    val fileCreatedAt: String,
    val fileModifiedAt: String,
    val isFavorite: Boolean = false,
    /** Video length in milliseconds. */
    val durationMs: Long? = null,
    /** Base64 or hex SHA-1, lets the server skip a file it already has. */
    val checksum: String? = null,
)

@Serializable
private data class SyncStreamRequest(val types: List<String>, val reset: Boolean)

/** One line of `/sync/stream`. [ack] is sent back once the change is stored. */
@Serializable
data class SyncLine(val type: String, val data: JsonObject, val ack: String)

@Serializable
private data class SyncAckRequest(val acks: List<String>)

@Serializable
private data class ErrorBody(val message: String? = null)

class ImmichApiException(val code: Int, message: String) : IOException(message)

/** The few Immich REST calls the companion needs. All calls use the shared (Tailscale-aware) client. */
class ImmichApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    // The server answers an upload only after it has stored the whole file.
    private val uploadClient = client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()

    // Sync streams and downloads can be large; only stalls should time out.
    private val streamClient = client.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()

    /**
     * Finds the API root for a server URL. It reads `/.well-known/immich` like the
     * official app and falls back to `<server>/api`, then checks that the API answers.
     */
    suspend fun discover(serverUrl: HttpUrl): HttpUrl {
        val fromWellKnown = runCatching {
            val wk = get<WellKnown>(serverUrl.resolve("/.well-known/immich")!!, token = null)
            ServerUrl.resolveApiEndpoint(serverUrl, wk.api.endpoint)
        }.getOrNull()
        val candidates = listOfNotNull(fromWellKnown, ServerUrl.defaultApi(serverUrl)).distinct()
        var lastError: Exception? = null
        for (api in candidates) {
            // Transport errors (Tailscale down, DNS failure) propagate as-is.
            try {
                if (ping(api)) return api
            } catch (e: ImmichApiException) {
                lastError = e
            } catch (e: SerializationException) {
                lastError = IOException("Server responded, but it isn't an Immich server", e)
            }
        }
        throw lastError ?: IOException("No Immich server found at $serverUrl")
    }

    suspend fun ping(api: HttpUrl): Boolean = get<PingResponse>(api.child("server/ping"), token = null).res == "pong"

    suspend fun serverVersion(api: HttpUrl): ServerVersion = get(api.child("server/version"), token = null)

    suspend fun login(api: HttpUrl, email: String, password: String): LoginResponse =
        post(api.child("auth/login"), json.encodeToString(LoginRequest(email, password)), token = null)

    suspend fun me(api: HttpUrl, token: String): UserResponse = get(api.child("users/me"), token)

    suspend fun logout(api: HttpUrl, token: String) {
        execute(Request.Builder().url(api.child("auth/logout")).post(ByteArray(0).toRequestBody()), token)
    }

    /** Asks which of the files (by SHA-1) the server already has. */
    suspend fun bulkUploadCheck(api: HttpUrl, token: String, items: List<BulkUploadCheckItem>): List<BulkUploadCheckResult> =
        post<BulkUploadCheckResponse>(
            api.child("assets/bulk-upload-check"),
            json.encodeToString(BulkUploadCheckRequest(items)),
            token,
        ).results

    /** Uploads one file. [data] is streamed, so it can be any size. */
    suspend fun upload(api: HttpUrl, token: String, meta: UploadMetadata, data: RequestBody): UploadResponse {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("fileCreatedAt", meta.fileCreatedAt)
            .addFormDataPart("fileModifiedAt", meta.fileModifiedAt)
            .addFormDataPart("filename", meta.filename)
            .addFormDataPart("isFavorite", meta.isFavorite.toString())
            .apply { meta.durationMs?.let { addFormDataPart("duration", it.toString()) } }
            .addFormDataPart("assetData", meta.filename, data)
            .build()
        val builder = Request.Builder().url(api.child("assets")).post(body)
        meta.checksum?.let { builder.header("x-immich-checksum", it) }
        return json.decodeFromString(execute(builder, token, uploadClient))
    }

    private suspend inline fun <reified T> get(url: HttpUrl, token: String?): T =
        json.decodeFromString(execute(Request.Builder().url(url).get(), token))

    private suspend inline fun <reified T> post(url: HttpUrl, body: String, token: String?): T =
        json.decodeFromString(execute(Request.Builder().url(url).post(body.toRequestBody(jsonType)), token))

    /**
     * Streams `/sync/stream` (JSON lines) and hands each line to [onLine]. Only the
     * user's own entities of [types] are sent; acked changes aren't sent again.
     */
    suspend fun syncStream(
        api: HttpUrl,
        token: String,
        types: List<String>,
        reset: Boolean,
        onLine: suspend (SyncLine) -> Unit,
    ) {
        val body = json.encodeToString(SyncStreamRequest(types, reset)).toRequestBody(jsonType)
        open(Request.Builder().url(api.child("sync/stream")).post(body), token, streamClient, accept = "*/*").use { resp ->
            val source = resp.body.source()
            while (true) {
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                if (line.isNotBlank()) onLine(json.decodeFromString(line))
            }
        }
    }

    /** Tells the server which sync changes are stored, so the next stream continues after them. */
    suspend fun syncAck(api: HttpUrl, token: String, acks: List<String>) {
        val body = json.encodeToString(SyncAckRequest(acks)).toRequestBody(jsonType)
        execute(Request.Builder().url(api.child("sync/ack")).post(body), token)
    }

    /** Downloads an asset's thumbnail (`thumbnail` ~250 px WebP, `preview` ~1440 px JPEG) to [dest]. */
    suspend fun downloadThumbnail(api: HttpUrl, token: String, assetId: String, size: String, dest: File) =
        download(api.child("assets/$assetId/thumbnail").newBuilder().addQueryParameter("size", size).build(), token, dest)

    /** Downloads the original file of an asset to [dest]. */
    suspend fun downloadOriginal(api: HttpUrl, token: String, assetId: String, dest: File) =
        download(api.child("assets/$assetId/original"), token, dest)

    private suspend fun download(url: HttpUrl, token: String, dest: File) {
        open(Request.Builder().url(url).get(), token, streamClient, accept = "*/*").use { resp ->
            withContext(Dispatchers.IO) {
                dest.sink().buffer().use { it.writeAll(resp.body.source()) }
            }
        }
    }

    private suspend fun execute(
        builder: Request.Builder,
        token: String?,
        client: OkHttpClient = this.client,
    ): String = open(builder, token, client).use { withContext(Dispatchers.IO) { it.body.string() } }

    /** Sends the request and returns the successful response; the caller closes it. */
    private suspend fun open(
        builder: Request.Builder,
        token: String?,
        client: OkHttpClient,
        accept: String = "application/json",
    ): Response {
        builder.header("Accept", accept)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val call = client.newCall(builder.build())
        // Async, so cancelling the coroutine (e.g. a stopped backup) also stops the request.
        val resp = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) =
                    cont.resume(response) { _, r, _ -> r.close() }
            })
        }
        if (!resp.isSuccessful) {
            val body = resp.use { withContext(Dispatchers.IO) { it.body.string() } }
            val msg = runCatching { json.decodeFromString<ErrorBody>(body).message }.getOrNull()
            throw ImmichApiException(resp.code, msg ?: "HTTP ${resp.code}")
        }
        return resp
    }

    private fun HttpUrl.child(path: String): HttpUrl = newBuilder().addPathSegments(path).build()
}
