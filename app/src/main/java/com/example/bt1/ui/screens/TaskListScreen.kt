package com.example.bt1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bt1.data.AuthViewModel
import com.example.bt1.data.TaskItem

@Composable
fun TaskListScreen(
    authViewModel: AuthViewModel,
    onNavigateToHome: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val tasks by authViewModel.tasks.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("Tất cả") }
    var showAddTaskDialog by remember { mutableStateOf(false) }

    // Filter tasks based on search query & category filter
    val filteredTasks = remember(tasks, searchQuery, selectedCategoryFilter) {
        tasks.filter { task ->
            val matchesSearch = task.title.contains(searchQuery, ignoreCase = true) ||
                    task.note.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedCategoryFilter) {
                "Học tập" -> task.displayCategory == "Học tập"
                "Cuộc sống" -> task.displayCategory == "Cuộc sống"
                else -> true
            }
            matchesSearch && matchesCategory
        }
    }

    val pendingTasks = filteredTasks.filter { !it.isCompleted }
    val completedTasks = filteredTasks.filter { it.isCompleted }
    val uncompletedCount = tasks.count { !it.isCompleted }

    Scaffold(
        containerColor = Color(0xFFF8FAFC),
        bottomBar = {
            BottomNavigationBar(
                selectedTab = 1,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onNavigateToHome()
                        1 -> {}
                        3 -> onNavigateToNotifications()
                        4 -> onNavigateToProfile()
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // 1. Header with Title & Badge
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Danh sách",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "$uncompletedCount việc chưa xong",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 2. Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Tìm công việc", color = Color(0xFF94A3B8)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF94A3B8)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFEEF2F6),
                        unfocusedContainerColor = Color(0xFFF1F5F9),
                        focusedBorderColor = Color(0xFF2563EB),
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 3. Category Filter Chips (Tất cả, Học tập, Cuộc sống)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val categories = listOf("Tất cả", "Học tập", "Cuộc sống")
                    categories.forEach { cat ->
                        val isSelected = selectedCategoryFilter == cat
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                            modifier = Modifier.clickable { selectedCategoryFilter = cat }
                        ) {
                            Text(
                                text = cat,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF475569),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 4. Section "CẦN LÀM"
            item {
                Text(
                    text = "CẦN LÀM",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (pendingTasks.isEmpty()) {
                item {
                    Text(
                        text = "Không có công việc nào cần làm",
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(pendingTasks, key = { it.id }) { task ->
                    TaskListItemRow(
                        task = task,
                        onToggle = { authViewModel.toggleTaskCompletion(task.id) },
                        onDelete = { authViewModel.deleteTask(task.id) }
                    )
                }
            }

            // 5. Section "ĐÃ XONG"
            item {
                Text(
                    text = "ĐÃ XONG",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (completedTasks.isEmpty()) {
                item {
                    Text(
                        text = "Chưa có công việc nào đã hoàn thành",
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(completedTasks, key = { it.id }) { task ->
                    TaskListItemRow(
                        task = task,
                        onToggle = { authViewModel.toggleTaskCompletion(task.id) },
                        onDelete = { authViewModel.deleteTask(task.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onAddTask = { title, category, time ->
                authViewModel.addTask(title, category, time)
                showAddTaskDialog = false
            }
        )
    }
}

@Composable
fun TaskListItemRow(
    task: TaskItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val isHighPriority = task.priority.lowercase() in listOf("high", "gấp")

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
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (task.isCompleted) Color(0xFF94A3B8) else Color(0xFF1E293B),
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Category pill badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (task.displayCategory == "Học tập") Color(0xFFE0F2FE) else Color(0xFFFFEDD5)
                    ) {
                        Text(
                            text = task.displayCategory,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (task.displayCategory == "Học tập") Color(0xFF0284C7) else Color(0xFFEA580C),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (task.date.isNotBlank()) "Hôm nay, ${task.time}" else task.time,
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            if (isHighPriority && !task.isCompleted) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEE2E2)
                ) {
                    Text(
                        text = "Gấp",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
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
