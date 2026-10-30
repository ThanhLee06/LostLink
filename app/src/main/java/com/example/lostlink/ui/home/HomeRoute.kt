package com.example.lostlink.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Home keeps shared navigation integration points while the remaining destinations are built. */
@Composable
fun HomeRoute(
    model: HomeViewModel,
    onSignOut: (() -> Unit)? = null,
    onPost: ((String) -> Unit)? = null,
    onCreatePost: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var searchRequest by rememberSaveable { mutableIntStateOf(0) }
    var homeRequest by rememberSaveable { mutableIntStateOf(0) }
    fun select(index: Int) {
        when (index) {
            0 -> { selected = 0; homeRequest++ }
            1 -> { selected = 1; searchRequest++ }
            2 -> if (onCreatePost != null) onCreatePost(true) else {
                Toast.makeText(context, "Chức năng đăng tin sẽ được bổ sung sau.", Toast.LENGTH_SHORT).show()
            }
            3 -> Toast.makeText(context, "Chức năng tin nhắn sẽ được bổ sung sau.", Toast.LENGTH_SHORT).show()
            4 -> if (onSignOut != null) showAccount = true
        }
    }
    HomeTheme {
        HomeSystemBars()
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = { HomeNavigation(selected, showAccount, model.state.allowed, onSignOut != null, ::select) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                HomeScreen(state = model.state,
                    onPost = { onPost?.invoke(it) },
                    onRefresh = { if (!model.state.loading) model.load() },
                    onMore = { if (!model.state.loading) model.load(true) },
                    onSignIn = { onSignOut?.invoke() }, onReport = { onCreatePost?.invoke(it) },
                    canOpenPosts = onPost != null, reportAvailable = onCreatePost != null,
                    onAccount = if (onSignOut != null) ({ showAccount = true }) else null,
                    searchRequest = searchRequest, homeRequest = homeRequest,
                    onSearchActive = { active -> selected = if (active) 1 else 0 },
                )
            }
        }
        if (showAccount) AlertDialog(onDismissRequest = { showAccount = false }, title = { Text("Tài khoản") },
            text = { Text("Bạn có muốn đăng xuất khỏi LostLink?") },
            confirmButton = {
                TextButton(onClick = { showAccount = false; onSignOut?.invoke() }) {
                    Text("Đăng xuất", color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = { showAccount = false }) { Text("Ở lại") } })
    }
}

@Composable
internal fun HomeNavigation(selected: Int, accountOpen: Boolean, signedIn: Boolean, accountAvailable: Boolean, onSelect: (Int) -> Unit) {
    val items = listOf(HomeIcon.HOME to "Trang chủ", HomeIcon.SEARCH to "Tìm tin",
        HomeIcon.ADD to "Đăng tin", HomeIcon.CHAT to "Tin nhắn", HomeIcon.PERSON to "Tài khoản")
    NavigationBar(containerColor = Color.Black, contentColor = Color.White, tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars) {
        items.forEachIndexed { index, (icon, label) ->
            NavigationBarItem(selected = if (index == 4) accountOpen else selected == index && !accountOpen,
                onClick = { onSelect(index) },
                enabled = when (index) { 1, 2, 3 -> signedIn; 4 -> accountAvailable; else -> true },
                modifier = Modifier.semantics { contentDescription = label },
                icon = { LineIcon(icon, LocalContentColor.current, Modifier.size(24.dp)) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White,
                    indicatorColor = Color.Transparent, unselectedIconColor = Color.White,
                    disabledIconColor = Color.White.copy(alpha = 0.4f)))
        }
    }
}
