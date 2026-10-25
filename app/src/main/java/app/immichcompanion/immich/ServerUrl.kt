package app.immichcompanion.immich

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    /**
     * Turns user input like `http://immich.tailnet.ts.net:2283/` or `https://photos.example.com/api`
     * into a server root without a trailing slash or `/api` suffix.
     */
    fun normalize(input: String): Result<HttpUrl> {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Include http:// or https:// in the server URL"))
        }
        val withoutApi = trimmed.removeSuffix("/api")
        val url = withoutApi.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("That doesn't look like a valid URL"))
        return Result.success(url)
    }

    /** Resolves the `api.endpoint` from `/.well-known/immich`, which may be relative or absolute. */
    fun resolveApiEndpoint(server: HttpUrl, endpoint: String): HttpUrl? = server.resolve(endpoint.trimEnd('/'))

    fun defaultApi(server: HttpUrl): HttpUrl = server.newBuilder().addPathSegment("api").build()
}
