    package com.example.lostlink.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HomeAdvancedFilters(
    categories: List<String>,
    areas: List<String>,
    enabled: Boolean,
    resultCount: Int,
    onCategories: (List<String>) -> Unit,
    onAreas: (List<String>) -> Unit,
    onResetAll: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedCount = categories.size + areas.size
    val canResetInSheet = selectedCount > 0 || onResetAll != null

    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        shape = HomeTokens.ImageShape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeTokens.Target)
            .testTag("advanced-filters")
    ) {
        BadgedBox(
            badge = {
                if (selectedCount > 0) {
                    Badge(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    ) {
                        Text("$selectedCount")
                    }
                }
            }
        ) {
            LineIcon(HomeIcon.FILTER, LocalContentColor.current, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (selectedCount == 0) "Danh mục và khu vực" else "Danh mục và khu vực · $selectedCount đã chọn")
    }

    if (selectedCount > 0) {
        val labels = HomeCategory.entries.filter { it.id in categories }.map { it.label } +
            HomeArea.entries.filter { it.id in areas }.map { it.label }
        Text(
            text = labels.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.testTag("advanced-filter-summary")
        )
    }

    if (open && enabled) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            modifier = Modifier.testTag("advanced-filter-sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeTokens.Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Danh mục và khu vực",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                    if (canResetInSheet) {
                        TextButton(
                            onClick = {
                                if (onResetAll != null) {
                                    onResetAll()
                                } else {
                                    onCategories(emptyList())
                                    onAreas(emptyList())
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = HomeTokens.Target)
                                .testTag("reset-advanced-filters")
                                .semantics { contentDescription = "Đặt lại toàn bộ bộ lọc" }
                        ) {
                            LineIcon(HomeIcon.CLOSE, colors.error, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Đặt lại", color = colors.error)
                        }
                    }
                }

                Text(
                    text = "Chọn nhiều mục. Kết quả cập nhật ngay trong các tin đã tải.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("advanced-filter-options"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterSection(
                        title = "Danh mục",
                        options = HomeCategory.entries.map { it.id to it.label },
                        selected = categories,
                        tag = "category",
                        onChange = onCategories
                    )
                    HorizontalDivider()
                    FilterSection(
                        title = "Cơ sở và ký túc xá",
                        options = HomeArea.entries.filter { it.campus }.map { it.id to it.label },
                        selected = areas,
                        tag = "area",
                        onChange = onAreas
                    )
                    FilterSection(
                        title = "Khu vực cụ thể",
                        options = HomeArea.entries.filterNot { it.campus }.map { it.id to it.label },
                        selected = areas,
                        tag = "area",
                        onChange = onAreas
                    )
                }

                Text(
                    text = "$resultCount tin phù hợp trong các tin đã tải",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .testTag("advanced-filter-count")
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedCount > 0) {
                        OutlinedButton(
                            onClick = {
                                if (onResetAll != null) {
                                    onResetAll()
                                } else {
                                    onCategories(emptyList())
                                    onAreas(emptyList())
                                }
                            },
                            shape = HomeTokens.ImageShape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .testTag("clear-advanced-filters")
                        ) {
                            Text("Xóa bộ lọc")
                        }
                    }
                    Button(
                        onClick = { open = false },
                        shape = HomeTokens.ImageShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag("close-advanced-filters")
                    ) {
                        Text("Xem $resultCount tin")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    options: List<Pair<String, String>>,
    selected: List<String>,
    tag: String,
    onChange: (List<String>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (id, label) ->
                val checked = id in selected
                FilterChip(
                    selected = checked,
                    onClick = { onChange(if (checked) selected - id else selected + id) },
                    modifier = Modifier
                        .heightIn(min = HomeTokens.Target)
                        .testTag("$tag-$id"),
                    label = { Text(label) },
                    leadingIcon = if (checked) {
                        {
                            LineIcon(
                                HomeIcon.CHECK,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                                Modifier.size(16.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}
