package com.example.lostlink.ui.post

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

private fun PostType.hint(): String = when (this) {
    PostType.LOST ->
        "Mô tả màu sắc, kiểu dáng và dấu hiệu dễ nhận ra (vết xước, móc khóa, hình dán...)."
    PostType.FOUND ->
        "Mô tả sơ lược để chủ đồ nhận ra. Nên giữ lại một chi tiết kín để hỏi xác minh."
}

private fun PostType.titleLabel(): String = when (this) {
    PostType.LOST -> "Bạn làm mất gì?"
    PostType.FOUND -> "Bạn nhặt được gì?"
}

private fun PostType.icon(): ImageVector = when (this) {
    PostType.LOST -> Icons.Rounded.SearchOff
    PostType.FOUND -> Icons.Rounded.Inventory2
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostFormHeader(
    state: PostFormState,
    onTypeChange: (PostType) -> Unit,
    onTitleChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val types = PostType.values()

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    )

    val textFieldShape = RoundedCornerShape(14.dp)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {

        // ── Section label: Loại tin ──
        FormSectionLabel(
            icon = Icons.Outlined.Info,
            title = "Loại tin"
        )

        // ── Type selector (custom toggle instead of SegmentedButton) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            types.forEach { type ->
                val isSelected = state.type == type
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "typeBg"
                )
                val borderColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    label = "typeBorder"
                )
                val elevation by animateDpAsState(
                    targetValue = if (isSelected) 2.dp else 0.dp,
                    label = "typeElev"
                )

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTypeChange(type) },
                    shape = RoundedCornerShape(14.dp),
                    color = bgColor,
                    shadowElevation = elevation,
                    tonalElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = borderColor,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            type.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            type.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        // ── Section label: Tiêu đề ──
        FormSectionLabel(
            icon = Icons.Outlined.Title,
            title = state.type.titleLabel()
        )

        ImeAwareOutlinedTextField(
            externalValue = state.title,
            onValueChange = onTitleChange,
            placeholder = { Text("Ví dụ: Ví màu nâu") },
            singleLine = true,
            isError = state.errors.title != null,
            supportingText = state.errors.title?.let { { Text(it) } },
            shape = textFieldShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        // ── Section label: Danh mục ──
        FormSectionLabel(
            icon = Icons.Outlined.Category,
            title = "Danh mục"
        )

        Box {
            OutlinedTextField(
                value = PostCategories.find(state.categoryId)?.label.orEmpty(),
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("Chọn danh mục") },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Chọn",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = state.errors.category != null,
                supportingText = state.errors.category?.let { { Text(it) } },
                shape = textFieldShape,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            // Transparent overlay to capture clicks
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { menuOpen = true }
            )

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                PostCategories.all.forEach { c ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (state.categoryId == c.id) FontWeight.Bold
                                    else FontWeight.Normal
                                )
                                if (state.categoryId == c.id) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        onClick = {
                            onCategoryChange(c.id)
                            menuOpen = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        // ── Section label: Đặc điểm nhận diện ──
        FormSectionLabel(
            icon = Icons.Outlined.Description,
            title = "Đặc điểm nhận diện"
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            placeholder = { Text(state.type.hint()) },
            minLines = 4,
            maxLines = 6,
            isError = state.errors.description != null,
            supportingText = {
                val error = state.errors.description
                when {
                    error != null -> Text(error)
                    PostFormValidator.looksSensitive(state.description) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Có vẻ bạn đang nhập dãy số dài. Đừng đăng đầy đủ số giấy tờ, thẻ hay thông tin cá nhân.",
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    else -> {
                        val remaining = PostFormValidator.DESCRIPTION_MAX - state.description.length
                        Text(
                            "Còn $remaining ký tự",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            shape = textFieldShape,
            colors = textFieldColors,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Reusable section label ──
@Composable
private fun FormSectionLabel(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


@Composable
fun ConfirmLeaveDraft(
    hasUnsavedChanges: Boolean,
    onDiscardAndLeave: () -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = hasUnsavedChanges) { showDialog = true }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
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
                Text("Bạn đã nhập thông tin nhưng chưa đăng. Rời đi sẽ mất nội dung này.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    onDiscardAndLeave()
                }) {
                    Text(
                        "Bỏ nháp",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(
                        "Ở lại",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }
}