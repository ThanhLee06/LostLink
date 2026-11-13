package com.example.lostlink.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun rememberHomeFilterTime(filter: HomeTimeFilter): Long {
    val owner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), filter, owner) {
        if (filter == HomeTimeFilter.ALL) {
            value = System.currentTimeMillis()
            return@produceState
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = System.currentTimeMillis()
                delay(60_000 - Math.floorMod(value, 60_000L))
            }
        }
    }
    return now
}

@Composable
internal fun HomeTimeFilters(filter: HomeTimeFilter, enabled: Boolean, onChange: (HomeTimeFilter) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Thời gian đăng bài · Giờ Việt Nam", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, shape = HomeTokens.ImageShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = HomeTokens.Target).testTag("posted-time-filter")) {
                LineIcon(HomeIcon.CLOCK, LocalContentColor.current, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(filter.label, modifier = Modifier.weight(1f))
                LineIcon(HomeIcon.CHEVRON, LocalContentColor.current, Modifier.size(18.dp).rotate(90f))
            }
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                HomeTimeFilter.entries.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate.label) }, onClick = { onChange(candidate); open = false },
                        modifier = Modifier.testTag("posted-time-${candidate.name}").semantics { selected = filter == candidate },
                        leadingIcon = {
                            if (filter == candidate) LineIcon(HomeIcon.CHECK, MaterialTheme.colorScheme.primary, Modifier.size(20.dp))
                            else Spacer(Modifier.size(20.dp))
                        })
                }
            }
        }
        val hint = when (filter) {
            HomeTimeFilter.THREE_DAYS -> "Hôm nay và hai ngày trước đó"
            HomeTimeFilter.THIS_WEEK -> "Từ thứ Hai đầu tuần đến hiện tại"
            HomeTimeFilter.THIS_MONTH -> "Từ ngày 1 tháng này đến hiện tại"
            else -> null
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
