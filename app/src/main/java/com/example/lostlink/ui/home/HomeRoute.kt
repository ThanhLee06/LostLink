package com.example.lostlink.ui.home

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lostlink.ui.profile.ProfileScreen
import com.example.lostlink.ui.profile.ProfileViewModel

@Composable
fun HomeRoute(
    model: HomeViewModel,
    onSignOut: (() -> Unit)? = null,
    onPost: ((String) -> Unit)? = null,
    onCreatePost: ((Boolean) -> Unit)? = null,
    detailContent: (@Composable () -> Unit)? = null,
    onReturnHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var searchRequest by rememberSaveable { mutableIntStateOf(0) }
    var homeRequest by rememberSaveable { mutableIntStateOf(0) }
    var resetSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    val savedScreens = rememberSaveableStateHolder()
    fun createPost(lost: Boolean) {
        if (model.state.allowed && model.accounts.isUserLoggedInAndVerified) onCreatePost?.invoke(lost)
    }
    DisposableEffect(lifecycleOwner, activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) {
                val accountId = model.accounts.currentUserId.takeIf { model.accounts.isUserLoggedInAndVerified }
                if (HomePostCreation.consume(activity.intent, accountId)) {
                    selected = 0
                    homeRequest++
                    resetSearchRequest++
                    model.refreshAfterPostCreated()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun select(index: Int) {
        when (index) {
            0 -> { onReturnHome(); selected = 0; homeRequest++ }
            1 -> { onReturnHome(); selected = 1; searchRequest++ }
            2 -> if (onCreatePost != null) createPost(true) else {
                Toast.makeText(context, "Chức năng đăng tin sẽ được bổ sung sau.", Toast.LENGTH_SHORT).show()
            }
            3 -> Toast.makeText(context, "Chức năng tin nhắn sẽ được bổ sung sau.", Toast.LENGTH_SHORT).show()
            4 -> { onReturnHome(); selected = 4 }
        }
    }
    HomeTheme {
        HomeSystemBars()
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = { HomeNavigation(selected, selected == 4, model.state.allowed, onSignOut != null, ::select) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (detailContent != null) detailContent() else if (selected == 4 && onSignOut != null) {
                    savedScreens.SaveableStateProvider("profile") {
                        val profileViewModel = viewModel<ProfileViewModel>()
                        ProfileScreen(
                            viewModel = profileViewModel,
                            onPost = { onPost?.invoke(it) },
                            onSignOut = { onSignOut() }
                        )
                    }
                } else savedScreens.SaveableStateProvider("home") {
                    HomeScreen(state = model.state,
                        onPost = { onPost?.invoke(it) },
                        onRefresh = { if (!model.state.loading) model.load() },
                        onMore = { if (!model.state.loading) model.load(true) },
                        onSignIn = { onSignOut?.invoke() }, onReport = ::createPost,
                        canOpenPosts = onPost != null, reportAvailable = onCreatePost != null,
                        onAccount = if (onSignOut != null) ({ select(4) }) else null,
                        searchRequest = searchRequest, homeRequest = homeRequest, resetSearchRequest = resetSearchRequest,
                        onSearchActive = { active -> if (active) selected = 1 },
                    )
                }
            }
        }
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
