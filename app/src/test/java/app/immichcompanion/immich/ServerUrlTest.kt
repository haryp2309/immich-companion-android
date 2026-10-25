package app.immichcompanion.immich

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test fun stripsTrailingSlashAndApiSuffix() {
        assertEquals("http://immich:2283/", ServerUrl.normalize(" http://immich:2283/api/ ").getOrThrow().toString())
        assertEquals("https://photos.example.com/immich", ServerUrl.normalize("https://photos.example.com/immich/").getOrThrow().toString())
    }

    @Test fun requiresScheme() {
        assertTrue(ServerUrl.normalize("immich:2283").isFailure)
    }

    @Test fun defaultApiAppendsSegment() {
        assertEquals("http://immich:2283/api", ServerUrl.defaultApi("http://immich:2283/".toHttpUrl()).toString())
        assertEquals("https://h/immich/api", ServerUrl.defaultApi("https://h/immich".toHttpUrl()).toString())
    }

    @Test fun resolvesWellKnownEndpoint() {
        val server = "http://immich:2283/".toHttpUrl()
        assertEquals("http://immich:2283/api", ServerUrl.resolveApiEndpoint(server, "/api/").toString())
        assertEquals("https://api.example.com/v1", ServerUrl.resolveApiEndpoint(server, "https://api.example.com/v1").toString())
    }
}
