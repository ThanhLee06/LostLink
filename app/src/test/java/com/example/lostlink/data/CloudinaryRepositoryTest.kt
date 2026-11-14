package com.example.lostlink.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class CloudinaryRepositoryTest {

    @Test
    fun uploadSignedImage_withNullUri_returnsSuccessWithNull() = runBlocking {
        val apiClient = BackendApiClient(
            rawBaseUrl = "https://backend.example.com",
            tokenProvider = { "token" },
            allowCleartext = false,
        )
        val repository = CloudinaryRepository(apiClient = apiClient)
        val result = repository.uploadSignedImage(
            context = object : android.content.ContextWrapper(null) {},
            postId = "post-123",
            imageUri = null,
        )
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun uploadSignedImageBytes_runsSignedFlowWithoutSecretOrPreset() = runBlocking {
        val result = executeSignedUpload(
            postId = "post-xyz",
            assetId = "asset-001",
            publicId = "lostlink/posts/post-xyz/asset-001",
            paramsJson = """
            {
              "public_id": "lostlink/posts/post-xyz/asset-001",
              "timestamp": 1794000000,
              "allowed_formats": "jpg,png,webp",
              "overwrite": "false",
              "api_key": "public_key_only",
              "signature": "signed_sha1_hex"
            }
            """.trimIndent(),
        )

        assertTrue(result.uploadResult.isSuccess)
        val uploaded = result.uploadResult.getOrNull()!!
        assertEquals("asset-001", uploaded.assetId)
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/asset-001.jpg", uploaded.secureUrl)
        assertEquals("lostlink/posts/post-xyz/asset-001", uploaded.publicId)
        assertTrue(result.completeCalled)
        assertEquals("signed_sha1_hex", result.multipartFields["signature"])
        assertEquals("public_key_only", result.multipartFields["api_key"])
        assertEquals("lostlink/posts/post-xyz/asset-001", result.multipartFields["public_id"])
        assertEquals("1794000000", result.multipartFields["timestamp"])
        assertEquals("jpg,png,webp", result.multipartFields["allowed_formats"])
        assertEquals("false", result.multipartFields["overwrite"])
        assertFalse(result.multipartFields.containsKey("upload_preset"))
        assertFalse(result.multipartFields.containsKey("api_secret"))
    }

    @Test
    fun uploadSignedImageBytes_forwardsBackendSignedUploadPresetUnchangedAndStripsSecret() = runBlocking {
        val result = executeSignedUpload(
            postId = "post-preset",
            assetId = "asset-preset-001",
            publicId = "lostlink/posts/post-preset/asset-preset-001",
            paramsJson = """
            {
              "public_id": "lostlink/posts/post-preset/asset-preset-001",
              "timestamp": 1794000123,
              "allowed_formats": "jpg,png,webp",
              "overwrite": "false",
              "upload_preset": "lostlink_signed_preset_v1",
              "api_key": "public_key_only",
              "signature": "signed_sha1_with_preset",
              "api_secret": "must_never_be_forwarded"
            }
            """.trimIndent(),
        )

        assertTrue(result.uploadResult.isSuccess)
        assertTrue(result.completeCalled)
        assertEquals("lostlink_signed_preset_v1", result.multipartFields["upload_preset"])
        assertEquals("signed_sha1_with_preset", result.multipartFields["signature"])
        assertEquals("public_key_only", result.multipartFields["api_key"])
        assertEquals("lostlink/posts/post-preset/asset-preset-001", result.multipartFields["public_id"])
        assertEquals("1794000123", result.multipartFields["timestamp"])
        assertEquals("jpg,png,webp", result.multipartFields["allowed_formats"])
        assertEquals("false", result.multipartFields["overwrite"])
        assertFalse(result.multipartFields.containsKey("api_secret"))
        assertFalse(result.rawUploadBody.contains("must_never_be_forwarded"))
    }

    private data class CapturedSignedUpload(
        val uploadResult: Result<UploadedMedia>,
        val completeCalled: Boolean,
        val multipartFields: Map<String, String>,
        val rawUploadBody: String,
    )

    private suspend fun executeSignedUpload(
        postId: String,
        assetId: String,
        publicId: String,
        paramsJson: String,
    ): CapturedSignedUpload {
        var completeCalled = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/media/uploads") { exchange ->
                val path = exchange.requestURI.path
                val responseJson = if (path == "/api/v1/media/uploads") {
                    """
                    {
                      "assetId": "$assetId",
                      "uploadUrl": "https://api.cloudinary.com/v1_1/demo/image/upload",
                      "publicId": "$publicId",
                      "params": $paramsJson
                    }
                    """.trimIndent()
                } else if (path == "/api/v1/media/uploads/$assetId/complete") {
                    completeCalled = true
                    """
                    {
                      "assetId": "$assetId",
                      "secureUrl": "https://res.cloudinary.com/demo/image/upload/v1/$assetId.jpg",
                      "publicId": "$publicId",
                      "status": "READY"
                    }
                    """.trimIndent()
                } else {
                    "{}"
                }
                val bytes = responseJson.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        return try {
            var capturedUploadBody = ""
            val capturedFields = linkedMapOf<String, String>()
            val cloudinaryFakeClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val multipart = req.body as? MultipartBody
                    if (multipart != null) {
                        for (part in multipart.parts) {
                            val disposition = part.headers?.get("Content-Disposition").orEmpty()
                            val match = Regex("""name="([^"]+)"""").find(disposition)
                            val fieldName = match?.groupValues?.get(1) ?: continue
                            if (fieldName != "file") {
                                val partBuf = Buffer()
                                part.body.writeTo(partBuf)
                                capturedFields[fieldName] = partBuf.readUtf8()
                            }
                        }
                    }
                    val buf = Buffer()
                    req.body?.writeTo(buf)
                    capturedUploadBody = buf.readUtf8()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"secure_url":"https://res.cloudinary.com/demo/image/upload/v1/$assetId.jpg","public_id":"$publicId"}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build()

            val apiClient = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { "firebase-id-token" },
                allowCleartext = true,
            )
            val repository = CloudinaryRepository(apiClient = apiClient, client = cloudinaryFakeClient)
            val uploadResult = repository.uploadSignedImageBytes(
                postId = postId,
                bytes = byteArrayOf(1, 2, 3, 4),
                mimeType = "image/jpeg",
            )
            CapturedSignedUpload(
                uploadResult = uploadResult,
                completeCalled = completeCalled,
                multipartFields = capturedFields,
                rawUploadBody = capturedUploadBody,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun uploadSignedImageBytes_failsClosedWhenCompleteReturnsConflict() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/media/uploads") { exchange ->
                if (exchange.requestURI.path.endsWith("/complete")) {
                    val err = """{"detail":"Asset was already cleaned up","code":"ASSET_DELETING"}"""
                    val b = err.toByteArray()
                    exchange.sendResponseHeaders(409, b.size.toLong())
                    exchange.responseBody.use { it.write(b) }
                } else {
                    val ok = """
                    {
                      "assetId": "asset-002",
                      "uploadUrl": "https://api.cloudinary.com/v1_1/demo/image/upload",
                      "publicId": "lostlink/posts/p2/asset-002",
                      "params": {
                        "public_id": "lostlink/posts/p2/asset-002",
                        "timestamp": "1794000001",
                        "api_key": "pub",
                        "signature": "sig"
                      }
                    }
                    """.trimIndent().toByteArray()
                    exchange.sendResponseHeaders(200, ok.size.toLong())
                    exchange.responseBody.use { it.write(ok) }
                }
            }
            start()
        }

        try {
            val cloudinaryFakeClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"secure_url":"https://res.cloudinary.com/demo/image/upload/v1/asset-002.jpg"}""".toResponseBody())
                        .build()
                }
                .build()

            val apiClient = BackendApiClient(
                rawBaseUrl = "http://127.0.0.1:${server.address.port}",
                tokenProvider = { "firebase-id-token" },
                allowCleartext = true,
            )
            val repository = CloudinaryRepository(apiClient = apiClient, client = cloudinaryFakeClient)
            val result = repository.uploadSignedImageBytes("p2", byteArrayOf(5, 6, 7), "image/png")

            assertTrue(result.isFailure)
            val err = result.exceptionOrNull() as BackendApiException
            assertEquals(409, err.statusCode)
            assertEquals("ASSET_DELETING", err.code)
        } finally {
            server.stop(0)
        }
    }
}
