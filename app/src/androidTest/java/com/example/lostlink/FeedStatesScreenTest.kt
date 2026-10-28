package com.example.lostlink

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.lostlink.ui.home.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeedStatesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val old = HomePost("old", "Ví đã tải trước đó", "LOST", "Thư viện", 100)
    private fun scroll(text: String) = compose.onNodeWithTag("home-list").performScrollToNode(hasText(text))

    @Test fun initialLoadingDoesNotShowEmptyOrError() {
        compose.setContent { MaterialTheme { HomeScreen(HomeState(allowed = true, loading = true), {}, {}, {}, {}, {}) } }
        scroll("Đang tải tin…")
        compose.onNodeWithText("Đang tải tin…").assertIsDisplayed()
        compose.onNodeWithText("Đang tải…").assertIsNotEnabled()
        compose.onNodeWithText("Chưa có tin").assertDoesNotExist()
        compose.onNodeWithText("Thử lại").assertDoesNotExist()
    }

    @Test fun successfulEmptyStateCallsCreatePost() {
        var created = false
        compose.setContent { MaterialTheme { HomeScreen(HomeState(allowed = true), {}, {}, {}, {}, { created = it }) } }
        scroll("Chưa có tin")
        compose.onNodeWithText("Chưa có tin").assertIsDisplayed()
        scroll("Đăng tin")
        compose.onNodeWithText("Đăng tin").performClick()
        assertEquals(true, created)
    }

    @Test fun firstLoadFailureHasRetryAndDoesNotClaimEmpty() {
        var retries = 0
        compose.setContent { MaterialTheme { HomeScreen(HomeState(allowed = true, error = "Không có kết nối mạng"),
            {}, {}, {}, {}, {}, onRetry = { retries++ }) } }
        scroll("Thử lại")
        compose.onNodeWithText("Thử lại").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Chưa có tin").assertDoesNotExist()
    }

    @Test fun failedRefreshKeepsPostAndDisplaysStaleNotice() {
        compose.setContent { MaterialTheme { HomeScreen(HomeState(allowed = true, posts = listOf(old),
            error = "Bạn đang xem danh sách đã tải trước đó."), {}, {}, {}, {}, {}) } }
        scroll("Bạn đang xem danh sách đã tải trước đó.")
        compose.onNodeWithText("Bạn đang xem danh sách đã tải trước đó.").assertIsDisplayed()
        scroll(old.title)
        compose.onNodeWithText(old.title).assertIsDisplayed()
        compose.onNodeWithText("Chưa có tin").assertDoesNotExist()
    }

    @Test fun refreshActionIsAvailableWhenFeedHasNoPosts() {
        var refreshes = 0
        compose.setContent { MaterialTheme { HomeScreen(HomeState(allowed = true), {}, { refreshes++ }, {}, {}, {}) } }
        scroll("Làm mới")
        compose.onNodeWithText("Làm mới").performClick()
        assertEquals(1, refreshes)
    }
}
