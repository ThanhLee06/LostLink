package com.example.lostlink.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lostlink.R
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.home.HomeIcon
import com.example.lostlink.ui.home.LineIcon
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

private val GreenAccent = Color(0xFF0F6E39)
private val TextDark = Color(0xFF191D2B)
private val TextMuted = Color(0xFF7A7E89)
private val FieldBackground = Color(0xFFF5F6F8)
private val FieldBorder = Color(0xFFEBECEF)
private val DividerColor = Color(0xFFEEEEEE)

enum class AuthMode { LOGIN, REGISTER }

@Composable
fun AuthScreen(
    accounts: FirebaseAccountRepository = remember { FirebaseAccountRepository() },
    initialMode: AuthMode = AuthMode.LOGIN,
    onAuthSuccess: () -> Unit = {},
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val context = LocalContext.current

    // Form fields
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    // UI state
    var loading by remember { mutableStateOf(value = false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showResendVerificationButton by remember { mutableStateOf(value = false) }
    var showForgotPasswordDialog by remember { mutableStateOf(value = false) }

    // Google Sign-In Launcher (Account Picker)
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.data == null && (result.resultCode == Activity.RESULT_CANCELED)) {
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val selectedEmail = account?.email.orEmpty()
            val idToken: String? = account?.idToken

            if (selectedEmail.isNotBlank()) {
                email = selectedEmail
            }

            if (selectedEmail.isNotBlank() && !FirebaseAccountRepository.isAllowedEmail(selectedEmail)) {
                errorMessage = "Vui lòng chọn tài khoản Google sinh viên có đuôi @ut.edu.vn ($selectedEmail)."
                accounts.signOut()
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
                GoogleSignIn.getClient(context, gso).signOut()
                return@rememberLauncherForActivityResult
            }

            if (!idToken.isNullOrBlank()) {
                loading = true
                accounts.signInWithGoogle(idToken) { res ->
                    loading = false
                    res.onSuccess {
                        onAuthSuccess()
                    }.onFailure { err ->
                        errorMessage = err.localizedMessage ?: "Đăng nhập Google thất bại."
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
                        GoogleSignIn.getClient(context, gso).signOut()
                    }
                }
            } else if (selectedEmail.isNotBlank()) {
                Toast.makeText(
                    context,
                    "Đã chọn email: $selectedEmail. Nhập mật khẩu để tiếp tục đăng nhập.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } catch (e: ApiException) {
            val statusMsg = when (e.statusCode) {
                12500 -> "Google Play Services chưa được cập nhật trên thiết bị."
                12501 -> "Cần thêm SHA-1 fingerprint vào Firebase Console để bật Google Sign-In. Hãy nhập email @ut.edu.vn ở ô trên để đăng nhập trực tiếp!"
                10 -> "Chưa bật Google Sign-In trên Firebase Console (Developer Error)."
                else -> "Đăng nhập Google gặp lỗi (Mã lỗi: ${e.statusCode})."
            }
            errorMessage = statusMsg
            Toast.makeText(context, statusMsg, Toast.LENGTH_LONG).show()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Top Logo Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(GreenAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    LineIcon(HomeIcon.LINK, color = Color.White, modifier = Modifier.size(36.dp))
                }

                // App Title
                Text(
                    text = "LostLink",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    color = TextDark,
                    letterSpacing = (-0.5).sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Display error message if present
                errorMessage?.let { error ->
                    Surface(
                        color = Color(0xFFFDE8E8),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = error,
                                color = Color(0xFFE53E3E),
                                fontSize = 12.sp,
                            )

                            if (showResendVerificationButton) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { openGmailApp(context) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("Mở Gmail ✉️", fontSize = 11.sp, color = GreenAccent, fontWeight = FontWeight.Bold)
                                    }

                                    TextButton(
                                        onClick = {
                                            if (email.isNotBlank() && password.isNotBlank()) {
                                                loading = true
                                                accounts.sendVerificationEmailForAccount(email, password) { res ->
                                                    loading = false
                                                    res.onSuccess {
                                                        Toast.makeText(context, "Đã gửi lại thư xác minh tới Gmail ($email)! Kiểm tra hộp thư của bạn.", Toast.LENGTH_LONG).show()
                                                    }.onFailure { err ->
                                                        Toast.makeText(context, err.localizedMessage ?: "Gửi thư thất bại.", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            } else {
                                                Toast.makeText(context, "Vui lòng nhập email và mật khẩu để gửi lại thư xác minh.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("Gửi lại thư xác minh", fontSize = 11.sp, color = Color(0xFF191D2B), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Display success message if present
                successMessage?.let { msg ->
                    Surface(
                        color = Color(0xFFE6F4EA),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = msg,
                                color = Color(0xFF137333),
                                fontSize = 12.sp,
                            )

                            TextButton(
                                onClick = { openGmailApp(context) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Mở ứng dụng Gmail ✉️", fontSize = 12.sp, color = GreenAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Email Field
                AuthInputField(
                    label = "Email",
                    value = email,
                    onValueChange = { email = it; errorMessage = null; showResendVerificationButton = false },
                    placeholder = "Nhập email của bạn...",
                    icon = HomeIcon.PERSON,
                    keyboardType = KeyboardType.Email,
                    enabled = !loading,
                )

                // Name Field (Register Mode only)
                if (mode == AuthMode.REGISTER) {
                    AuthInputField(
                        label = "Tên",
                        value = name,
                        onValueChange = { name = it; errorMessage = null },
                        placeholder = "Nhập tên của bạn...",
                        icon = HomeIcon.BADGE,
                        keyboardType = KeyboardType.Text,
                        enabled = !loading,
                    )
                }

                // Password Field
                AuthInputField(
                    label = "Mật khẩu",
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = if (mode == AuthMode.LOGIN) "Nhập mật khẩu của bạn..." else "Nhập mật khẩu...",
                    icon = HomeIcon.LOCK,
                    isPassword = true,
                    enabled = !loading,
                )

                // Confirm Password Field (Register Mode only)
                if (mode == AuthMode.REGISTER) {
                    AuthInputField(
                        label = "Nhập lại mật khẩu",
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = null },
                        placeholder = "Nhập lại mật khẩu...",
                        icon = HomeIcon.LOCK,
                        isPassword = true,
                        enabled = !loading,
                    )
                }

                // Forgot Password Link (Login Mode only)
                if (mode == AuthMode.LOGIN) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            text = "Quên mật khẩu",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            modifier = Modifier.clickable {
                                showForgotPasswordDialog = true
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Primary Action Button
                Button(
                    onClick = {
                        errorMessage = null
                        successMessage = null
                        showResendVerificationButton = false
                        if (mode == AuthMode.LOGIN) {
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = "Vui lòng nhập đầy đủ email và mật khẩu."
                                return@Button
                            }
                            loading = true
                            accounts.signIn(email, password) { result ->
                                loading = false
                                result.onSuccess {
                                    onAuthSuccess()
                                }.onFailure { err ->
                                    val msg = err.localizedMessage.orEmpty()
                                    if (msg.contains("Verify your email") || msg.contains("xác minh")) {
                                        errorMessage = "Tài khoản của bạn chưa được xác minh email. Vui lòng kiểm tra hộp thư Gmail ($email) và nhấp vào link xác nhận."
                                        showResendVerificationButton = true
                                    } else {
                                        errorMessage = "Đăng nhập thất bại. Kiểm tra email, mật khẩu hoặc xác minh tài khoản."
                                        showResendVerificationButton = false
                                    }
                                }
                            }
                        } else {
                            if (name.isBlank()) {
                                errorMessage = "Vui lòng nhập họ và tên."
                                return@Button
                            }
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = "Vui lòng nhập đầy đủ email và mật khẩu."
                                return@Button
                            }
                            if (!FirebaseAccountRepository.isAllowedEmail(email)) {
                                errorMessage = "Vui lòng sử dụng địa chỉ email sinh viên @ut.edu.vn."
                                return@Button
                            }
                            if (password.length < 6) {
                                errorMessage = "Mật khẩu phải có ít nhất 6 ký tự."
                                return@Button
                            }
                            if (password != confirmPassword) {
                                errorMessage = "Mật khẩu nhập lại không khớp."
                                return@Button
                            }
                            loading = true
                            accounts.register(name, email, password) { result ->
                                loading = false
                                result.onSuccess {
                                    successMessage = "Đã gửi thư xác minh tới Gmail ($email)! Vui lòng mở ứng dụng Gmail và nhấp vào link xác minh tài khoản trước khi đăng nhập."
                                    mode = AuthMode.LOGIN
                                    password = ""
                                    confirmPassword = ""
                                }.onFailure { err ->
                                    errorMessage = err.localizedMessage ?: "Đăng ký thất bại. Vui lòng thử lại."
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenAccent),
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = if (mode == AuthMode.LOGIN) "Đăng nhập" else "Đăng ký",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                // OR Divider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = DividerColor,
                    )
                    Text(
                        text = "OR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB0B4BC),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = DividerColor,
                    )
                }

                // UTH / Google Account Sign In Button (Opens Account Picker Dialog)
                Surface(
                    onClick = {
                        errorMessage = null
                        showResendVerificationButton = false
                        try {
                            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                            val defaultWebClientId = if (resId != 0) context.resources.getString(resId) else ""

                            val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()

                            if (defaultWebClientId.isNotBlank()) {
                                gsoBuilder.requestIdToken(defaultWebClientId)
                            }

                            val googleSignInClient = GoogleSignIn.getClient(context, gsoBuilder.build())
                            googleSignInClient.signOut().addOnCompleteListener {
                                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Mở Google Sign-In thất bại. Nhập email @ut.edu.vn ở ô trên để đăng nhập.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = FieldBackground,
                    border = BorderStroke(1.dp, FieldBorder),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Đăng nhập bằng",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "UTH Logo",
                            modifier = Modifier.height(20.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Switch Mode Toggle Text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (mode == AuthMode.LOGIN) "Don't have an account? " else "Already have an account? ",
                        fontSize = 12.sp,
                        color = TextMuted,
                    )
                    Text(
                        text = if (mode == AuthMode.LOGIN) "Sign Up" else "Sign In",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        modifier = Modifier.clickable {
                            errorMessage = null
                            successMessage = null
                            showResendVerificationButton = false
                            mode = if (mode == AuthMode.LOGIN) AuthMode.REGISTER else AuthMode.LOGIN
                        },
                    )
                }
            }
        }
    }

    // Forgot Password Dialog
    if (showForgotPasswordDialog) {
        var resetEmail by remember { mutableStateOf(email) }
        var resetLoading by remember { mutableStateOf(value = false) }
        var resetError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!resetLoading) showForgotPasswordDialog = false },
            title = { Text("Quên mật khẩu", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nhập email @ut.edu.vn của bạn để nhận liên kết đặt lại mật khẩu.", fontSize = 13.sp, color = TextMuted)
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it; resetError = null },
                        label = { Text("Email sinh viên") },
                        singleLine = true,
                        enabled = !resetLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    resetError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !resetLoading && resetEmail.isNotBlank(),
                    onClick = {
                        resetLoading = true
                        resetError = null
                        accounts.sendPasswordReset(resetEmail) { result ->
                            resetLoading = false
                            result.onSuccess {
                                showForgotPasswordDialog = false
                                Toast.makeText(context, "Đã gửi email đặt lại mật khẩu! Kiểm tra hộp thư của bạn.", Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                resetError = err.localizedMessage ?: "Gửi email thất bại. Vui lòng kiểm tra lại địa chỉ email."
                            }
                        }
                    },
                ) {
                    Text(if (resetLoading) "Đang gửi..." else "Gửi liên kết", color = GreenAccent)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showForgotPasswordDialog = false },
                    enabled = !resetLoading,
                ) {
                    Text("Hủy", color = TextMuted)
                }
            },
        )
    }
}

private fun openGmailApp(context: Context) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_EMAIL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Không thể mở ứng dụng Gmail. Vui lòng tự mở ứng dụng Gmail trên điện thoại.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AuthInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: HomeIcon,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    var passwordVisible by remember { mutableStateOf(value = false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4B4F58),
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = FieldBackground,
            border = BorderStroke(1.dp, FieldBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        color = Color(0xFFA5A9B2),
                    )
                },
                leadingIcon = {
                    LineIcon(icon, color = Color(0xFF8C919E), modifier = Modifier.size(20.dp))
                },
                trailingIcon = if (isPassword) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            LineIcon(
                                if (passwordVisible) HomeIcon.EYE_OFF else HomeIcon.EYE,
                                color = Color(0xFF8C919E),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else null,
                singleLine = true,
                enabled = enabled,
                visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Next,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = TextDark,
                    unfocusedTextColor = TextDark,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
