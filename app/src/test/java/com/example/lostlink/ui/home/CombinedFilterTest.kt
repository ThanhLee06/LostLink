package com.example.lostlink.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class CombinedFilterTest {
    private fun at(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    private val now = at("2026-11-03T23:30:00+07:00")

    private fun post(id: String, type: String, category: String, area: String, time: Long, title: String) =
        HomePost(id, title, type, "Khu vực $area", time, categoryId = category, areaId = area)

    @Test
    fun testCombinedAllFilterConditions() {
        val p1 = post("1", "LOST", "wallet", "campus_1", now, "Ví da nâu")
        val p2 = post("2", "FOUND", "wallet", "campus_1", now, "Ví da đen nhặt được")
        val p3 = post("3", "LOST", "keys", "campus_1", now, "Chìa khóa")
        val p4 = post("4", "LOST", "wallet", "campus_2", now, "Ví ở cơ sở 2")
        val p5 = post("5", "LOST", "wallet", "campus_1", now - 10 * 86_400_000L, "Ví cũ 10 ngày trước")

        val allPosts = listOf(p1, p2, p3, p4, p5)

        // Lọc kết hợp 4 tiêu chí: Loại tin (LOST) + Danh mục (wallet) + Khu vực (campus_1) + Thời gian (TODAY) + Từ khóa ("Ví")
        val filtered = filterLoadedHomePosts(
            posts = allPosts,
            query = "Ví",
            filter = HomePostFilter.LOST,
            categories = listOf("wallet"),
            areas = listOf("campus_1"),
            timeFilter = HomeTimeFilter.TODAY,
            now = now
        )

        assertEquals(1, filtered.size)
        assertEquals("1", filtered[0].id)
    }

    @Test
    fun testEmptyStateWhenNoPostMatchesFilters() {
        val p1 = post("1", "LOST", "wallet", "campus_1", now, "Ví da")
        val allPosts = listOf(p1)

        // Không khớp vì loại tin FOUND
        val noMatch = filterLoadedHomePosts(
            posts = allPosts,
            query = "",
            filter = HomePostFilter.FOUND,
            categories = listOf("wallet"),
            areas = listOf("campus_1"),
            timeFilter = HomeTimeFilter.TODAY,
            now = now
        )

        assertTrue(noMatch.isEmpty())
    }

    @Test
    fun testActiveFilterCountCalculation() {
        fun count(filter: HomePostFilter, categories: List<String>, areas: List<String>, time: HomeTimeFilter): Int {
            return (if (filter != HomePostFilter.ALL) 1 else 0) +
                (if (time != HomeTimeFilter.ALL) 1 else 0) +
                categories.size + areas.size
        }

        // Mặc định: 0
        assertEquals(0, count(HomePostFilter.ALL, emptyList(), emptyList(), HomeTimeFilter.ALL))

        // 1 loại tin
        assertEquals(1, count(HomePostFilter.LOST, emptyList(), emptyList(), HomeTimeFilter.ALL))

        // 1 loại tin + 1 danh mục + 1 khu vực -> 3 bộ lọc
        assertEquals(3, count(HomePostFilter.LOST, listOf("wallet"), listOf("campus_1"), HomeTimeFilter.ALL))

        // 1 loại tin + 1 danh mục + 1 khu vực + 1 thời gian -> 4 bộ lọc
        assertEquals(4, count(HomePostFilter.LOST, listOf("wallet"), listOf("campus_1"), HomeTimeFilter.TODAY))
    }

    @Test
    fun testTimeFilterRanges_VietnamTimezone() {
        // Thứ Ba 03/11/2026 15:00:00 GMT+7
        val refNow = at("2026-11-03T15:00:00+07:00")
        // Cùng ngày lúc 01:00 sáng
        val postToday = post("t1", "LOST", "wallet", "campus_1", at("2026-11-03T01:00:00+07:00"), "Ví sáng nay")
        // Hôm qua 02/11/2026 23:59:00 GMT+7 (thuộc cùng tuần vì thứ Hai)
        val postYesterday = post("t2", "LOST", "wallet", "campus_1", at("2026-11-02T23:59:00+07:00"), "Ví hôm qua")
        // Tuần trước 25/10/2026 (Chủ nhật tuần trước, khác tháng)
        val postLastMonth = post("t3", "LOST", "wallet", "campus_1", at("2026-10-25T10:00:00+07:00"), "Ví tháng trước")

        val posts = listOf(postToday, postYesterday, postLastMonth)

        // 1. Lọc Hôm nay: chỉ có postToday
        val todayResults = filterLoadedHomePosts(posts, "", HomePostFilter.ALL, timeFilter = HomeTimeFilter.TODAY, now = refNow)
        assertEquals(1, todayResults.size)
        assertEquals("t1", todayResults[0].id)

        // 2. Lọc Tuần này: postToday và postYesterday (Thứ 2 và Thứ 3 cùng tuần)
        val weekResults = filterLoadedHomePosts(posts, "", HomePostFilter.ALL, timeFilter = HomeTimeFilter.THIS_WEEK, now = refNow)
        assertEquals(2, weekResults.size)
        assertTrue(weekResults.any { it.id == "t1" })
        assertTrue(weekResults.any { it.id == "t2" })

        // 3. Lọc Tháng này (Tháng 11/2026): postToday và postYesterday
        val monthResults = filterLoadedHomePosts(posts, "", HomePostFilter.ALL, timeFilter = HomeTimeFilter.THIS_MONTH, now = refNow)
        assertEquals(2, monthResults.size)

        // 4. Lọc Tất cả: cả 3 bài
        val allResults = filterLoadedHomePosts(posts, "", HomePostFilter.ALL, timeFilter = HomeTimeFilter.ALL, now = refNow)
        assertEquals(3, allResults.size)
    }

    @Test
    fun testCategoryAndAreaAliasMatching() {
        // Bài viết lưu categoryId là "phone", location là "Phòng A101, Tòa A"
        val pPhone = HomePost(
            id = "p1",
            title = "iPhone 15 bỏ quên",
            type = "LOST",
            location = "Phòng A101, Tòa A",
            createdAt = now,
            categoryId = "phone",
            areaId = "a"
        )
        val posts = listOf(pPhone)

        // Bộ lọc chọn "electronics" (điện thoại/laptop) -> khớp vì "phone" là alias của ELECTRONICS
        val matchedCategory = filterLoadedHomePosts(
            posts = posts,
            query = "",
            filter = HomePostFilter.ALL,
            categories = listOf("electronics")
        )
        assertEquals(1, matchedCategory.size)

        // Bộ lọc chọn khu vực "a" -> khớp
        val matchedArea = filterLoadedHomePosts(
            posts = posts,
            query = "",
            filter = HomePostFilter.ALL,
            areas = listOf("a")
        )
        assertEquals(1, matchedArea.size)
    }

    @Test
    fun testPostResolvedStatusHandling() {
        val openPost = HomePost("1", "Ví", "LOST", "CS1", now, status = "OPEN")
        val resolvedPost = HomePost("2", "Chìa khóa", "FOUND", "CS1", now, status = "RESOLVED")
        val closedPost = HomePost("3", "Thẻ", "LOST", "CS1", now, status = "CLOSED")
        val completedPost = HomePost("4", "Balo", "LOST", "CS1", now, status = "COMPLETED")

        assertTrue(openPost.isOpened)
        assertFalse(openPost.isResolved)

        assertTrue(resolvedPost.isResolved)
        assertFalse(resolvedPost.isOpened)

        assertTrue(closedPost.isResolved)
        assertFalse(closedPost.isOpened)

        assertTrue(completedPost.isResolved)
        assertFalse(completedPost.isOpened)
    }
}
