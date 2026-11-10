package com.example.lostlink

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.auth.AuthScreen
import com.example.lostlink.ui.home.HomeRoute
import com.example.lostlink.ui.home.HomeViewModel
import com.example.lostlink.ui.home.HomeTheme
import com.example.lostlink.ui.detail.PostDetailNavigation
import com.example.lostlink.ui.detail.PostDetailRoute
import com.example.lostlink.ui.detail.PostDetailViewModel
import com.example.lostlink.ui.post.PostFormScreen
import com.example.lostlink.ui.post.PostFormViewModel
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    private var pendingPostId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPostId = intent.getStringExtra(PostDetailNavigation.EXTRA_POST_ID)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        val model = ViewModelProvider(this)[HomeViewModel::class.java]
        val detailModel = ViewModelProvider(this)[PostDetailViewModel::class.java]
        val postFormModel = ViewModelProvider(this, PostFormViewModel.factory(applicationContext))[PostFormViewModel::class.java]
        setContent {
            val accounts = remember { FirebaseAccountRepository() }
            val auth = remember { FirebaseAuth.getInstance() }
            fun activeAccount() = accounts.currentUserId.takeIf { accounts.isUserLoggedInAndVerified }
            var accountId by remember { mutableStateOf(activeAccount()) }
            DisposableEffect(auth) {
                fun updateAccount() {
                    val next = activeAccount()
                    postFormModel.bindUser(next)
                    accountId = next
                }
                val accountListener = FirebaseAuth.AuthStateListener { updateAccount() }
                val tokenListener = FirebaseAuth.IdTokenListener { updateAccount() }
                auth.addAuthStateListener(accountListener)
                auth.addIdTokenListener(tokenListener)
                onDispose { auth.removeAuthStateListener(accountListener); auth.removeIdTokenListener(tokenListener) }
            }

            if (accountId != null) key(accountId) {
                var selectedPostId by rememberSaveable { mutableStateOf<String?>(null) }
                var isCreatingPost by rememberSaveable { mutableStateOf(false) }

                fun closeDetail() { selectedPostId = null; detailModel.clearSelection() }
                LaunchedEffect(pendingPostId) {
                    pendingPostId?.let { id ->
                        selectedPostId = id
                        pendingPostId = null
                        intent.removeExtra(PostDetailNavigation.EXTRA_POST_ID)
                    }
                }

                if (isCreatingPost) {
                    HomeTheme {
                        PostFormScreen(
                            uid = accountId,
                            onLeave = { isCreatingPost = false },
                            vm = postFormModel,
                            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                            onPostCreated = { postId ->
                                isCreatingPost = false
                                selectedPostId = postId
                                model.refreshAfterPostCreated()
                            }
                        )
                    }
                } else {
                    HomeRoute(
                        model = model,
                        onPost = { selectedPostId = it },
                        onCreatePost = { lost ->
                            accountId?.let { uid ->
                                closeDetail()
                                postFormModel.openForUser(uid, lost)
                                isCreatingPost = true
                            }
                        },
                        detailContent = selectedPostId?.let { id -> ({ PostDetailRoute(detailModel, id, ::closeDetail, currentUserId = accountId) }) },
                        onReturnHome = ::closeDetail,
                        onSignOut = {
                            closeDetail()
                            isCreatingPost = false
                            pendingPostId = null
                            intent.removeExtra(PostDetailNavigation.EXTRA_POST_ID)
                            accounts.signOut()
                            accountId = null
                        }
                    )
                }
            } else {
                AuthScreen(
                    accounts = accounts,
                    onAuthSuccess = {
                        accountId = activeAccount()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPostId = intent.getStringExtra(PostDetailNavigation.EXTRA_POST_ID)
    }
}
