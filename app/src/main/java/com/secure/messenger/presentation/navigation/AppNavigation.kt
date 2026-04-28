package com.secure.messenger.presentation.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
    /**
     * Групповой звонок (mesh P2P до 4 человек).
     *
     * Параметры:
     *  - chatId: id группы где идёт звонок (нужен для start API)
     *  - isVideo: true для VIDEO, false для AUDIO
     *  - existingCallId: если присоединяемся к идущему — id звонка, иначе пустая строка
     *  - inviteUserIds: comma-separated id юзеров, которых выбрал инициатор. "_"
     *    означает «всем» (или приглашённый присоединяется — в этом случае не используется).
     */
    object GroupCall : Screen("group-call/{chatId}/{isVideo}/{existingCallId}/{inviteUserIds}") {
        fun navigate(
            chatId: String,
            isVideo: Boolean,
            existingCallId: String?,
            inviteUserIds: List<String>? = null,
        ): String {
            val callPart = existingCallId.orEmpty().ifEmpty { "_" }
            val invitePart = inviteUserIds?.takeIf { it.isNotEmpty() }
                ?.joinToString(",") ?: "_"
            return "group-call/$chatId/$isVideo/$callPart/$invitePart"
        }
    }
    /**
     * Экран выбора участников перед стартом группового звонка. Юзер ставит
     * галочки и тапает «Позвонить» — переход на [GroupCall] со списком
     * выбранных. Здесь же поиск по группе.
     */
    object GroupCallPicker : Screen("group-call-picker/{chatId}/{isVideo}") {
        fun navigate(chatId: String, isVideo: Boolean) =
            "group-call-picker/$chatId/$isVideo"
    }
    object Diagnostics  : Screen("diagnostics")
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    pendingChatIdFlow: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    onPendingChatIdConsumed: () -> Unit = {},
    isInPipModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    pendingGroupCallFlow: kotlinx.coroutines.flow.StateFlow<com.secure.messenger.presentation.ui.main.MainActivity.PendingGroupCall?>? = null,
    onPendingGroupCallConsumed: () -> Unit = {},
    pendingIncomingCallFlow: kotlinx.coroutines.flow.StateFlow<com.secure.messenger.presentation.ui.main.MainActivity.PendingIncomingCall?>? = null,
    onPendingIncomingCallConsumed: () -> Unit = {},
) {
    // Shared CallViewModel at NavHost scope — lives as long as the nav graph.
    // Used to detect incoming calls and navigate to CallScreen from anywhere.
    val callViewModel: CallViewModel = hiltViewModel()
    val activeCall by callViewModel.activeCallState.collectAsStateWithLifecycle()

    // Deep-link из FCM: тап по уведомлению — пробрасываем в Chat.
    // Не открываем чат если пользователь не залогинен (startDestination=Auth):
    // FCM не должно ломать onboarding, в Auth-режиме нет даже chat/{chatId}
    // экрана с данными. Звонки имеют свой собственный путь (CallViewModel)
    // и обрабатываются выше.
    val pendingChatId by (pendingChatIdFlow
        ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingChatId) {
        val chatId = pendingChatId ?: return@LaunchedEffect
        if (startDestination != Screen.Auth.route &&
            startDestination != Screen.ProfileSetup.route) {
            navController.navigate(Screen.Chat.navigate(chatId)) {
                launchSingleTop = true
            }
        }
        onPendingChatIdConsumed()
    }

    // Deep-link на групповой звонок: тап по входящему уведомлению
    val pendingGroupCall by (pendingGroupCallFlow
        ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingGroupCall) {
        val gc = pendingGroupCall ?: return@LaunchedEffect
        if (startDestination != Screen.Auth.route &&
            startDestination != Screen.ProfileSetup.route) {
            navController.navigate(
                Screen.GroupCall.navigate(gc.chatId, gc.isVideo, gc.callId, null)
            ) {
                launchSingleTop = true
            }
        }
        onPendingGroupCallConsumed()
    }

    // Deep-link на ВХОДЯЩИЙ 1-1 звонок: тап по FCM/WS notification приносит
    // данные звонка в extras → MainActivity складывает в этот flow → мы
    // навигируем на CallScreen с isIncoming=true. Без этого юзер по тапу
    // на «звонит Иван» попадал на список диалогов, потому что WS-event
    // мог не успеть дойти до CallRepository.
    val pendingIncoming by (pendingIncomingCallFlow
        ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    LaunchedEffect(pendingIncoming) {
        val pic = pendingIncoming ?: return@LaunchedEffect
        if (startDestination != Screen.Auth.route &&
            startDestination != Screen.ProfileSetup.route) {
            navController.navigate(
                Screen.Call.navigate(
                    userId = pic.callerId,
                    isVideo = pic.isVideo,
                    peerName = pic.callerName,
                    isIncoming = true,
                )
            ) {
                launchSingleTop = true
            }
        }
        onPendingIncomingCallConsumed()
    }

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

    // Плашка «Идёт звонок · вернуться» рисуется внутри каждого экрана под
    // его шапкой (см. OngoingCallBar в ChatScreen / HomeScreen) — так она
    // не перекрывает топбары и встаёт там где её ожидает юзер. Глобально
    // здесь не накладываем.

    // Дефолтные transitions navigation-compose 2.8 (fadeIn 700мс) делали
    // переходы вязкими — input «залипал». Заменяем на короткий slide 200мс:
    // плавно как в нативе, но достаточно быстро чтобы не блокировать тач-евенты.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(200),
                initialOffsetX = { it },
            )
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = androidx.compose.animation.core.tween(200),
                targetOffsetX = { -it / 4 },
            )
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(200),
                initialOffsetX = { -it / 4 },
            )
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = androidx.compose.animation.core.tween(200),
                targetOffsetX = { it },
            )
        },
    ) {

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
                onGroupCallClick = { chatId, isVideo ->
                    // Сначала отправляем юзера выбирать с кем звонить.
                    navController.navigate(Screen.GroupCallPicker.navigate(chatId, isVideo))
                },
                onJoinGroupCall = { chatId, callId, isVideo ->
                    // Идущий звонок — присоединяемся напрямую, без picker'а.
                    navController.navigate(
                        Screen.GroupCall.navigate(chatId, isVideo, callId, null)
                    )
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
                onGroupCall = { chatId, isVideo ->
                    // Тап по «Аудио» / «Видео» в шапке группы — тот же путь
                    // что и из ChatScreen: сначала picker участников.
                    navController.navigate(Screen.GroupCallPicker.navigate(chatId, isVideo))
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
            val isInPip by (isInPipModeFlow
                ?: kotlinx.coroutines.flow.MutableStateFlow(false))
                .collectAsStateWithLifecycle()
            CallScreen(
                userId     = userId,
                isVideo    = isVideo,
                peerName   = peerName,
                isIncoming = isIncoming,
                onCallEnd  = { navController.popBackStack() },
                viewModel  = callViewModel,
                isInPipMode = isInPip,
            )
        }

        composable(
            route = Screen.GroupCall.route,
            arguments = listOf(
                navArgument("chatId")          { type = NavType.StringType },
                navArgument("isVideo")         { type = NavType.BoolType },
                navArgument("existingCallId")  { type = NavType.StringType },
                navArgument("inviteUserIds")   { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val chatId  = backStackEntry.arguments?.getString("chatId").orEmpty()
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            val raw     = backStackEntry.arguments?.getString("existingCallId").orEmpty()
            val existingCallId = if (raw.isBlank() || raw == "_") null else raw
            val invitedRaw = backStackEntry.arguments?.getString("inviteUserIds").orEmpty()
            val invitedIds: List<String>? = if (invitedRaw.isBlank() || invitedRaw == "_") {
                null
            } else {
                invitedRaw.split(",").filter { it.isNotBlank() }
            }
            com.secure.messenger.presentation.ui.calls.GroupCallScreen(
                chatId = chatId,
                isVideo = isVideo,
                existingCallId = existingCallId,
                inviteUserIds = invitedIds,
                onCallEnd = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.GroupCallPicker.route,
            arguments = listOf(
                navArgument("chatId")  { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val chatId  = backStackEntry.arguments?.getString("chatId").orEmpty()
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            com.secure.messenger.presentation.ui.calls.GroupCallParticipantPickerScreen(
                chatId = chatId,
                isVideo = isVideo,
                onBack = { navController.popBackStack() },
                onConfirm = { selectedIds ->
                    // Уходим со страницы picker'а сразу, чтобы по back из звонка
                    // юзер вернулся в чат, а не обратно в picker.
                    navController.navigate(
                        Screen.GroupCall.navigate(chatId, isVideo, null, selectedIds)
                    ) {
                        popUpTo(Screen.GroupCallPicker.route) { inclusive = true }
                    }
                },
            )
        }
    } // end NavHost
}

/**
 * Плашка «Идёт звонок · вернуться». Встраивается в экраны (ChatScreen,
 * HomeScreen) под собственным top-bar'ом — так она не перекрывает шапку
 * и встаёт логично там где юзер её ожидает (как в Telegram). На самом
 * экране звонка не показывается. Тап навигирует обратно на CallScreen.
 *
 * navController нужен композаблу извне, поэтому используем глобальный
 * via LocalContext / activity-cast — нет, проще принять onClick. Caller
 * (экран) сам решает куда вести.
 */
@Composable
fun OngoingCallBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
) {
    Surface(
        color = Color(0xFF2E7D32),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isVideo) "Идёт видеозвонок · вернуться"
                       else "Идёт звонок · вернуться",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
