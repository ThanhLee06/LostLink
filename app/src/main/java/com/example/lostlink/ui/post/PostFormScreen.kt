package com.example.lostlink.ui.post

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PostFormScreen(
    uid: String?,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    vm: PostFormViewModel = viewModel(),
    onSubmitSuccess: ((PostFormState) -> Unit)? = null
) {
    val state by vm.state.collectAsState()
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uid) { vm.bindUser(uid) }

    val handleBack = {
        if (state.hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            onLeave()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    "Bỏ bản nháp?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Bạn đã nhập thông tin nhưng chưa đăng. Bạn có chắc muốn thoát ra?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    vm.clearDraft()
                    onLeave()
                }) {
                    Text(
                        "Thoát & Bỏ nháp",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(
                        "Ở lại",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                PostFormBanner(
                    onBackClick = handleBack,
                    onLoadSample = vm::loadSampleData
                )

                Spacer(Modifier.height(16.dp))
                PostFormProgressIndicator(state = state)

                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        PostFormHeader(
                            state = state,
                            onTypeChange = vm::onTypeChange,
                            onTitleChange = vm::onTitleChange,
                            onCategoryChange = vm::onCategoryChange,
                            onDescriptionChange = vm::onDescriptionChange,
                            imageContent = {
                                PostFormImageSection(
                                    imageUri = state.imageUri,
                                    imageError = state.errors.image,
                                    onImagePicked = vm::onImageSelected,
                                    onRemoveImage = vm::onRemoveImage,
                                    onImageError = vm::onImageError
                                )
                            }
                        )

                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp
                        )
                        Spacer(Modifier.height(20.dp))

                        PostFormWhereWhen(
                            state = state,
                            onAreaChoiceChange = vm::onAreaChoiceChange,
                            onAreaChange = vm::onAreaChange,
                            onAreaOtherChange = vm::onAreaOtherChange,
                            onLocationNoteChange = vm::onLocationNoteChange,
                            onTimeModeChange = vm::onTimeModeChange,
                            onExactTimeChange = vm::onExactTimeChange,
                            onRangeChange = vm::onRangeChange,
                            onTimeNoteChange = vm::onTimeNoteChange
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                tonalElevation = 2.dp
            ) {
                ElevatedButton(
                    onClick = {
                        if (vm.validate()) {
                            onSubmitSuccess?.invoke(state)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.elevatedButtonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp
                    )
                ) {
                    Text(
                        "Tiếp tục",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PostFormBanner(
    onBackClick: () -> Unit,
    onLoadSample: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
            )
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Thoát ra",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "Đăng tin mới",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Điền thông tin để đăng tin tìm đồ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            if (onLoadSample != null) {
                Surface(
                    onClick = onLoadSample,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        "Dữ liệu mẫu",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PostFormProgressIndicator(state: PostFormState) {
    val isAreaFilled = when (state.areaChoice) {
        AreaChoice.KNOWN -> state.areaId != null
        AreaChoice.OTHER -> state.areaOtherText.isNotBlank()
        AreaChoice.UNKNOWN -> true
    }
    val isTimeFilled = when (state.timeMode) {
        TimeMode.EXACT -> state.exactAtMillis != null
        TimeMode.RANGE -> state.rangeStartMillis != null && state.rangeEndMillis != null
        TimeMode.DESCRIBED -> state.timeNote.isNotBlank()
        TimeMode.UNKNOWN -> true
    }

    val filledSteps = listOfNotNull(
        if (state.title.isNotBlank()) 1 else null,
        if (state.categoryId != null) 2 else null,
        if (state.imageUri != null) 3 else null,
        if (state.description.isNotBlank()) 4 else null,
        if (isAreaFilled) 5 else null,
        if (isTimeFilled) 6 else null
    ).size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val labels = listOf("Tiêu đề", "Danh mục", "Ảnh", "Mô tả", "Khu vực", "Thời gian")
        labels.forEachIndexed { index, label ->
            val isActive = index < filledSteps
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (isActive) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
            if (index < labels.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 0.dp)
                        .height(2.dp)
                        .weight(1f)
                        .align(Alignment.Top)
                        .padding(top = 4.dp)
                        .background(
                            if (index < filledSteps) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
    }
}
