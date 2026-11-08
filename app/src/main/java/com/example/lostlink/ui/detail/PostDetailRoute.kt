package com.example.lostlink.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun PostDetailRoute(
    model: PostDetailViewModel,
    postId: String,
    onBack: () -> Unit,
    currentUserId: String? = null
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(postId) { model.open(postId) }
    DisposableEffect(postId) { onDispose { model.clearSelection() } }
    val visible = model.state.takeIf { it.id == postId } ?: PostDetailState(postId, DetailStatus.LOADING)
    val activeUid = currentUserId ?: model.currentUserId
    PostDetailScreen(
        state = visible,
        onBack = onBack,
        onRetry = model::retry,
        currentUserId = activeUid
    )
}
