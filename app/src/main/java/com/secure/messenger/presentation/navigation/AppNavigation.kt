package com.secure.messenger.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.secure.messenger.presentation.ui.auth.AuthScreen
import com.secure.messenger.presentation.ui.calls.CallScreen
import com.secure.messenger.presentation.ui.chat.ChatScreen
import com.secure.messenger.presentation.ui.main.HomeScreen
import com.secure.messenger.presentation.ui.profile.ProfileSetupScreen

sealed class Screen(val route: String) {
    object Auth         : Screen("auth")
    object ProfileSetup : Screen("profile_setup")
    object Home         : Screen("home")
    object Chat         : Screen("chat/{chatId}") {
        fun navigate(chatId: String) = "chat/$chatId"
    }
    object Call         : Screen("call/{userId}/{isVideo}") {
        fun navigate(userId: String, isVideo: Boolean) = "call/$userId/$isVideo"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Auth.route) {
            AuthScreen(onAuthSuccess = { isNewUser ->
                val dest = if (isNewUser) Screen.ProfileSetup.route else Screen.Home.route
                navController.navigate(dest) {
                    popUpTo(Screen.Auth.route) { inclusive = true }
                }
            })
        }

        composable(Screen.ProfileSetup.route) {
            ProfileSetupScreen(onSuccess = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.ProfileSetup.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onChatClick = { chatId -> navController.navigate(Screen.Chat.navigate(chatId)) },
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onCallClick = { userId, isVideo ->
                    navController.navigate(Screen.Call.navigate(userId, isVideo))
                },
            )
        }

        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType },
            ),
        ) {
            CallScreen(onCallEnd = { navController.popBackStack() })
        }
    }
}
