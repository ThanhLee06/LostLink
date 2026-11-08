package com.example.lostlink.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.example.lostlink.ui.home.HomePost

@Composable
fun PostDetailRoute(
    model: PostDetailViewModel,
    postId: String,
    onBack: () -> Unit,
    onEditPost: ((HomePost) -> Unit)? = null
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(postId) { model.open(postId) }
    DisposableEffect(postId) { onDispose { model.clearSelection() } }
    val visible = model.state.takeIf { it.id == postId } ?: PostDetailState(postId, DetailStatus.LOADING)
    PostDetailScreen(visible, onBack, model::retry, onEditPost)
}
