package com.example.lostlink.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lostlink.ui.home.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PostDetailScreen(
    state: PostDetailState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    currentUserId: String? = null
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).testTag("post-detail")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(HomeTokens.Target)
                .semantics { contentDescription = "Quay lại trang chủ" }) {
                LineIcon(HomeIcon.BACK, colors.onSurface, Modifier.size(24.dp))
            }
            Text("Chi tiết tin", style = MaterialTheme.typography.titleMedium, color = colors.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
            Spacer(Modifier.width(HomeTokens.Target))
        }
        Box(Modifier.weight(1f)) {
            when (state.status) {
                DetailStatus.IDLE, DetailStatus.LOADING -> Column(Modifier.fillMaxSize().padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Đang tải bài đăng…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                DetailStatus.READY -> state.detail?.let { detail -> key(detail.post.id) { DetailContent(detail, currentUserId) } }
                else -> DetailFailure(state, onBack, onRetry)
            }
        }
    }
}

@Composable
private fun DetailContent(detail: PostDetail, currentUserId: String? = null) {
    val post = detail.post
    val context = LocalContext.current
    val isAuthor = !currentUserId.isNullOrBlank() && post.authorId.isNotBlank() && currentUserId == post.authorId
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp && LocalDensity.current.fontScale <= 1.3f
        val gutter = if (maxWidth >= 600.dp) 32.dp else HomeTokens.Gutter
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxHeight().widthIn(max = 1040.dp)
            .fillMaxWidth().align(Alignment.TopCenter).testTag("detail-list"),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "overview") {
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    PostDetailGallery(post.images, post.title, Modifier.weight(1f), wide = true)
                    DetailSummary(post, Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PostDetailGallery(post.images, post.title)
                    DetailSummary(post)
                }
            }
            item(key = "facts") { DetailFacts(detail) }
            item(key = "description") {
                DetailSection("Mô tả") {
                    Text(post.description.ifBlank { "Người đăng chưa thêm mô tả." },
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("detail-description"))
                }
            }
            item(key = "author") {
                DetailSection("Liên hệ người đăng") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                LineIcon(HomeIcon.PERSON, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(24.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = post.authorName.ifBlank { "Sinh viên UTH" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("detail-author")
                                    )
                                    if (isAuthor) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.testTag("author-self-badge")
                                        ) {
                                            Text(
                                                text = "Bài của bạn",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                if (isAuthor) {
                                    Text(
                                        text = "Bạn là tác giả bài viết này",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("author-self-notice")
                                    )
                                } else if (post.allowContact && post.contactEmail.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        LineIcon(HomeIcon.EMAIL, MaterialTheme.colorScheme.primary, Modifier.size(16.dp))
                                        Text(
                                            text = post.contactEmail,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("author-email-text")
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Người đăng không công khai email liên hệ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("author-email-hidden")
                                    )
                                }
                            }
                        }

                        // Ẩn khu vực nút liên hệ nếu người xem chính là tác giả bài viết
                        if (!isAuthor && post.allowContact && post.contactEmail.isNotBlank()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        try {
                                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                                data = Uri.parse("mailto:${post.contactEmail}")
                                                putExtra(Intent.EXTRA_SUBJECT, "LostLink - Liên hệ về bài đăng: ${post.title}")
                                            }
                                            context.startActivity(Intent.createChooser(emailIntent, "Gửi email cho người đăng"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Không tìm thấy ứng dụng email", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("btn-send-email"),
                                    shape = HomeTokens.ImageShape
                                ) {
                                    LineIcon(HomeIcon.EMAIL, MaterialTheme.colorScheme.onPrimary, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Gửi Email")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Email người đăng", post.contactEmail)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Đã sao chép địa chỉ email", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("btn-copy-email"),
                                    shape = HomeTokens.ImageShape
                                ) {
                                    Text("Sao chép")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailSummary(post: HomePost, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            TypeBadge(post)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(HomeIcon.CLOCK, colors.onSurfaceVariant, Modifier.size(16.dp))
                Text(postedTime(post.createdAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Text(post.title, style = MaterialTheme.typography.headlineMedium, color = colors.onSurface,
            modifier = Modifier.testTag("detail-title").semantics { heading() })
        Text("Mã tin: ${post.id}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        val status = when (post.status.uppercase(Locale.ROOT)) {
            "OPEN" -> null
            "RESOLVED" -> "Đã tìm được hoặc trả lại đồ"
            "CLOSED" -> "Tin đã đóng"
            else -> "Trạng thái: ${post.status}"
        }
        if (status != null) Text(status, color = colors.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DetailFacts(detail: PostDetail) {
    val post = detail.post
    DetailSection("Thông tin món đồ") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailField(HomeIcon.BOX, "Danh mục", detail.category.ifBlank { "Chưa cập nhật danh mục" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailField(HomeIcon.PIN, "Khu vực", post.location)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailField(HomeIcon.CALENDAR, if (post.isLost) "Thời gian mất" else "Thời gian nhặt",
                post.occurredAt.ifBlank { "Người đăng chưa ghi thời gian" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailField(HomeIcon.CLOCK, "Đăng lúc", SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.forLanguageTag("vi-VN"))
                .format(Date(post.createdAt)))
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Surface(color = colors.surface, shape = HomeTokens.CardShape, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Box(Modifier.fillMaxWidth().padding(20.dp)) { content() }
        }
    }
}

@Composable
private fun DetailField(icon: HomeIcon, label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) {}) {
        LineIcon(icon, colors.primary, Modifier.padding(top = 2.dp).size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DetailFailure(state: PostDetailState, onBack: () -> Unit, onRetry: () -> Unit) {
    val icon = when (state.status) {
        DetailStatus.NOT_FOUND, DetailStatus.INVALID_ID -> HomeIcon.SEARCH
        DetailStatus.ACCESS_DENIED -> HomeIcon.LOCK
        else -> HomeIcon.REFRESH
    }
    val (title, message) = when (state.status) {
        DetailStatus.NOT_FOUND -> "Không tìm thấy bài đăng" to "Bài đăng không tồn tại hoặc đã được xóa. Bạn có thể quay lại để xem tin khác."
        DetailStatus.ACCESS_DENIED -> "Chưa thể xem bài đăng" to "Tài khoản hiện tại không có quyền đọc tin này. Hãy quay lại để kiểm tra tài khoản."
        DetailStatus.NETWORK_ERROR -> "Chưa kết nối được" to "Không tải được bài đăng. Kiểm tra kết nối mạng rồi thử lại."
        DetailStatus.INVALID_ID -> "Mã bài đăng không hợp lệ" to "Hãy quay lại trang chủ và chọn bài đăng khác."
        DetailStatus.INVALID_DATA -> "Chưa đọc được nội dung" to "Bài đăng thiếu thông tin cần thiết. Bạn có thể thử tải lại sau."
        else -> "Chưa tải được bài đăng" to "Có lỗi khi đọc nội dung. Vui lòng thử lại."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LazyColumn(Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("detail-error"), contentPadding = PaddingValues(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { LineIcon(icon,
                MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(40.dp)) }
            item { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }) }
            item { Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center) }
            if (state.canRetry) item { Button(onClick = onRetry, shape = HomeTokens.ImageShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = HomeTokens.Target)) { Text("Thử lại") } }
            item { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = HomeTokens.Target)) { Text("Về trang chủ") } }
        }
    }
}
