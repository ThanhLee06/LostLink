package com.example.lostlink.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun PostCard(post: HomePost, onClick: () -> Unit) = HomePostCard(post, onClick)

/** A missing navigation handler produces a readable static card, not a fake button. */
@Composable
internal fun HomePostCard(post: HomePost, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale > 1.3f
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(HomeTokens.CardPadding)) {
            if (largeText) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    PostImage(post.images.firstOrNull(), Modifier.size(112.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { TypeBadge(post) }
                }
                Spacer(Modifier.height(16.dp))
                PostText(post, showBadge = false)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PostImage(post.images.firstOrNull(), Modifier.size(88.dp))
                    Box(Modifier.weight(1f)) { PostText(post, showBadge = true) }
                }
            }
        }
    }
    val modifier = Modifier.fillMaxWidth().testTag("post:${post.id}")
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.semantics {
        this.onClick(label = "Xem chi tiết ${post.title}", action = { onClick(); true })
    }, color = colors.surface, shape = HomeTokens.CardShape, border = BorderStroke(1.dp, colors.outlineVariant), content = body)
    else Surface(modifier = modifier.semantics(mergeDescendants = true) {}, color = colors.surface,
        shape = HomeTokens.CardShape, border = BorderStroke(1.dp, colors.outlineVariant), content = body)
}

@Composable
private fun PostText(post: HomePost, showBadge: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showBadge) TypeBadge(post)
        Text(post.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("post-title:${post.id}"))
        Metadata(HomeIcon.PIN, post.location)
        Metadata(HomeIcon.CLOCK, "Đăng ${postedTime(post.createdAt)}")
    }
}

@Composable
internal fun TypeBadge(post: HomePost, modifier: Modifier = Modifier, detail: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, color = if (post.isLost) colors.primaryContainer else colors.secondaryContainer,
        contentColor = if (post.isLost) colors.onPrimaryContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp)) {
        Text(if (post.isLost) "Mất đồ" else "Nhặt được", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Metadata(icon: HomeIcon, text: String, larger: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LineIcon(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (larger) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun PostImage(url: String?, modifier: Modifier, fit: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(HomeTokens.ImageShape).background(colors.surfaceContainer), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank() || failed) Column(
            Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            LineIcon(HomeIcon.PHOTO, colors.onSurfaceVariant, Modifier.size(24.dp))
            Text(if (failed) "Ảnh bị lỗi" else "Chưa có ảnh", color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        } else AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(HomeTokens.ImageFadeMillis).build(),
            contentDescription = "Ảnh đồ vật", modifier = Modifier.fillMaxSize(),
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, onError = { failed = true },
        )
    }
}

@Composable
internal fun FeedNotice(
    title: String, message: String, action: String, onAction: () -> Unit,
    error: Boolean = false, enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (error) colors.errorContainer else colors.surface,
        shape = HomeTokens.CardShape, border = if (error) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = if (error) colors.onErrorContainer else colors.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(message, color = if (error) colors.onErrorContainer else colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = HomeTokens.Target),
                contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action) }
        }
    }
}
