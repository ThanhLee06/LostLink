package com.example.lostlink.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HomeAdvancedFilters(categories: List<String>, areas: List<String>, enabled: Boolean, resultCount: Int,
    onCategories: (List<String>) -> Unit, onAreas: (List<String>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedCount = categories.size + areas.size
    OutlinedButton(onClick = { open = true }, enabled = enabled, shape = HomeTokens.ImageShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = HomeTokens.Target).testTag("advanced-filters")) {
        LineIcon(HomeIcon.FILTER, LocalContentColor.current, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (selectedCount == 0) "Danh mục và khu vực" else "Danh mục và khu vực · $selectedCount đã chọn")
    }
    if (selectedCount > 0) {
        val labels = HomeCategory.entries.filter { it.id in categories }.map { it.label } +
            HomeArea.entries.filter { it.id in areas }.map { it.label }
        Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("advanced-filter-summary"))
    }
    if (open && enabled) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("advanced-filter-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = HomeTokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Danh mục và khu vực", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text("Chọn nhiều mục. Kết quả cập nhật ngay trong các tin đã tải.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("advanced-filter-options"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterSection("Danh mục", HomeCategory.entries.map { it.id to it.label }, categories, "category", onCategories)
                HorizontalDivider()
                FilterSection("Cơ sở và ký túc xá", HomeArea.entries.filter { it.campus }.map { it.id to it.label }, areas, "area", onAreas)
                FilterSection("Khu vực cụ thể", HomeArea.entries.filterNot { it.campus }.map { it.id to it.label }, areas, "area", onAreas)
            }
            Text("$resultCount tin phù hợp trong các tin đã tải", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("advanced-filter-count").semantics { liveRegion = LiveRegionMode.Polite })
            Button(onClick = { open = false }, shape = HomeTokens.ImageShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("close-advanced-filters")) {
                Text("Xem $resultCount tin")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, options: List<Pair<String, String>>, selected: List<String>, tag: String,
    onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (id, label) ->
                val checked = id in selected
                FilterChip(selected = checked, onClick = { onChange(if (checked) selected - id else selected + id) },
                    modifier = Modifier.heightIn(min = HomeTokens.Target).testTag("$tag-$id"),
                    label = { Text(label) }, leadingIcon = if (checked) ({
                        LineIcon(HomeIcon.CHECK, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(16.dp))
                    }) else null)
            }
        }
    }
}
