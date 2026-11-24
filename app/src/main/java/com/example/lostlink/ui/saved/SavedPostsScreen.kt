package com.example.lostlink.ui.saved

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lostlink.ui.home.FeedNotice
import com.example.lostlink.ui.home.HomeIcon
import com.example.lostlink.ui.home.HomePostCard
import com.example.lostlink.ui.home.HomeTokens
import com.example.lostlink.ui.home.LineIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPostsScreen(
    currentUserId: String?,
    onBack: () -> Unit,
    onPostClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SavedPostsViewModel = viewModel(),
) {
    val observedState by viewModel.state.collectAsState()
    val state = if (viewModel.isBoundTo(currentUserId)) observedState else SavedPostsState()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    DisposableEffect(currentUserId) {
        viewModel.bindUser(currentUserId)
        onDispose { }
    }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("saved-posts-screen"),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Tin đã lưu", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Quay lại" },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.loading,
                        modifier = Modifier.semantics { contentDescription = "Làm mới tin đã lưu" },
                    ) {
                        LineIcon(HomeIcon.REFRESH, colors.onSurface, Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colors.background)
        ) {
            val gutter = if (maxWidth >= 600.dp) 32.dp else HomeTokens.Gutter

            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = HomeTokens.MaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.loading && state.items.isEmpty() -> {
                        item(key = "loading") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Đang tải tin đã lưu…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    state.error != null && state.items.isEmpty() -> {
                        item(key = "error") {
                            FeedNotice(
                                title = "Chưa tải được tin đã lưu",
                                message = state.error,
                                action = "Thử lại",
                                onAction = { viewModel.refresh() },
                                error = true,
                                enabled = !state.loading,
                            )
                        }
                    }

                    state.items.isEmpty() -> {
                        item(key = "empty") {
                            FeedNotice(
                                title = "Chưa có tin đã lưu",
                                message = "Nhấn biểu tượng trái tim trên bài đăng để lưu và xem lại tại đây.",
                                action = "Quay lại",
                                onAction = onBack,
                            )
                        }
                    }

                    else -> {
                        if (state.error != null) {
                            item(key = "sync_error") {
                                FeedNotice(
                                    title = "Chưa thể đồng bộ mới nhất",
                                    message = state.error,
                                    action = "Thử lại",
                                    onAction = { viewModel.refresh() },
                                    error = true,
                                    enabled = !state.loading,
                                )
                            }
                        }

                        items(state.items, key = { "saved:${it.postId}" }) { item ->
                            val isPending = item.postId in state.pendingPostIds
                            when (item) {
                                is SavedPostItem.Available -> {
                                    HomePostCard(
                                        post = item.post,
                                        onClick = { onPostClick(item.post.id) },
                                        isSaved = item.postId in state.savedPostIds,
                                        isSavePending = isPending,
                                        onToggleSave = { viewModel.toggleSave(item.postId) },
                                    )
                                }

                                is SavedPostItem.Unavailable -> {
                                    UnavailableSavedPostCard(
                                        postId = item.postId,
                                        isPending = isPending,
                                        onRemove = { viewModel.removeSavedPost(item.postId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableSavedPostCard(
    postId: String,
    isPending: Boolean,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("unavailable-saved:$postId"),
        color = colors.surface,
        shape = HomeTokens.CardShape,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HomeTokens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Bài viết hiện không khả dụng",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                Text(
                    text = "Bài viết đã bị xóa hoặc không còn được hiển thị.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRemove,
                enabled = !isPending,
                modifier = Modifier.testTag("remove-unavailable:$postId"),
            ) {
                Text("Gỡ khỏi danh sách")
            }
        }
    }
}
