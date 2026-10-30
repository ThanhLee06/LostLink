package com.example.lostlink.ui.post

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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    vm: PostFormViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(uid) { vm.bindUser(uid) }

    ConfirmLeaveDraft(state.hasUnsavedChanges) {
        vm.clearDraft()
        onLeave()
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
                PostFormBanner()

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
                            onDescriptionChange = vm::onDescriptionChange
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
                    onClick = { vm.validate() },
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
private fun PostFormBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
            )
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PostAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "Đăng tin mới",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Điền thông tin để đăng tin tìm đồ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
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
        if (state.description.isNotBlank()) 3 else null,
        if (isAreaFilled) 4 else null,
        if (isTimeFilled) 5 else null
    ).size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val labels = listOf("Tiêu đề", "Danh mục", "Mô tả", "Khu vực", "Thời gian")
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
