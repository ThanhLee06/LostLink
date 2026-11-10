package com.example.lostlink.ui.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lostlink.ui.home.FeedNotice
import com.example.lostlink.ui.home.HomeIcon
import com.example.lostlink.ui.home.HomePostCard
import com.example.lostlink.ui.home.HomeTokens
import com.example.lostlink.ui.home.LineIcon

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onPost: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    var showEditNameDialog by remember { mutableStateOf(false) }
    var showPersonalInfoDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val gutter = if (maxWidth >= 600.dp) 32.dp else HomeTokens.Gutter

        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = HomeTokens.MaxWidth)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header title
            item(key = "header") {
                Text(
                    text = "Hồ sơ",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }

            // Profile Card (Header card with info + stats)
            item(key = "profile_card") {
                ProfileHeaderCard(
                    profile = state.profile,
                    totalPosts = state.totalPostsCount,
                    openPosts = state.openPostsCount,
                    completedPosts = state.completedPostsCount,
                    onEditName = { showEditNameDialog = true }
                )
            }

            // Section: Tin của tôi (My Posts)
            item(key = "my_posts_header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Tin của tôi",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )

                    // Tabs: Đang mở (X) | Hoàn tất (Y)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = state.selectedTab == ProfilePostTab.OPEN,
                            onClick = { viewModel.selectTab(ProfilePostTab.OPEN) },
                            label = { Text("Đang mở (${state.openPostsCount})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.onPrimaryContainer
                            )
                        )
                        FilterChip(
                            selected = state.selectedTab == ProfilePostTab.COMPLETED,
                            onClick = { viewModel.selectTab(ProfilePostTab.COMPLETED) },
                            label = { Text("Hoàn tất (${state.completedPostsCount})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            // Loading / Error state or Posts List
            if (state.loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Đang tải thông tin hồ sơ...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else if (state.error != null) {
                item(key = "error") {
                    FeedNotice(
                        title = "Chưa thể tải dữ liệu",
                        message = state.error,
                        action = "Thử lại",
                        onAction = { viewModel.loadProfileAndPosts() },
                        error = true
                    )
                }
            } else if (state.filteredPosts.isEmpty()) {
                item(key = "empty_posts") {
                    FeedNotice(
                        title = if (state.selectedTab == ProfilePostTab.OPEN) "Không có tin đang mở" else "Không có tin đã hoàn tất",
                        message = if (state.selectedTab == ProfilePostTab.OPEN)
                            "Các bài đăng tìm đồ hoặc báo nhặt được đang xử lý sẽ xuất hiện ở đây."
                        else "Các bài đăng đã tìm lại được đồ hoặc đã trao trả thành công sẽ xuất hiện ở đây.",
                        action = "Làm mới",
                        onAction = { viewModel.loadProfileAndPosts() }
                    )
                }
            } else {
                items(state.filteredPosts, key = { "user_post:${it.id}" }) { post ->
                    HomePostCard(post = post, onClick = { onPost(post.id) })
                }
            }

            // Options List Section (Thông tin cá nhân, Quyền riêng tư email, Thông báo, Trợ giúp, Đăng xuất)
            item(key = "options_section") {
                Surface(
                    color = colors.surface,
                    shape = HomeTokens.CardShape,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        OptionRow(
                            icon = HomeIcon.BADGE,
                            title = "Thông tin cá nhân",
                            onClick = { showPersonalInfoDialog = true }
                        )
                        HorizontalDivider(color = colors.outlineVariant)
                        OptionSwitchRow(
                            icon = HomeIcon.EMAIL,
                            title = "Hiển thị email trường để liên hệ",
                            subtitle = when {
                                state.isSavingEmailContact -> "Đang lưu cài đặt liên hệ…"
                                state.emailContactLoaded -> "Cho phép người khác xem email @ut.edu.vn trên bài đăng để dễ dàng liên hệ tìm đồ."
                                state.loading -> "Đang tải cài đặt liên hệ…"
                                else -> "Chưa tải được cài đặt liên hệ. Vui lòng thử lại."
                            },
                            checked = state.emailContactLoaded && state.profile.allowEmailContact,
                            enabled = state.emailContactLoaded && !state.loading && !state.isSavingEmailContact,
                            onCheckedChange = {
                                viewModel.toggleAllowEmailContact { result ->
                                    result.onSuccess { enabled ->
                                        val msg = if (enabled) "Đã bật hiển thị email liên hệ" else "Đã tắt hiển thị email liên hệ"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(context, err.message ?: "Không thể lưu cài đặt", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant)
                        OptionRow(
                            icon = HomeIcon.BELL,
                            title = "Thông báo",
                            trailingText = if (state.notificationsEnabled) "Đang bật" else "Đã tắt",
                            onClick = {
                                viewModel.toggleNotifications()
                                val msg = if (!state.notificationsEnabled) "Đã bật thông báo" else "Đã tắt thông báo"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(color = colors.outlineVariant)
                        OptionRow(
                            icon = HomeIcon.HELP,
                            title = "Trợ giúp & hỗ trợ",
                            onClick = { showHelpDialog = true }
                        )
                    }
                }
            }

            // Logout Action
            item(key = "logout_button") {
                Surface(
                    onClick = { showSignOutConfirmDialog = true },
                    color = colors.surface,
                    shape = HomeTokens.CardShape,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LineIcon(HomeIcon.LOGOUT, colors.error, Modifier.size(20.dp))
                        Text(
                            text = "Đăng xuất",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.error
                        )
                    }
                }
            }

            item(key = "bottom_space") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Dialogs
    if (showEditNameDialog) {
        EditNameDialog(
            currentName = state.profile.displayName,
            isSaving = state.isSavingName,
            onDismiss = { showEditNameDialog = false },
            onSave = { newName ->
                viewModel.updateDisplayName(newName) { result ->
                    result.onSuccess {
                        Toast.makeText(context, "Cập nhật tên hiển thị thành công!", Toast.LENGTH_SHORT).show()
                        showEditNameDialog = false
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: "Cập nhật tên thất bại. Vui lòng thử lại.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showPersonalInfoDialog) {
        PersonalInfoDialog(
            profile = state.profile,
            onDismiss = { showPersonalInfoDialog = false }
        )
    }

    if (showHelpDialog) {
        HelpSupportDialog(
            onDismiss = { showHelpDialog = false }
        )
    }

    if (showSignOutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmDialog = false },
            title = { Text("Tài khoản") },
            text = { Text("Bạn có muốn đăng xuất khỏi LostLink?") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirmDialog = false
                    onSignOut()
                }) {
                    Text("Đăng xuất", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirmDialog = false }) {
                    Text("Ở lại")
                }
            }
        )
    }
}

@Composable
private fun ProfileHeaderCard(
    profile: UserProfile,
    totalPosts: Int,
    openPosts: Int,
    completedPosts: Int,
    onEditName: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        color = colors.surface,
        shape = HomeTokens.CardShape,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // User identity row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Initials Circle Avatar
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(colors.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.initials,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = profile.displayName.ifBlank { "Sinh viên UTH" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = colors.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "MSSV: ${profile.mssv}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = profile.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                // Chỉnh sửa button
                OutlinedButton(
                    onClick = onEditName,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 34.dp)
                ) {
                    Text("Chỉnh sửa", style = MaterialTheme.typography.labelMedium)
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatItem(count = totalPosts, label = "tin đã đăng")
                VerticalDivider(modifier = Modifier.height(32.dp), color = colors.outlineVariant)
                StatItem(count = openPosts, label = "đang mở")
                VerticalDivider(modifier = Modifier.height(32.dp), color = colors.outlineVariant)
                StatItem(count = completedPosts, label = "hoàn tất")
            }
        }
    }
}

@Composable
private fun StatItem(count: Int, label: String) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionRow(
    icon: HomeIcon,
    title: String,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LineIcon(icon, colors.onSurfaceVariant, Modifier.size(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
        LineIcon(HomeIcon.CHEVRON, colors.onSurfaceVariant, Modifier.size(18.dp))
    }
}

@Composable
private fun OptionSwitchRow(
    icon: HomeIcon,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LineIcon(icon, colors.onSurfaceVariant, Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun EditNameDialog(
    currentName: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var nameText by remember { mutableStateOf(currentName) }
    val cleanName = nameText.trim().replace(Regex("""[\r\n\t]"""), " ")
    val validationError = remember(nameText) {
        when {
            cleanName.isEmpty() -> "Tên hiển thị không được để trống."
            cleanName.length < 2 -> "Tên hiển thị phải từ 2 đến 50 ký tự."
            cleanName.length > 50 -> "Tên hiển thị quá dài (tối đa 50 ký tự)."
            else -> null
        }
    }
    val isValid = validationError == null

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Chỉnh sửa tên hiển thị", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { if (it.length <= 60) nameText = it },
                    label = { Text("Tên hiển thị") },
                    placeholder = { Text("Nhập tên hiển thị mới...") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = nameText.isNotBlank() && !isValid,
                    supportingText = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (nameText.isNotBlank() && validationError != null) {
                                Text(validationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Từ 2 đến 50 ký tự", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${cleanName.length}/50", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isValid && !isSaving) onSave(nameText) },
                enabled = isValid && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Đang lưu...")
                } else {
                    Text("Lưu thay đổi")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("Hủy")
            }
        }
    )
}

@Composable
private fun PersonalInfoDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thông tin cá nhân", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("Mã số sinh viên (MSSV)", profile.mssv, isHighlight = true)
                DetailRow("Email trường (@ut.edu.vn)", profile.email)
                DetailRow("Tên hiển thị", profile.displayName)
                DetailRow("Ngày tham gia", profile.joinDateFormatted)
                DetailRow(
                    "Xác minh email",
                    if (profile.isEmailVerified) "Đã xác minh" else "Chưa xác minh",
                    isVerified = profile.isEmailVerified
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isHighlight: Boolean = false,
    isVerified: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                style = if (isHighlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Medium,
                color = if (isHighlight) colors.primary else colors.onSurface
            )
            if (isVerified) {
                LineIcon(HomeIcon.CHECK, colors.primary, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun HelpSupportDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trợ giúp & Hỗ trợ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "LostLink là ứng dụng hỗ trợ sinh viên UTH tìm kiếm đồ thất lạc trong khuôn viên trường.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• Email hỗ trợ: support@ut.edu.vn\n• Hotline: 1900 xxxx\n• Phòng làm việc: Văn phòng Đoàn Thanh niên UTH",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}
