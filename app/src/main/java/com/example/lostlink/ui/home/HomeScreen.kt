package com.example.lostlink.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

internal val PageBackground = Color(0xFFF8F8FC)
internal val Ink = Color(0xFF191D2B)
internal val Muted = Color(0xFF93939E)
internal val Green = Color(0xFF306C38)
internal val Border = Color(0xFFECECF1)

@Composable
fun HomeScreen(
    state: HomeState,
    onPost: (String) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onSignIn: () -> Unit,
    onReport: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(PageBackground).testTag("home-list"),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "intro") {
            Column {
                Text("Mất đồ? Hãy tìm lại.", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Ink)
                Spacer(Modifier.height(7.dp))
                Text("Báo mất hoặc báo tìm thấy để\nnhận hỗ trợ từ mọi người xung\nquanh.",
                    color = Color(0xFF727480), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        item(key = "report") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportTile(true, Modifier.weight(1f)) { onReport(true) }
                ReportTile(false, Modifier.weight(1f)) { onReport(false) }
            }
        }
        item(key = "heading") {
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tin mới đăng", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                TextButton(onClick = {},
                    contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.height(32.dp)) {
                    Text("Xem tất cả", fontSize = 12.sp, color = Green)
                }
            }
        }
        item(key = "tips") { TipsCard() }
        if (!state.allowed) {
            item(key = "auth") { FeedNotice("Đăng nhập để xem tin", "Dùng tài khoản @ut.edu.vn đã xác minh email.", "Đăng nhập", onSignIn) }
        } else {
            items(state.posts, key = { "post:${it.id}" }) { post -> PostCard(post) { onPost(post.id) } }
            if (state.loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Green, strokeWidth = 2.dp)
                }
            }
            if (state.error != null) item(key = "error") {
                FeedNotice("Chưa tải được tin", state.error, "Thử lại", onRefresh)
            }
            if (!state.loading && state.error == null && state.posts.isEmpty()) item(key = "empty") {
                FeedNotice("Chưa có bài đăng", "Những tin mới sẽ xuất hiện tại đây.", "Làm mới", onRefresh)
            }
            if (state.posts.isNotEmpty()) item(key = "more") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.hasMore) {
                        Text("Đã tải ${state.posts.size} tin • Còn tin để xem", fontSize = 11.sp, color = Muted)
                        TextButton(onClick = onMore, enabled = !state.loading) { Text("Xem thêm tin", color = Green) }
                    } else if (!state.loading && state.error == null) {
                        Text("Đã xem hết tin", fontSize = 11.sp, color = Muted)
                    }
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Làm mới", color = Green, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ReportTile(lost: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = if (lost) Color(0xFF42AC78) else Color(0xFF517DF3)
    val pale = if (lost) Color(0xFFF4FFF9) else Color(0xFFF5F7FF)
    val decoration = if (lost) Color(0xFFD8F7E5) else Color(0xFFE0E9FF)
    Surface(onClick = onClick, modifier = modifier.height(150.dp), shape = RoundedCornerShape(22.dp),
        color = pale, border = BorderStroke(1.dp, decoration)) {
        Box {
            Box(Modifier.size(100.dp).align(Alignment.BottomEnd).offset(26.dp, 40.dp).background(decoration, CircleShape))
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.size(43.dp).background(decoration.copy(alpha = .65f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        LineIcon(if (lost) HomeIcon.BOX else HomeIcon.SCAN, accent, Modifier.size(23.dp))
                    }
                    Box(Modifier.size(26.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        LineIcon(HomeIcon.CHEVRON, Color(0xFFA4A9B0), Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(if (lost) "Báo mất đồ" else "Báo tìm thấy", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text(if (lost) "Chia sẻ món đồ bạn\nvừa mất." else "Chia sẻ món đồ bạn\nvừa nhặt được.",
                    fontSize = 11.sp, lineHeight = 16.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun TipsCard() {
    Surface(color = Color.White, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(15.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Mẹo tìm lại đồ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            listOf("Thêm ảnh và mô tả rõ ràng", "Chia sẻ nơi cuối cùng bạn thấy", "Giữ thông tin liên hệ luôn mới").forEachIndexed { index, tip ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tint = listOf(Color(0xFF40BC7A), Color(0xFF628DF9), Color(0xFF8A8E99))[index]
                    Box(Modifier.size(21.dp).background(tint.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) {
                        LineIcon(HomeIcon.CHECK, tint, Modifier.size(13.dp))
                    }
                    Text(tip, color = Color(0xFF525866), fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
fun PostCard(post: HomePost, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("post:${post.id}"),
        color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            PostImage(post.images.firstOrNull(), Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(post.title, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Metadata(HomeIcon.PIN, post.location)
                Metadata(HomeIcon.CLOCK, postedTime(post.createdAt))
            }
            TypeBadge(post, Modifier.align(Alignment.Top))
        }
    }
}

@Composable
internal fun TypeBadge(post: HomePost, modifier: Modifier = Modifier, detail: Boolean = false) {
    val tint = if (post.isLost) Color(0xFF399963) else Color(0xFF587FD7)
    Text(if (detail) (if (post.isLost) "LOST" else "FOUND") else post.typeLabel,
        modifier.clip(RoundedCornerShape(7.dp)).background(if (detail && post.isLost) Color(0xFF98FAAA) else tint.copy(alpha = .12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics { contentDescription = if (post.isLost) "Mất đồ" else "Nhặt được" },
        color = if (detail) Color(0xFF21622F) else tint, fontSize = if (detail) 11.sp else 9.sp,
        lineHeight = if (detail) 13.sp else 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Metadata(icon: HomeIcon, text: String, larger: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (larger) 10.dp else 4.dp)) {
        LineIcon(icon, if (larger) Color(0xFF868383) else Muted, Modifier.size(if (larger) 16.dp else 11.dp))
        Text(text, color = if (larger) Color(0xFF777373) else Muted, fontSize = if (larger) 12.sp else 10.sp,
            lineHeight = if (larger) 17.sp else 13.sp, maxLines = if (larger) 3 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PostImage(url: String?, modifier: Modifier, fit: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(if (fit) Color.White else Color(0xFFF0F1F4)), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank() || failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LineIcon(HomeIcon.PHOTO, Color(0xFFB0B6BA), Modifier.size(24.dp))
                Text(if (failed) "Ảnh bị lỗi" else "Chưa có ảnh", fontSize = 8.sp, color = Muted)
            }
        } else {
            AsyncImage(model = url, contentDescription = "Ảnh đồ vật", modifier = Modifier.fillMaxSize(),
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, onError = { failed = true })
        }
    }
}

@Composable
internal fun FeedNotice(title: String, message: String, action: String, onAction: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(message, fontSize = 12.sp, color = Muted)
            TextButton(onClick = onAction) { Text(action, color = Green) }
        }
    }
}

@Composable
internal fun BottomBar(onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().height(62.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        listOf(HomeIcon.HOME to "Trang chủ", HomeIcon.SEARCH to "Tìm kiếm", HomeIcon.ADD to "Đăng tin",
            HomeIcon.CHAT to "Tin nhắn", HomeIcon.PERSON to "Tài khoản").forEachIndexed { index, (icon, label) ->
            IconButton(onClick = { onTab(index) }, modifier = Modifier.semantics { contentDescription = label }) {
                LineIcon(icon, Color.White, Modifier.size(19.dp))
            }
        }
    }
}
