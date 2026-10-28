package com.example.lostlink.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.lostlink.data.FirebaseAccountRepository
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    model: HomeViewModel,
    onSignOut: () -> Unit = {},
    onCreatePost: (Boolean) -> Unit = {},
) {
    var showSignOutDialog by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            background = PageBackground,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Scaffold(
            containerColor = PageBackground,
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                BottomBar { tabIndex ->
                    if (tabIndex == 4) {
                        showSignOutDialog = true
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                HomeScreen(
                    state = model.state,
                    onPost = {},
                    onRefresh = { model.load() },
                    onMore = { model.load(true) },
                    onSignIn = onSignOut,
                    onReport = onCreatePost,
                    onRetry = model::retry,
                )
            }
        }

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Tài khoản") },
                text = { Text("Bạn có muốn đăng xuất khỏi ứng dụng LostLink?") },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        onSignOut()
                    }) {
                        Text("Đăng xuất", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) {
                        Text("Hủy")
                    }
                }
            )
        }
    }
}
