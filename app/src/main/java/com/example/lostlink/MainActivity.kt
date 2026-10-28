package com.example.lostlink

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.example.lostlink.data.FirebaseAccountRepository
import com.example.lostlink.ui.auth.AuthScreen
import com.example.lostlink.ui.home.HomeRoute
import com.example.lostlink.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        val model = ViewModelProvider(this)[HomeViewModel::class.java]
        setContent {
            val accounts = remember { FirebaseAccountRepository() }
            var isLoggedIn by remember { mutableStateOf(accounts.isUserLoggedInAndVerified) }

            if (isLoggedIn) {
                HomeRoute(
                    model = model,
                    onSignOut = {
                        accounts.signOut()
                        isLoggedIn = false
                    }
                )
            } else {
                AuthScreen(
                    accounts = accounts,
                    onAuthSuccess = {
                        isLoggedIn = true
                    }
                )
            }
        }
    }
}
