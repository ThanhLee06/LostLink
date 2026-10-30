package com.example.lostlink.ui.post

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun PostType.areaLabel() = when (this) {
    PostType.LOST -> "Bạn đã thấy đồ lần cuối ở đâu?"
    PostType.FOUND -> "Bạn nhặt được đồ ở đâu?"
}

private fun PostType.timeLabel() = when (this) {
    PostType.LOST -> "Bạn mất đồ lúc nào?"
    PostType.FOUND -> "Bạn nhặt được đồ lúc nào?"
}

private val dateTimeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi", "VN"))

@Composable
private fun BoxScope.ClickOverlay(onClick: () -> Unit) {
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    )
}

@Composable
fun PostFormWhereWhen(
    state: PostFormState,
    onAreaChoiceChange: (AreaChoice) -> Unit,
    onAreaChange: (String) -> Unit,
    onAreaOtherChange: (String) -> Unit,
    onLocationNoteChange: (String) -> Unit,
    onTimeModeChange: (TimeMode) -> Unit,
    onExactTimeChange: (Long?) -> Unit,
    onRangeChange: (Long?, Long?) -> Unit,
    onTimeNoteChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldShape = RoundedCornerShape(14.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ───────── KHU VỰC ─────────
        FormSectionLabel(Icons.Outlined.Place, state.type.areaLabel())

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.areaChoice == AreaChoice.KNOWN,
                onClick = { onAreaChoiceChange(AreaChoice.KNOWN) },
                label = { Text("Khu vực cụ thể") },
                leadingIcon = {
                    Icon(
                        if (state.areaChoice == AreaChoice.KNOWN) Icons.Rounded.Check else Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )
            FilterChip(
                selected = state.areaChoice == AreaChoice.OTHER,
                onClick = { onAreaChoiceChange(AreaChoice.OTHER) },
                label = { Text("Khác") },
                leadingIcon = {
                    Icon(
                        if (state.areaChoice == AreaChoice.OTHER) Icons.Rounded.Check else Icons.Outlined.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )
            FilterChip(
                selected = state.areaChoice == AreaChoice.UNKNOWN,
                onClick = { onAreaChoiceChange(AreaChoice.UNKNOWN) },
                label = { Text("Không rõ") },
                leadingIcon = {
                    Icon(
                        if (state.areaChoice == AreaChoice.UNKNOWN) Icons.Rounded.Check else Icons.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )
        }

        AnimatedContent(
            targetState = state.areaChoice,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "AreaChoiceTransition"
        ) { choice ->
            when (choice) {
                AreaChoice.KNOWN -> AreaDropdown(
                    selectedId = state.areaId,
                    error = state.errors.area,
                    shape = fieldShape,
                    colors = fieldColors,
                    onSelect = onAreaChange
                )
                AreaChoice.OTHER -> OutlinedTextField(
                    value = state.areaOtherText,
                    onValueChange = onAreaOtherChange,
                    label = { Text("Tên khu vực") },
                    placeholder = { Text("Ví dụ: Bàn tròn trước sảnh D...") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.AddLocationAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (state.areaOtherText.isNotEmpty()) {
                        {
                            IconButton(onClick = { onAreaOtherChange("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Xóa", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else null,
                    singleLine = true,
                    isError = state.errors.area != null,
                    supportingText = state.errors.area?.let { err -> { Text(err) } },
                    shape = fieldShape,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                AreaChoice.UNKNOWN -> HelperNoticeCard(
                    text = "Bạn không nhớ rõ khu vực, không sao cả! Chúng tôi sẽ gợi ý bài viết trên toàn trường."
                )
            }
        }

        // Mô tả chi tiết vị trí
        OutlinedTextField(
            value = state.locationNote,
            onValueChange = onLocationNoteChange,
            label = { Text("Chi tiết vị trí (không bắt buộc)") },
            placeholder = { Text("Ví dụ: Bàn gần cửa sổ tầng 2, dãy ghế thứ 3...") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            },
            trailingIcon = if (state.locationNote.isNotEmpty()) {
                {
                    IconButton(onClick = { onLocationNoteChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Xóa", modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            maxLines = 3,
            shape = fieldShape,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        // ───────── THỜI GIAN ─────────
        FormSectionLabel(Icons.Outlined.AccessTime, state.type.timeLabel())

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeMode.entries.forEach { mode ->
                val icon = when (mode) {
                    TimeMode.EXACT -> Icons.Outlined.AccessTime
                    TimeMode.RANGE -> Icons.Outlined.DateRange
                    TimeMode.DESCRIBED -> Icons.Outlined.Edit
                    TimeMode.UNKNOWN -> Icons.Outlined.HelpOutline
                }
                FilterChip(
                    selected = state.timeMode == mode,
                    onClick = { onTimeModeChange(mode) },
                    label = { Text(mode.label) },
                    leadingIcon = {
                        Icon(
                            if (state.timeMode == mode) Icons.Rounded.Check else icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        AnimatedContent(
            targetState = state.timeMode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "TimeModeTransition"
        ) { mode ->
            when (mode) {
                TimeMode.EXACT -> DateTimeField(
                    label = "Thời điểm cụ thể",
                    millis = state.exactAtMillis,
                    onPicked = onExactTimeChange,
                    shape = fieldShape,
                    colors = fieldColors
                )
                TimeMode.RANGE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateTimeField(
                        label = "Từ lúc",
                        millis = state.rangeStartMillis,
                        onPicked = { onRangeChange(it, state.rangeEndMillis) },
                        shape = fieldShape,
                        colors = fieldColors
                    )
                    DateTimeField(
                        label = "Đến lúc",
                        millis = state.rangeEndMillis,
                        onPicked = { onRangeChange(state.rangeStartMillis, it) },
                        shape = fieldShape,
                        colors = fieldColors
                    )
                }
                TimeMode.DESCRIBED -> OutlinedTextField(
                    value = state.timeNote,
                    onValueChange = onTimeNoteChange,
                    label = { Text("Mô tả thời gian") },
                    placeholder = { Text("Ví dụ: Sáng thứ hai, khoảng giờ ra chơi tiết 2...") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (state.timeNote.isNotEmpty()) {
                        {
                            IconButton(onClick = { onTimeNoteChange("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Xóa", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = fieldShape,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                TimeMode.UNKNOWN -> HelperNoticeCard(
                    text = "Bạn không nhớ rõ thời gian, không sao cả! Bài viết vẫn sẽ được đăng bình thường."
                )
            }
        }

        state.errors.time?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun HelperNoticeCard(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AreaDropdown(
    selectedId: String?,
    error: String?,
    shape: Shape,
    colors: TextFieldColors,
    onSelect: (String) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        label = "arrowRotation"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = PostAreas.find(selectedId)?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Khu vực") },
            placeholder = { Text("Chạm để chọn khu vực trong trường") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation)
                )
            },
            isError = error != null,
            supportingText = error?.let { err -> { Text(err) } },
            shape = shape,
            colors = colors,
            modifier = Modifier.fillMaxWidth()
        )
        ClickOverlay { open = true }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            PostAreas.all.forEach { area ->
                val isSelected = area.id == selectedId
                DropdownMenuItem(
                    text = {
                        Text(
                            text = area.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        onSelect(area.id)
                        open = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(
    label: String,
    millis: Long?,
    onPicked: (Long?) -> Unit,
    shape: Shape,
    colors: TextFieldColors
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var pickedDate by rememberSaveable { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = millis?.let { dateTimeFmt.format(Date(it)) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Chạm để chọn ngày và giờ") },
            leadingIcon = {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = if (millis != null) {
                {
                    IconButton(onClick = { onPicked(null) }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Xóa ngày", modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            shape = shape,
            colors = colors,
            modifier = Modifier.fillMaxWidth()
        )
        ClickOverlay { showDate = true }
    }

    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = millis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = dateState.selectedDateMillis
                    showDate = false
                    showTime = pickedDate != null
                }) { Text("Tiếp tục chọn giờ") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Hủy") } }
        ) { DatePicker(state = dateState) }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val base = pickedDate
                    if (base != null) {
                        // DatePicker trả về 0h UTC của ngày đã chọn, nên đọc ngày theo UTC
                        // rồi ghép giờ/phút theo múi giờ của máy
                        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = base }
                        val local = Calendar.getInstance().apply {
                            set(
                                utc.get(Calendar.YEAR),
                                utc.get(Calendar.MONTH),
                                utc.get(Calendar.DAY_OF_MONTH),
                                timeState.hour,
                                timeState.minute,
                                0
                            )
                            set(Calendar.MILLISECOND, 0)
                        }
                        onPicked(local.timeInMillis)
                    }
                    showTime = false
                }) { Text("Xong") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Hủy") } },
            text = { TimePicker(state = timeState) }
        )
    }
}
