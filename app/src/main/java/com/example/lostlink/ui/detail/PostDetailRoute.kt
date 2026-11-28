package com.example.lostlink.ui.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.lostlink.ui.home.HomePost

@Composable
fun PostDetailRoute(
    model: PostDetailViewModel,
    postId: String,
    onBack: () -> Unit,
    onEditPost: ((HomePost) -> Unit)? = null,
    currentUserId: String? = null,
    onPostChanged: ((postId: String, action: String) -> Unit)? = null,
    onPostUnavailable: ((postId: String) -> Unit)? = null,
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
    val context = LocalContext.current

    PostDetailScreen(
        state = visible,
        onBack = onBack,
        onRetry = model::retry,
        onEditPost = onEditPost,
        currentUserId = activeUid,
        onPostChanged = onPostChanged,
        onToggleSave = {
            model.toggleSave { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        },
        onOpenReport = {
            model.openReportDialog { alreadyMsg ->
                Toast.makeText(context, alreadyMsg, Toast.LENGTH_SHORT).show()
            }
        },
        onDismissReport = model::dismissReportDialog,
        onSelectReportReason = model::selectReportReason,
        onUpdateReportDescription = model::updateReportDescription,
        onSubmitReport = {
            model.submitReport(
                onSuccess = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                onAlreadyReported = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                onPostUnavailable = { unavailableId, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onPostUnavailable?.invoke(unavailableId)
                },
                onError = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
            )
        },
        onDismissReportNotice = model::dismissReportNotice,
    )
}
