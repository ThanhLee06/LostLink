package com.example.lostlink.ui.post

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.lostlink.ui.home.HomePost
import com.google.firebase.auth.FirebaseAuth
import com.example.lostlink.data.PostRepository
import com.example.lostlink.data.FirebaseAccountRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPostScreen(
    post: HomePost,
    onBack: () -> Unit,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember(context) { PostRepository(context.applicationContext) }
    val accounts = remember { FirebaseAccountRepository() }
    val ownerUid = post.authorId
    fun isCurrentOwner() = accounts.isUserLoggedInAndVerified && accounts.currentUserId == ownerUid

    // Điền sẵn dữ liệu cũ từ bài viết
    var title by remember { mutableStateOf(post.title) }
    var type by remember { mutableStateOf(post.type) } 
    var categoryId by remember { mutableStateOf(PostCategories.normalizeId(post.categoryId) ?: "other") }
    var location by remember { mutableStateOf(post.location) }
    var description by remember { mutableStateOf(post.description) }

    // Quản lý ảnh: ảnh hiện tại trên mạng hoặc ảnh mới chọn từ máy
    val originalImageUrl: String? = remember { post.images.firstOrNull { it.isNotBlank() } }
    var currentImageUrl by remember { mutableStateOf<String?>(originalImageUrl) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isImageDeleted by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var savingStatus by remember { mutableStateOf("Đang lưu bài đăng...") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    // Launcher chọn ảnh từ máy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            isImageDeleted = false
        }
    }

    val filePickerFallback = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            isImageDeleted = false
        }
    }

    fun pickImage() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: Exception) {
            filePickerFallback.launch("image/*")
        }
    }

    val categories = remember { PostCategories.all.map { it.id to it.label } }

    val selectedCategoryLabel = categories.firstOrNull { it.first == categoryId }?.second ?: "Khác"

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chỉnh sửa bài đăng",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cập nhật lại thông tin tìm kiếm / trao trả",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Chọn Loại tin 
                        EditSectionHeader(icon = Icons.Outlined.Info, title = "Loại tin")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val isLost = type == "LOST"

                            SelectableTypeButton(
                                title = "Mất đồ",
                                icon = Icons.Rounded.SearchOff,
                                isSelected = isLost,
                                onClick = {}, enabled = false,
                                modifier = Modifier.weight(1f)
                            )

                            SelectableTypeButton(
                                title = "Nhặt được",
                                icon = Icons.Rounded.Inventory2,
                                isSelected = !isLost,
                                onClick = {}, enabled = false,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Tiêu đề món đồ
                        EditSectionHeader(
                            icon = Icons.Outlined.Title,
                            title = if (type == "LOST") "Bạn làm mất gì?" else "Bạn nhặt được gì?"
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = { Text("Ví dụ: Ví da màu nâu...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Danh mục
                        EditSectionHeader(icon = Icons.Outlined.Category, title = "Danh mục")
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                onClick = { categoryMenuExpanded = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedCategoryLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = categoryMenuExpanded,
                                onDismissRequest = { categoryMenuExpanded = false }
                            ) {
                                categories.forEach { (catId, catLabel) ->
                                    DropdownMenuItem(
                                        text = { Text(catLabel) },
                                        onClick = {
                                            categoryId = catId
                                            categoryMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Khu vực / Địa điểm
                        EditSectionHeader(icon = Icons.Outlined.Place, title = "Khu vực / Địa điểm")
                        OutlinedTextField(
                            value = location,
                            onValueChange = { location = it },
                            placeholder = { Text("Ví dụ: Căn tin khu A, Thư viện tầng 2...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Hình ảnh món đồ (Bổ sung / Thay đổi ảnh)
                        EditSectionHeader(icon = Icons.Outlined.Image, title = "Hình ảnh món đồ")
                        val activeImageModel: Any? = when {
                            selectedImageUri != null -> selectedImageUri
                            !isImageDeleted && !currentImageUrl.isNullOrBlank() -> currentImageUrl
                            else -> null
                        }

                        if (activeImageModel != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(activeImageModel)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Ảnh món đồ",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { pickImage() },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Đổi ảnh khác")
                                    }
                                    TextButton(
                                        onClick = {
                                            selectedImageUri = null
                                            isImageDeleted = true
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Gỡ ảnh")
                                    }
                                }
                            }
                        } else {
                            Surface(
                                onClick = { pickImage() },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.AddPhotoAlternate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Thêm ảnh món đồ (JPG, PNG, WEBP)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Mô tả chi tiết
                        EditSectionHeader(icon = Icons.Outlined.Description, title = "Mô tả chi tiết")
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            placeholder = { Text("Thêm các đặc điểm nhận diện riêng của món đồ...") },
                            minLines = 4,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Nút Lưu thay đổi
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            if (isSaving || !isCurrentOwner()) return@Button
                            if (post.isHidden) {
                                Toast.makeText(context, "Bài đang bị ẩn, không thể chỉnh sửa.", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val draft = editPostDraft(post, title, categoryId, location, description)
                            val errors = PostFormValidator.validate(draft)
                            val errorMsg = listOfNotNull(errors.title, errors.category, errors.description,
                                errors.area, errors.time, errors.image).firstOrNull()
                            if (errorMsg != null) {
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (selectedImageUri == null && !isImageDeleted && originalImageUrl != null && post.mediaAssetIds.isEmpty()) {
                                Toast.makeText(context, "Ảnh cũ chưa được xác minh. Hãy chọn lại ảnh hoặc gỡ ảnh.", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            isSaving = true
                            coroutineScope.launch {
                                try {
                                    val media = selectedImageUri?.let {
                                        savingStatus = "Đang tải ảnh lên..."
                                        repository.uploadImage(ownerUid, post.id, it)
                                    }
                                    if (!isCurrentOwner()) return@launch
                                    savingStatus = "Đang lưu bài đăng..."
                                    repository.updatePost(ownerUid, post.id, draft, media,
                                        keepExistingMedia = selectedImageUri == null && !isImageDeleted)
                                    if (isCurrentOwner()) onSaveSuccess()
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    if (isCurrentOwner()) Toast.makeText(context,
                                        error.localizedMessage ?: "Cập nhật thất bại.", Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(savingStatus)
                        } else {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Lưu thay đổi",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

//CÁC COMPONENT GIAO DIỆN NHỎ 
@Composable
private fun EditSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SelectableTypeButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSelected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
