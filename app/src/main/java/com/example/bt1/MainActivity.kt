package com.example.bt1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.bt1.data.AuthViewModel
import com.example.bt1.ui.screens.HomeScreen
import com.example.bt1.ui.screens.NotificationsScreen
import com.example.bt1.ui.screens.ProfileScreen
import com.example.bt1.ui.screens.TaskListScreen
import com.example.bt1.ui.screens.WelcomeAuthScreen
import com.example.bt1.ui.theme.Bt1Theme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle permission grant result if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            Bt1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UthTodoApp()
                }
            }
        }
    }
}

@Composable
fun UthTodoApp(
    authViewModel: AuthViewModel = viewModel()
) {
    val navController = rememberNavController()
    val currentUser by authViewModel.currentUserProfile.collectAsState()

    val startDestination = if (currentUser != null) "home" else "welcome"

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("welcome") {
            WelcomeAuthScreen(
                authViewModel = authViewModel,
                onAuthSuccess = {
                    navController.navigate("home") {
                        popUpTo("welcome") { inclusive = true }
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                authViewModel = authViewModel,
                onNavigateToProfile = {
                    navController.navigate("profile")
                },
                onNavigateToNotifications = {
                    navController.navigate("notifications")
                },
                onNavigateToTaskList = {
                    navController.navigate("taskList")
                }
            )
        }

        composable("taskList") {
            TaskListScreen(
                authViewModel = authViewModel,
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onNavigateToNotifications = {
                    navController.navigate("notifications")
                },
                onNavigateToProfile = {
                    navController.navigate("profile")
                }
            )
        }

        composable("profile") {
            ProfileScreen(
                authViewModel = authViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    navController.navigate("welcome") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }

        composable("notifications") {
            NotificationsScreen(
                authViewModel = authViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onNavigateToHome = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onNavigateToTaskList = {
                    navController.navigate("taskList")
                },
                onNavigateToProfile = {
                    navController.navigate("profile")
                }
            )
        }
    }
}
