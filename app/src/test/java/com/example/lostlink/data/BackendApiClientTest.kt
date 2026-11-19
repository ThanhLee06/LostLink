package com.example.lostlink.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class BackendApiClientTest {

    @Test
    fun releaseModeRejectsCleartextHttpBackendUrl() = runBlocking {
        val client = BackendApiClient(
            rawBaseUrl = "http://10.0.2.2:8000",
            tokenProvider = { "secret-id-token" },
            allowCleartext = false,
        )
        val result = client.requestMediaUpload("post-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    fun requestSignedUploadSendsBearerTokenAndParsesCamelCaseResponse() = runBlocking {
        var authHeader: String? = null
        var receivedPostId: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/media/uploads") { exchange ->
                authHeader = exchange.requestHeaders.getFirst("Authorization")
                val requestText = exchange.requestBody.reader(Charsets.UTF_8).readText()
                receivedPostId = SimpleJson.stringValue(SimpleJson.parseObject(requestText), "postId")
                val body = """{
                    "assetId": "asset-123",
                    "uploadUrl": "https://api.cloudinary.com/v1_1/demo/image/upload",
                    "publicId": "lostlink/asset-123",
                    "params": {"api_key": "12345", "timestamp": "1700000000", "signature": "sig123"}
                }""".trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val client = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { "my-firebase-jwt" },
                allowCleartext = true,
            )
            val result = client.requestMediaUpload("post-xyz")
            assertTrue(result.isSuccess)
            val ticket = result.getOrThrow()
            assertEquals("Bearer my-firebase-jwt", authHeader)
            assertEquals("post-xyz", receivedPostId)
            assertEquals("asset-123", ticket.assetId)
            assertEquals("sig123", ticket.params["signature"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unauthorizedResponseMapsCleanlyWithoutLeakingToken() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/events/wake") { exchange ->
                val body = """{"detail":"Token expired","code":"AUTH_INVALID","traceId":"trace-xyz"}""".toByteArray()
                exchange.sendResponseHeaders(401, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val tokenSecret = "eyJhbGciOiJSUzI1NiJ9.super_secret_payload"
            val client = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { tokenSecret },
                allowCleartext = true,
            )
            val result = client.wakeWorker()
            assertTrue(result.isFailure)
            val err = result.exceptionOrNull() as BackendApiException
            assertEquals(401, err.statusCode)
            assertEquals("AUTH_INVALID", err.code)
            assertEquals("trace-xyz", err.traceId)
            assertFalse(err.message.orEmpty().contains(tokenSecret))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun wakeWorkerParsesProcessedCountAndMessage() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/events/wake") { exchange ->
                val body = """{"processed":3,"message":"Worker sweep complete."}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val client = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { "token" },
                allowCleartext = true,
            )
            val res = client.wakeWorker().getOrThrow()
            assertEquals(3, res.processed)
            assertEquals("Worker sweep complete.", res.message)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun accountDeletionEndpointsParseStepsAndResume() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/account/deletion") { exchange ->
                methods.add("${exchange.requestMethod} ${exchange.requestURI.path}")
                val body = """{
                    "uid": "u-1",
                    "status": "IN_PROGRESS",
                    "steps": {
                        "sessionRevoked": "DONE",
                        "firestorePosts": {"status": "DONE", "deleted": 3},
                        "mediaAssets": {"status": "IN_PROGRESS", "deleted": 1, "failed": 0},
                        "legacyMediaAudit": {"status": "DONE", "count": 2},
                        "firestoreUserData": {"status": "PENDING"},
                        "firebaseAuth": {"status": "PENDING"}
                    },
                    "lastError": null
                }""".trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(202, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val client = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { "student-jwt" },
                allowCleartext = true,
            )
            val init = client.requestAccountDeletion().getOrThrow()
            assertEquals("u-1", init.uid)
            assertEquals("IN_PROGRESS", init.status)
            assertEquals("DONE", init.steps.sessionRevoked)
            assertEquals(3, init.steps.firestorePostsDeleted)
            assertEquals(1, init.steps.mediaAssetsDeleted)
            assertEquals(2, init.steps.legacyAuditCount)

            val polled = client.getAccountDeletionStatus().getOrThrow()
            assertEquals("IN_PROGRESS", polled.status)

            val resumed = client.resumeAccountDeletion().getOrThrow()
            assertEquals("IN_PROGRESS", resumed.status)

            assertEquals(
                listOf(
                    "POST /api/v1/account/deletion",
                    "GET /api/v1/account/deletion",
                    "POST /api/v1/account/deletion/resume",
                ),
                methods,
            )
        } finally {
            server.stop(0)
        }
    }
}
