package app.immichcompanion.immich

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImmichApiTest {
    private val server = MockWebServer()
    private val api = ImmichApi(OkHttpClient())

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun discoverUsesWellKnownEndpoint() = runTest {
        route {
            when (it.url.encodedPath) {
                "/.well-known/immich" -> json("""{"api":{"endpoint":"/custom-api"}}""")
                "/custom-api/server/ping" -> json("""{"res":"pong"}""")
                else -> json("{}", 404)
            }
        }
        assertEquals(server.url("/custom-api"), api.discover(server.url("/")))
    }

    @Test fun discoverFallsBackToApiPath() = runTest {
        route {
            when (it.url.encodedPath) {
                "/api/server/ping" -> json("""{"res":"pong"}""")
                else -> json("{}", 404)
            }
        }
        assertEquals(server.url("/api"), api.discover(server.url("/")))
    }

    @Test fun loginParsesTokenAndReportsBadCredentials() = runTest {
        route {
            val body = it.body?.utf8() ?: ""
            if (body.contains("\"password\":\"right\"")) {
                json("""{"accessToken":"tok","userId":"u1","userEmail":"a@b.c","name":"A","isAdmin":true,"extra":1}""")
            } else {
                json("""{"message":"Incorrect email or password","statusCode":401}""", 401)
            }
        }
        val apiBase = server.url("/api")
        assertEquals("tok", api.login(apiBase, "a@b.c", "right").accessToken)
        val err = runCatching { api.login(apiBase, "a@b.c", "wrong") }.exceptionOrNull()
        assertTrue(err is ImmichApiException && err.code == 401)
    }

    @Test fun authenticatedCallsSendBearerToken() = runTest {
        route { json("""{"id":"u1","email":"a@b.c","name":"A"}""") }
        api.me(server.url("/api"), "tok")
        val req = server.takeRequest()
        assertEquals("/api/users/me", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
    }
}
