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
fun HomeRoute(model: HomeViewModel) {
    var signIn by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = PageBackground,
        surface = Color.White, onSurface = Ink)) {
        Scaffold(containerColor = PageBackground, contentWindowInsets = WindowInsets.statusBars,
            bottomBar = { BottomBar {} }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                HomeScreen(model.state, onPost = {}, onRefresh = { model.load() },
                    onMore = { model.load(true) }, onSignIn = { signIn = true }, onReport = {})
            }
        }
        if (signIn) SignInDialog(model.accounts, onDismiss = { signIn = false }, onSuccess = { signIn = false })
    }
}

/** Temporary entry point until the team's authentication screen is merged. */
@Composable
private fun SignInDialog(accounts: FirebaseAccountRepository, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!loading) onDismiss() }, title = { Text("Đăng nhập LostLink") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dùng tài khoản @ut.edu.vn đã xác minh email.")
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, enabled = !loading)
                OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !loading)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !loading && email.isNotBlank() && password.isNotBlank(), onClick = {
                loading = true
                error = null
                accounts.signIn(email, password) { result ->
                    loading = false
                    result.onSuccess { password = ""; onSuccess() }
                        .onFailure { error = "Chưa đăng nhập được. Kiểm tra email, mật khẩu và xác minh email." }
                }
            }) { Text(if (loading) "Đang đăng nhập…" else "Đăng nhập") }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Để sau") } })
}
