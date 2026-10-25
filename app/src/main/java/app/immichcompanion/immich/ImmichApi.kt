package app.immichcompanion.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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
private data class ErrorBody(val message: String? = null)

class ImmichApiException(val code: Int, message: String) : IOException(message)

/** The few Immich REST calls the companion needs. All calls use the shared (Tailscale-aware) client. */
class ImmichApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

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

    private suspend inline fun <reified T> get(url: HttpUrl, token: String?): T =
        json.decodeFromString(execute(Request.Builder().url(url).get(), token))

    private suspend inline fun <reified T> post(url: HttpUrl, body: String, token: String?): T =
        json.decodeFromString(execute(Request.Builder().url(url).post(body.toRequestBody(jsonType)), token))

    private suspend fun execute(builder: Request.Builder, token: String?): String = withContext(Dispatchers.IO) {
        builder.header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.decodeFromString<ErrorBody>(body).message }.getOrNull()
                throw ImmichApiException(resp.code, msg ?: "HTTP ${resp.code}")
            }
            body
        }
    }

    private fun HttpUrl.child(path: String): HttpUrl = newBuilder().addPathSegments(path).build()
}
