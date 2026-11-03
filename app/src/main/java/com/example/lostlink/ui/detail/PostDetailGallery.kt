package com.example.lostlink.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.lostlink.ui.home.HomeIcon
import com.example.lostlink.ui.home.HomeTokens
import com.example.lostlink.ui.home.LineIcon
import kotlinx.coroutines.launch

@Composable
internal fun PostDetailGallery(images: List<String>, title: String, modifier: Modifier = Modifier, wide: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { images.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val frame = Modifier.fillMaxWidth().then(if (images.isEmpty()) Modifier.heightIn(min = 156.dp)
            else Modifier.aspectRatio(if (wide) 16f / 10f else 4f / 3f))
        Box(frame.clip(HomeTokens.CardShape).background(colors.surfaceContainer)) {
            if (images.isEmpty()) PhotoPlaceholder("Tin này chưa có ảnh") else {
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("detail-photos").semantics {
                    stateDescription = "Ảnh ${pager.currentPage + 1} trên ${images.size}"
                }) { index -> DetailPhoto(images[index], "Ảnh ${index + 1} của $title") }
                Surface(color = colors.scrim.copy(alpha = 0.8f), contentColor = Color.White, shape = HomeTokens.ImageShape,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Text("${pager.currentPage + 1} / ${images.size}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (images.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0, modifier = Modifier.size(HomeTokens.Target)
                    .semantics { contentDescription = "Ảnh trước" }) {
                LineIcon(HomeIcon.CHEVRON, LocalContentColor.current, Modifier.size(22.dp).rotate(180f))
            }
            Text("Vuốt để xem ảnh", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = pager.currentPage < images.lastIndex, modifier = Modifier.size(HomeTokens.Target)
                    .semantics { contentDescription = "Ảnh tiếp theo" }) {
                LineIcon(HomeIcon.CHEVRON, LocalContentColor.current, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DetailPhoto(url: String, description: String) {
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) PhotoPlaceholder("Không tải được ảnh") else {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(HomeTokens.ImageFadeMillis).build(),
                contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                onSuccess = { loading = false }, onError = { loading = false; failed = true })
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PhotoPlaceholder(title: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        LineIcon(HomeIcon.PHOTO, colors.onSurfaceVariant, Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}
