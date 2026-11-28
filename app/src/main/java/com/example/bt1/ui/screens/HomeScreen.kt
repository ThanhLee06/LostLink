package com.example.bt1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bt1.data.AuthViewModel
import com.example.bt1.data.TaskItem
import com.example.bt1.ui.components.getUserInitials

@Composable
fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = if (isSelected) Color(0xFF2563EB) else Color(0xFF94A3B8)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun BottomNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onFabClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            BottomNavItem(
                icon = Icons.Default.Home,
                label = "Trang chủ",
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )

            BottomNavItem(
                icon = Icons.Default.List,
                label = "Danh sách",
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )

            FloatingActionButton(
                onClick = onFabClick,
                containerColor = Color(0xFF2563EB),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Thêm công việc"
                )
            }

            BottomNavItem(
                icon = Icons.Default.Notifications,
                label = "Thông báo",
                isSelected = selectedTab == 3,
                onClick = { onTabSelected(3) }
            )

            BottomNavItem(
                icon = Icons.Default.Person,
                label = "Cài đặt",
                isSelected = selectedTab == 4,
                onClick = { onTabSelected(4) }
            )
        }
    }
}

@Composable
fun CategoryCard(
    title: String,
    remainingText: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF1E293B)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = remainingText,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun TaskRowItem(
    task: TaskItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (task.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (task.isCompleted) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (task.isCompleted) Color(0xFF94A3B8) else Color(0xFF1E293B),
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (task.category == "Học tập") Color(0xFF0284C7) else Color(0xFFEA580C))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${task.category}  ${task.time}",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Xóa",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAddTask: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Học tập") }
    var time by remember { mutableStateOf("09:00") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm công việc mới", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Tên công việc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = category == "Học tập",
                        onClick = { category = "Học tập" },
                        label = { Text("Học tập") }
                    )
                    FilterChip(
                        selected = category == "Cuộc sống",
                        onClick = { category = "Cuộc sống" },
                        label = { Text("Cuộc sống") }
                    )
                }

                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Thời gian (vd: 09:00)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddTask(title, category, time) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Thêm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
fun HomeScreen(
    authViewModel: AuthViewModel,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToTaskList: () -> Unit = {}
) {
    val currentUser by authViewModel.currentUserProfile.collectAsState()
    val tasks by authViewModel.tasks.collectAsState()
    val welcomeBannerText by authViewModel.welcomeBannerText.collectAsState()

    val completedCount = tasks.count { it.isCompleted }
    val totalCount = tasks.size
    val progressPercent = if (totalCount > 0) (completedCount * 100) / totalCount else 0

    val hocTapRemaining = tasks.count { it.displayCategory == "Học tập" && !it.isCompleted }
    val cuocSongRemaining = tasks.count { it.displayCategory == "Cuộc sống" && !it.isCompleted }

    var showAddTaskDialog by remember { mutableStateOf(false) }
    var selectedBottomTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Color(0xFFF8FAFC),
        bottomBar = {
            BottomNavigationBar(
                selectedTab = selectedBottomTab,
                onTabSelected = { tab ->
                    selectedBottomTab = tab
                    if (tab == 1) {
                        onNavigateToTaskList()
                    } else if (tab == 3) {
                        onNavigateToNotifications()
                    } else if (tab == 4) {
                        onNavigateToProfile()
                    }
                },
                onFabClick = { showAddTaskDialog = true }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // 1. Top Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onNavigateToProfile() }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2B52BA)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = getUserInitials(currentUser?.displayName ?: "Minh Anh"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Thứ Ba, 20 tháng 10",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                            Text(
                                text = "Chào ${currentUser?.displayName ?: "Minh Anh"}!",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { onNavigateToNotifications() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Notification",
                                tint = Color(0xFF1E293B)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDC2626))
                                .align(Alignment.TopEnd)
                                .offset(x = (-4).dp, y = 4.dp)
                        )
                    }
                }
            }

            // Remote Config Announcement Banner
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDBEAFE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Thông báo hệ thống (Remote Config)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF1D4ED8)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = welcomeBannerText,
                                fontSize = 13.sp,
                                color = Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }

            // 2. Today Progress Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF2B52BA),
                                        Color(0xFF3B82F6)
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tiến độ hôm nay",
                                    color = Color(0xFFDBEAFE),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$completedCount/$totalCount việc",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (progressPercent >= 50) "Đã hơn nửa chặng, cố lên!" else "Hãy hoàn thành thêm công việc!",
                                    color = Color(0xFFEFF6FF),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(68.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { progressPercent / 100f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = Color.White,
                                    strokeWidth = 6.dp,
                                    trackColor = Color(0x40FFFFFF)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0x30FFFFFF),
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "$progressPercent%",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Categories Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CategoryCard(
                        title = "Học tập",
                        remainingText = "$hocTapRemaining việc còn lại",
                        icon = Icons.Default.School,
                        iconBg = Color(0xFFE0F2FE),
                        iconTint = Color(0xFF0284C7),
                        modifier = Modifier.weight(1f)
                    )

                    CategoryCard(
                        title = "Cuộc sống",
                        remainingText = "$cuocSongRemaining việc còn lại",
                        icon = Icons.Default.Spa,
                        iconBg = Color(0xFFFFEDD5),
                        iconTint = Color(0xFFEA580C),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. "Việc hôm nay" Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Việc hôm nay",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )

                    Text(
                        text = "Xem tất cả",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2B52BA),
                        modifier = Modifier.clickable { onNavigateToTaskList() }
                    )
                }
            }

            // 5. Task Items
            items(tasks) { task ->
                TaskRowItem(
                    task = task,
                    onToggle = { authViewModel.toggleTaskCompletion(task.id) },
                    onDelete = { authViewModel.deleteTask(task.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onAddTask = { title, cat, time ->
                authViewModel.addTask(title, cat, time)
                showAddTaskDialog = false
            }
        )
    }
}
