package com.example.lostlink.ui.profile

import com.example.lostlink.ui.home.HomePost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
    @Test fun closedPostsStayInCompletedTabRatherThanOpen() {
        val closed = HomePost("closed", "Ví", "LOST", "Thư viện", 1, status = "CLOSED")
        val state = ProfileState(userPosts = listOf(closed), selectedTab = ProfilePostTab.COMPLETED)
        assertEquals(0, state.openPostsCount)
        assertEquals(1, state.completedPostsCount)
        assertEquals(listOf(closed), state.filteredPosts)
    }


    @Test
    fun extractMssv_returnsDigitsFromEmailPrefix() {
        assertEquals("2151050001", UserProfile.extractMssv("2151050001@ut.edu.vn"))
        assertEquals("2151050123", UserProfile.extractMssv("2151050123.sv@ut.edu.vn"))
        assertEquals("sinhvien", UserProfile.extractMssv("sinhvien@ut.edu.vn"))
    }

    @Test
    fun initials_returnsCorrectInitials() {
        val p1 = UserProfile(displayName = "Minh Anh")
        assertEquals("MA", p1.initials)

        val p2 = UserProfile(displayName = "Thành Lê")
        assertEquals("TL", p2.initials)

        val p3 = UserProfile(displayName = "Minh")
        assertEquals("MI", p3.initials)

        val p4 = UserProfile(displayName = "", email = "2151050001@ut.edu.vn")
        assertEquals("21", p4.initials)
    }

    @Test
    fun profileState_filtersPostsCorrectly() {
        val post1 = HomePost("1", "Ví da", "LOST", "Thư viện", System.currentTimeMillis(), status = "OPEN")
        val post2 = HomePost("2", "Thẻ SV", "LOST", "Căn tin", System.currentTimeMillis(), status = "RESOLVED")
        val post3 = HomePost("3", "Chìa khóa", "FOUND", "Sân thể thao", System.currentTimeMillis(), status = "OPEN")

        val state = ProfileState(
            userPosts = listOf(post1, post2, post3),
            selectedTab = ProfilePostTab.OPEN
        )

        assertEquals(3, state.totalPostsCount)
        assertEquals(2, state.openPostsCount)
        assertEquals(1, state.completedPostsCount)
        assertEquals(2, state.filteredPosts.size)
        assertTrue(state.filteredPosts.all { !it.isResolved })

        val stateCompleted = state.copy(selectedTab = ProfilePostTab.COMPLETED)
        assertEquals(1, stateCompleted.filteredPosts.size)
        assertEquals("2", stateCompleted.filteredPosts.first().id)
        assertTrue(stateCompleted.filteredPosts.first().isResolved)
    }

    @Test
    fun validateDisplayName_checksLengthAndWhitespace() {
        // Empty / Whitespace
        assertEquals("Tên hiển thị không được để trống.", UserProfile.validateDisplayName(""))
        assertEquals("Tên hiển thị không được để trống.", UserProfile.validateDisplayName("   "))

        // Too short (< 2 chars)
        assertEquals("Tên hiển thị phải từ 2 đến 50 ký tự.", UserProfile.validateDisplayName("A"))
        assertEquals("Tên hiển thị phải từ 2 đến 50 ký tự.", UserProfile.validateDisplayName(" B "))

        // Too long (> 50 chars)
        val longName = "A".repeat(51)
        assertEquals("Tên hiển thị quá dài (tối đa 50 ký tự).", UserProfile.validateDisplayName(longName))

        // Valid name (2 to 50 chars)
        assertEquals(null, UserProfile.validateDisplayName("Minh Anh"))
        assertEquals(null, UserProfile.validateDisplayName("  Nguyễn Văn A  "))
        assertEquals(null, UserProfile.validateDisplayName("A".repeat(50)))
    }

    @Test
    fun allowEmailContact_defaultsToTrue() {
        val defaultProfile = UserProfile()
        assertTrue("Mặc định cho phép xem email liên hệ phải là true", defaultProfile.allowEmailContact)
    }
}
