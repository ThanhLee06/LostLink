package com.example.lostlink.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onPost: (String) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onSignIn: () -> Unit,
    onReport: (Boolean) -> Unit,
    canOpenPosts: Boolean = true,
    reportAvailable: Boolean = true,
    onAccount: (() -> Unit)? = null,
    searchRequest: Int = 0,
    homeRequest: Int = 0,
    onSearchActive: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = MaterialTheme.colorScheme
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var filter by rememberSaveable { mutableStateOf(HomePostFilter.ALL) }
    var searchFocused by remember { mutableStateOf(false) }
    var lastLoadWasMore by rememberSaveable { mutableStateOf(false) }
    var consumedSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    var consumedHomeRequest by rememberSaveable { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    val visible = remember(state.posts, search.text, filter) { filterLoadedHomePosts(state.posts, search.text, filter) }
    val searching = search.text.isNotBlank() || filter != HomePostFilter.ALL
    fun refresh() { if (!state.loading) { lastLoadWasMore = false; onRefresh() } }
    fun more() { if (!state.loading) { lastLoadWasMore = true; onMore() } }
    LaunchedEffect(searchRequest, state.allowed) {
        if (searchRequest > consumedSearchRequest && state.allowed) {
            consumedSearchRequest = searchRequest
            withFrameNanos { }
            listState.scrollToItem(1)
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(homeRequest) {
        if (homeRequest > consumedHomeRequest) {
            consumedHomeRequest = homeRequest
            search = TextFieldValue()
            filter = HomePostFilter.ALL
            focusManager.clearFocus()
            keyboard?.hide()
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(search.text, searchFocused) { onSearchActive(search.text.isNotBlank() || searchFocused) }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val gutter = if (maxWidth >= 600.dp) 32.dp else HomeTokens.Gutter
        val shortWindow = maxHeight < 480.dp && maxWidth >= 480.dp && !largeText
        val deferUnavailableActions = shortWindow && !reportAvailable
        val reportContent: @Composable () -> Unit = {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints {
                    if (largeText || maxWidth < 300.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReportAction(true, reportAvailable && state.allowed, Modifier.fillMaxWidth(), shortWindow) { onReport(true) }
                        ReportAction(false, reportAvailable && state.allowed, Modifier.fillMaxWidth(), shortWindow) { onReport(false) }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReportAction(true, reportAvailable && state.allowed, Modifier.weight(1f), shortWindow) { onReport(true) }
                        ReportAction(false, reportAvailable && state.allowed, Modifier.weight(1f), shortWindow) { onReport(false) }
                    }
                }
                if (!reportAvailable) Text("Đăng tin chưa khả dụng", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight().widthIn(max = HomeTokens.MaxWidth)
            .fillMaxWidth().align(Alignment.TopCenter).testTag("home-list"),
            contentPadding = PaddingValues(horizontal = gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "brand") { HomeHeader(onAccount, largeText) }
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { searchFocused = it.isFocused }
                            .testTag("home-search"),
                        enabled = state.allowed, singleLine = true,
                        label = { Text("Tìm đồ hoặc khu vực") }, placeholder = { Text("Ví nâu, thư viện…") },
                        leadingIcon = { LineIcon(HomeIcon.SEARCH, colors.onSurfaceVariant, Modifier.size(22.dp)) },
                        trailingIcon = if (search.text.isNotEmpty()) ({ IconButton(
                            onClick = { search = TextFieldValue() },
                            modifier = Modifier.sizeIn(minWidth = HomeTokens.Target, minHeight = HomeTokens.Target)
                                .semantics { contentDescription = "Xóa từ khóa tìm kiếm" },
                        ) { LineIcon(HomeIcon.CLOSE, colors.onSurfaceVariant, Modifier.size(20.dp)) } }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }),
                        textStyle = MaterialTheme.typography.bodyLarge, shape = HomeTokens.CardShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = colors.surface, focusedContainerColor = colors.surface,
                            unfocusedBorderColor = colors.outline, focusedBorderColor = colors.primary,
                            unfocusedLabelColor = colors.onSurfaceVariant, focusedLabelColor = colors.primary,
                            unfocusedPlaceholderColor = colors.onSurfaceVariant, focusedPlaceholderColor = colors.onSurfaceVariant),
                    )
                    Text(if (state.allowed) "Phạm vi: ${state.posts.size} tin đã tải${if (state.hasMore) " · Có thể tải thêm" else ""}"
                        else "Đăng nhập để tìm và xem tin", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.testTag("search-scope"))
                }
            }
            if (!deferUnavailableActions) item(key = "report") { reportContent() }
            item(key = "heading") {
                FeedHeading(searching, visible.size, state.allowed, state.loading, filter, shortWindow,
                    onRefresh = ::refresh, onFilter = { candidate ->
                        filter = if (filter == candidate && candidate != HomePostFilter.ALL) HomePostFilter.ALL else candidate
                    })
            }
            if (!state.allowed) item(key = "auth") {
                FeedNotice("Cùng tìm lại đồ thất lạc", "Đăng nhập bằng tài khoản UTH đã xác minh để xem bài đăng trong cộng đồng.", "Đăng nhập", onSignIn)
            } else {
                if (state.loading && state.posts.isEmpty()) item(key = "loading") {
                    LoadingNotice("Đang tải tin…")
                }
                if (state.loading && state.posts.isNotEmpty() && !lastLoadWasMore) item(key = "refreshing") {
                    LoadingNotice("Đang cập nhật. Các tin bên dưới đã tải trước đó.")
                }
                if (state.error != null) item(key = "error") {
                    FeedNotice(if (state.posts.isEmpty()) "Chưa tải được tin" else "Chưa cập nhật được danh sách",
                        if (state.posts.isEmpty()) state.error else "${state.error}\nBạn đang xem các tin đã tải trước đó.",
                        "Thử lại", { if (lastLoadWasMore && state.hasMore) more() else refresh() }, error = true, enabled = !state.loading)
                }
                if (!state.loading && state.error == null && state.posts.isEmpty()) item(key = "empty") {
                    FeedNotice("Chưa có tin", "Khi có bài đăng mới, bạn sẽ thấy ở đây.",
                        if (reportAvailable) "Báo mất đồ" else "Làm mới",
                        { if (reportAvailable) onReport(true) else refresh() })
                }
                if (!state.loading && state.error == null && state.posts.isNotEmpty() && visible.isEmpty()) item(key = "no-match") {
                    FeedNotice("Chưa thấy tin phù hợp", "Không có kết quả trong ${state.posts.size} tin đã tải. Thử từ khóa hoặc loại tin khác.",
                        "Xóa bộ lọc", { search = TextFieldValue(); filter = HomePostFilter.ALL })
                }
                items(visible, key = { "post:${it.id}" }, contentType = { "post-card" }) { post ->
                    HomePostCard(post, if (canOpenPosts) ({ onPost(post.id) }) else null)
                }
                if (state.posts.isNotEmpty() || state.hasMore) item(key = "pagination") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.hasMore) {
                            Text("Đã tải ${state.posts.size} tin · Còn tin để xem", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            OutlinedButton(onClick = ::more, enabled = !state.loading && state.error == null, shape = HomeTokens.ImageShape,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("load-more")) {
                                if (state.loading && lastLoadWasMore) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (state.loading && lastLoadWasMore) "Đang tải thêm…" else if (searching) "Tải thêm tin để tìm tiếp" else "Xem thêm tin")
                            }
                        } else if (!state.loading && state.error == null) Text("Bạn đã xem hết các tin đã tải", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (deferUnavailableActions) item(key = "report") { reportContent() }
            item(key = "tip") {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    LineIcon(HomeIcon.CHECK, colors.primary, Modifier.size(20.dp))
                    Text("Mẹo nhỏ: thêm đặc điểm riêng của món đồ để mọi người dễ nhận ra hơn.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onAccount: (() -> Unit)?, largeText: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(colors.primary, HomeTokens.ImageShape), contentAlignment = Alignment.Center) {
                LineIcon(HomeIcon.LINK, colors.onPrimary, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("LostLink", style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.semantics { heading() })
                Text("Cùng nhau tìm lại đồ", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (onAccount != null && !largeText) IconButton(onClick = onAccount, modifier = Modifier.size(HomeTokens.Target)
                .background(colors.primaryContainer, CircleShape).semantics { contentDescription = "Mở tài khoản" }) {
                LineIcon(HomeIcon.PERSON, colors.onPrimaryContainer, Modifier.size(22.dp))
            }
        }
        if (onAccount != null && largeText) TextButton(onClick = onAccount,
            modifier = Modifier.align(Alignment.End).heightIn(min = HomeTokens.Target).semantics { contentDescription = "Mở tài khoản" }) {
            LineIcon(HomeIcon.PERSON, colors.primary, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp)); Text("Tài khoản")
        }
    }
}

@Composable
private fun FeedHeading(searching: Boolean, count: Int, allowed: Boolean, loading: Boolean,
    filter: HomePostFilter, compact: Boolean, onRefresh: () -> Unit, onFilter: (HomePostFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val copy: @Composable () -> Unit = {
        Column {
            Text(if (compact) (if (searching) "Kết quả" else "Tin mới") else (if (searching) "Tin phù hợp" else "Tin mới đăng"),
                style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            Text(if (compact) "$count tin" else if (searching) "$count kết quả trong tin đã tải" else "Sắp xếp theo thời điểm đăng",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (compact) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { copy() }
        Box(Modifier.widthIn(max = 340.dp)) { HomeFilters(filter, allowed, onFilter) }
        IconButton(onClick = onRefresh, enabled = allowed && !loading, modifier = Modifier.size(HomeTokens.Target)
            .testTag("refresh-feed").semantics { contentDescription = "Làm mới danh sách" }) {
            LineIcon(HomeIcon.REFRESH, colors.primary, Modifier.size(22.dp))
        }
    } else Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { copy() }
            TextButton(onClick = onRefresh, enabled = allowed && !loading,
                modifier = Modifier.heightIn(min = HomeTokens.Target).testTag("refresh-feed")) {
                LineIcon(HomeIcon.REFRESH, if (loading) colors.onSurfaceVariant else colors.primary, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Làm mới")
            }
        }
        HomeFilters(filter, allowed, onFilter)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeFilters(filter: HomePostFilter, enabled: Boolean, onFilter: (HomePostFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HomePostFilter.entries.forEach { candidate ->
            FilterChip(selected = filter == candidate, onClick = { onFilter(candidate) }, enabled = enabled,
                modifier = Modifier.heightIn(min = HomeTokens.Target).testTag("filter-${candidate.name}"),
                label = { Text(candidate.label, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = if (filter == candidate) ({ LineIcon(HomeIcon.CHECK, colors.onPrimaryContainer, Modifier.size(16.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer,
                    selectedLabelColor = colors.onPrimaryContainer, labelColor = colors.onSurfaceVariant))
        }
    }
}

@Composable
private fun ReportAction(lost: Boolean, enabled: Boolean, modifier: Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (compact) 56.dp else 88.dp)
        .testTag(if (lost) "report-lost" else "report-found"), shape = HomeTokens.CardShape,
        color = if (!enabled) colors.surfaceContainer else if (lost) colors.primary else colors.secondaryContainer,
        contentColor = if (!enabled) colors.onSurfaceVariant else if (lost) colors.onPrimary else colors.onSecondaryContainer) {
        Row(Modifier.padding(if (compact) 12.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(if (lost) HomeIcon.BOX else HomeIcon.SCAN, LocalContentColor.current, Modifier.size(24.dp))
            Text(if (lost) "Báo mất đồ" else "Báo nhặt được", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun LoadingNotice(message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
