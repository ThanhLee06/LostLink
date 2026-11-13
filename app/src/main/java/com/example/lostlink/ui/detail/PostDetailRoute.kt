
package com.example.lostlink.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.example.lostlink.ui.home.HomePost

@Composable
fun PostDetailRoute(
    model: PostDetailViewModel,
    postId: String,
    onBack: () -> Unit,
    onEditPost: ((HomePost) -> Unit)? = null,
    currentUserId: String? = null,
    onPostChanged: ((postId: String, action: String) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    LaunchedEffect(postId) {
        model.open(postId)
    }

    DisposableEffect(postId) {
        onDispose {
            model.clearSelection()
        }
    }

    val visible = model.state.takeIf {
        it.id == postId
    } ?: PostDetailState(postId, DetailStatus.LOADING)

    val activeUid = currentUserId ?: model.currentUserId

    PostDetailScreen(
        state = visible,
        onBack = onBack,
        onRetry = model::retry,
        onEditPost = onEditPost,
        currentUserId = activeUid,
        onPostChanged = onPostChanged
    )
}

