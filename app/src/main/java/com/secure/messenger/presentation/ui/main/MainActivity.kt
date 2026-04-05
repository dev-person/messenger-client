package com.secure.messenger.presentation.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.presentation.navigation.AppNavHost
import com.secure.messenger.presentation.navigation.Screen
import com.secure.messenger.presentation.theme.SecureMessengerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authTokenProvider: AuthTokenProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SecureMessengerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val startDestination = if (authTokenProvider.hasToken()) {
                        Screen.Home.route
                    } else {
                        Screen.Auth.route
                    }
                    AppNavHost(navController = navController, startDestination = startDestination)
                }
            }
        }
    }
}
