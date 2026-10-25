package app.immichcompanion.immich

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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

    @Test fun uploadSendsMultipartWithChecksum() = runTest {
        route { json("""{"id":"a1","status":"created"}""", 201) }
        val meta = UploadMetadata(
            filename = "IMG_1.jpg",
            fileCreatedAt = "2026-01-02T03:04:05Z",
            fileModifiedAt = "2026-01-02T03:04:06Z",
            isFavorite = true,
            durationMs = 1500,
            checksum = "qvTGHdzF6KLavt4PO0gs2a6pQ00=",
        )
        val resp = api.upload(server.url("/api"), "tok", meta, "hello".toRequestBody("image/jpeg".toMediaType()))
        assertEquals(UploadResponse("a1", "created"), resp)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/assets", req.url.encodedPath)
        assertEquals("qvTGHdzF6KLavt4PO0gs2a6pQ00=", req.headers["x-immich-checksum"])
        val body = req.body!!.utf8()
        for (field in listOf("fileCreatedAt", "fileModifiedAt", "filename", "isFavorite", "duration")) {
            assertTrue("missing $field", body.contains("name=\"$field\""))
        }
        assertTrue(body.contains("name=\"assetData\"; filename=\"IMG_1.jpg\""))
        assertTrue(body.contains("1500"))
        assertTrue(body.contains("hello"))
    }

    @Test fun uploadAcceptsDuplicateResponse() = runTest {
        route { json("""{"id":"a1","status":"duplicate"}""", 200) }
        val meta = UploadMetadata("a.jpg", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        assertEquals("duplicate", api.upload(server.url("/api"), "tok", meta, "x".toRequestBody()).status)
    }

    @Test fun bulkUploadCheckParsesResults() = runTest {
        route {
            json(
                """{"results":[
                    {"id":"1","action":"accept"},
                    {"id":"2","action":"reject","reason":"duplicate","assetId":"a2","isTrashed":false}
                ]}""",
            )
        }
        val results = api.bulkUploadCheck(
            server.url("/api"), "tok",
            listOf(BulkUploadCheckItem("1", "c1"), BulkUploadCheckItem("2", "c2")),
        )
        assertEquals(listOf("accept", "reject"), results.map { it.action })
        assertEquals("a2", results[1].assetId)
        val req = server.takeRequest()
        assertEquals("/api/assets/bulk-upload-check", req.url.encodedPath)
        assertEquals("""{"assets":[{"id":"1","checksum":"c1"},{"id":"2","checksum":"c2"}]}""", req.body!!.utf8())
    }
}
