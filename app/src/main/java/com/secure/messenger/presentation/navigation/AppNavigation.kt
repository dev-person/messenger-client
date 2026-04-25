package com.secure.messenger.presentation.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.presentation.ui.auth.AuthScreen
import com.secure.messenger.presentation.ui.calls.CallScreen
import com.secure.messenger.presentation.ui.chat.ChatScreen
import com.secure.messenger.presentation.ui.groups.CreateGroupScreen
import com.secure.messenger.presentation.ui.groups.GroupInfoScreen
import com.secure.messenger.presentation.ui.main.HomeScreen
import com.secure.messenger.presentation.ui.permissions.DiagnosticsScreen
import com.secure.messenger.presentation.ui.profile.ProfileSetupScreen
import com.secure.messenger.presentation.viewmodel.CallViewModel

sealed class Screen(val route: String) {
    object Auth         : Screen("auth")
    object ProfileSetup : Screen("profile_setup")
    object Home         : Screen("home")
    object Chat         : Screen("chat/{chatId}") {
        fun navigate(chatId: String) = "chat/$chatId"
    }
    object Call         : Screen("call/{userId}/{isVideo}/{peerName}/{isIncoming}") {
        fun navigate(userId: String, isVideo: Boolean, peerName: String, isIncoming: Boolean) =
            "call/$userId/$isVideo/${Uri.encode(peerName)}/$isIncoming"
    }
    object CreateGroup  : Screen("groups/new")
    object GroupInfo    : Screen("groups/{chatId}") {
        fun navigate(chatId: String) = "groups/$chatId"
    }
    object Diagnostics  : Screen("diagnostics")
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    // Shared CallViewModel at NavHost scope — lives as long as the nav graph.
    // Used to detect incoming calls and navigate to CallScreen from anywhere.
    val callViewModel: CallViewModel = hiltViewModel()
    val activeCall by callViewModel.activeCallState.collectAsStateWithLifecycle()

    // Navigate to CallScreen when an incoming call arrives (RINGING state).
    // ВАЖНО: передаём isIncoming=true, чтобы CallScreen НЕ начал автоматически
    // исходящий вызов. Раньше при гонке incoming_call → end_call (звонящий
    // бросил трубку до того как абонент успел открыть приложение) экран открывался,
    // call к этому моменту уже null, и LaunchedEffect автостарта думал что это
    // исходящий → запускал реальный исходящий звонок «А → B», хотя B уже отбой.
    LaunchedEffect(activeCall?.id) {
        val call = activeCall ?: return@LaunchedEffect
        if (call.state == CallState.RINGING) {
            val peerName = call.callerId   // will be resolved to display name when user info is loaded
            val isVideo = call.type == CallType.VIDEO
            navController.navigate(
                Screen.Call.navigate(call.callerId, isVideo, peerName, isIncoming = true)
            ) {
                launchSingleTop = true
            }
        }
    }

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
                onCreateGroupClick = { navController.navigate(Screen.CreateGroup.route) },
                onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) },
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onCallClick = { userId, isVideo, peerName ->
                    // Из чата — это всегда ИСХОДЯЩИЙ звонок (юзер сам нажал кнопку)
                    navController.navigate(
                        Screen.Call.navigate(userId, isVideo, peerName, isIncoming = false)
                    )
                },
                onGroupInfoClick = { chatId ->
                    navController.navigate(Screen.GroupInfo.navigate(chatId))
                },
            )
        }

        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(
                onBack = { navController.popBackStack() },
                onGroupCreated = { chatId ->
                    // Сразу открываем созданную группу, подменяя CreateGroup в backstack.
                    navController.navigate(Screen.Chat.navigate(chatId)) {
                        popUpTo(Screen.CreateGroup.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Screen.GroupInfo.route,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) {
            GroupInfoScreen(
                onBack = { navController.popBackStack() },
                onLeft = {
                    // После выхода из группы возвращаемся на экран со списком чатов.
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("userId")     { type = NavType.StringType },
                navArgument("isVideo")    { type = NavType.BoolType },
                navArgument("peerName")   { type = NavType.StringType },
                navArgument("isIncoming") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val userId     = backStackEntry.arguments?.getString("userId")     ?: ""
            val isVideo    = backStackEntry.arguments?.getBoolean("isVideo")   ?: false
            val peerName   = backStackEntry.arguments?.getString("peerName")   ?: ""
            val isIncoming = backStackEntry.arguments?.getBoolean("isIncoming") ?: false
            CallScreen(
                userId     = userId,
                isVideo    = isVideo,
                peerName   = peerName,
                isIncoming = isIncoming,
                onCallEnd  = { navController.popBackStack() },
                viewModel  = callViewModel,
            )
        }
    }
}
