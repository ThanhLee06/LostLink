package com.example.lostlink.data

import com.example.lostlink.ui.home.HomePost
import com.example.lostlink.ui.home.HomePostFilter
import com.example.lostlink.ui.home.filterLoadedHomePosts
import com.example.lostlink.ui.home.suggestHomeSearchChips
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmartSearchApiTest {

    @Test
    fun searchPostsSendsPayloadAndParsesItemsAndCursor() = runBlocking {
        var authHeader: String? = null
        var receivedQuery: String? = null
        var receivedTimeFilter: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/search") { exchange ->
                authHeader = exchange.requestHeaders.getFirst("Authorization")
                val reqJson = SimpleJson.parseObject(exchange.requestBody.reader(Charsets.UTF_8).readText())
                receivedQuery = SimpleJson.stringValue(reqJson, "query")
                receivedTimeFilter = SimpleJson.stringValue(reqJson, "timeFilter")
                val body = """{
                    "items": [
                        {
                            "postId": "p1",
                            "title": "Mất ví da",
                            "type": "LOST",
                            "status": "OPEN",
                            "statusLabel": "Đang mở",
                            "categoryId": "wallet",
                            "areaId": "library",
                            "location": "Thư viện",
                            "createdAt": "2026-11-10T08:00:00Z",
                            "imageUrl": "https://res.cloudinary.com/demo/image/upload/v1/a.jpg",
                            "score": 0.95
                        }
                    ],
                    "hasMore": true,
                    "nextCursor": "cur-next-1",
                    "searchMode": "HYBRID"
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
                tokenProvider = { "student-jwt" },
                allowCleartext = true,
            )
            val result = client.searchPosts(
                SmartSearchRequest(query = "ví da", type = "LOST", categoryIds = listOf("wallet"), timeFilter = "TODAY")
            )
            assertTrue(result.isSuccess)
            val page = result.getOrThrow()
            assertEquals("Bearer student-jwt", authHeader)
            assertEquals("ví da", receivedQuery)
            assertEquals("TODAY", receivedTimeFilter)
            assertEquals("HYBRID", page.searchMode)
            assertTrue(page.hasMore)
            assertEquals("cur-next-1", page.nextCursor)
            assertEquals(1, page.posts.size)
            assertEquals("p1", page.posts[0].id)
            assertTrue(page.posts[0].isActive)
            assertFalse(page.posts[0].isResolved)
            assertTrue(page.posts[0].createdAt > 0L)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun friendlySearchErrorHandlesColdStartAndNetworkFailures() {
        assertTrue(friendlySearchError(SocketTimeoutException("timeout")).contains("khởi động"))
        assertTrue(friendlySearchError(UnknownHostException("offline")).contains("kết nối mạng"))
        assertTrue(
            friendlySearchError(BackendApiException(504, "DEADLINE_EXCEEDED", null, "timeout")).contains("khởi động")
        )
        assertNull(SmartSearchRequest().cursor)
    }

    @Test
    fun vietnameseSynonymsTelexTranspositionAndRepeatedCharsMatchWallet() {
        val walletPost = HomePost(
            id = "p-wallet",
            title = "Mất ví da đen tại thư viện",
            type = "LOST",
            location = "Thư viện tầng 2",
            createdAt = 1_700_000_000_000L,
            categoryId = "wallet",
        )
        val otherPost = HomePost(
            id = "p-keys",
            title = "Nhặt được chìa khóa xe",
            type = "FOUND",
            location = "Bãi giữ xe",
            createdAt = 1_700_000_000_000L,
            categoryId = "keys",
        )
        val posts = listOf(walletPost, otherPost)

        for (query in listOf("bóp", "túi tiền", "vis", "vsi", "viiiiii")) {
            val matched = filterLoadedHomePosts(posts, query, HomePostFilter.ALL)
            assertEquals("Query '$query' should match walletPost", listOf("p-wallet"), matched.map { it.id })
            val chips = suggestHomeSearchChips(query, posts)
            assertTrue("Query '$query' should suggest Ví tiền", chips.any { it.contains("Ví", ignoreCase = true) })
        }
    }
}
