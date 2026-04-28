package com.secure.messenger.presentation.ui.main

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secure.messenger.R
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.secure.messenger.MessengerApp
import com.secure.messenger.data.remote.api.MessengerApi
import retrofit2.HttpException
import com.secure.messenger.data.remote.api.UpdateInfoDto
import com.secure.messenger.data.remote.websocket.SignalingClient
import com.secure.messenger.di.AuthTokenProvider
import com.secure.messenger.presentation.navigation.AppNavHost
import com.secure.messenger.presentation.navigation.Screen
import com.secure.messenger.presentation.theme.SecureMessengerTheme
import com.secure.messenger.presentation.theme.ThemePreferences
import com.secure.messenger.utils.TextEnhancer
import com.secure.messenger.service.FcmService
import com.secure.messenger.service.MessagingService
import com.secure.messenger.utils.UpdateManager
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authTokenProvider: AuthTokenProvider
    @Inject lateinit var authRepository: com.secure.messenger.domain.repository.AuthRepository
    @Inject lateinit var api: MessengerApi
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var chatRepository: com.secure.messenger.domain.repository.ChatRepository

    /**
     * chatId, который надо открыть после старта (или резюма) активности —
     * прилетает из Intent'а уведомления. Compose-слой подписывается и переходит
     * на ChatScreen, потом сбрасывает значение через [consumePendingChatId].
     */
    private val pendingChatId = MutableStateFlow<String?>(null)

    /**
     * Deep-link на групповой звонок из notification: chatId + callId + isVideo.
     * Compose-слой в [AppNavHost] подхватывает и переходит на GroupCallScreen,
     * затем сбрасывает значение.
     */
    data class PendingGroupCall(val chatId: String, val callId: String, val isVideo: Boolean)
    private val pendingGroupCall = MutableStateFlow<PendingGroupCall?>(null)

    /**
     * Deep-link на ВХОДЯЩИЙ 1-1 звонок из FCM-/WS-notification: callerId,
     * isVideo + имя для шапки CallScreen.
     */
    data class PendingIncomingCall(
        val callId: String,
        val callerId: String,
        val isVideo: Boolean,
        val callerName: String,
    )
    private val pendingIncomingCall = MutableStateFlow<PendingIncomingCall?>(null)

    /**
     * true когда юзер сейчас находится на экране звонка. Compose-слой
     * выставляет этот флаг через [onCallScreenEntered] / [onCallScreenLeft].
     * Используется для решения: входить ли в PiP при свёртывании.
     */
    private val isOnCallScreen = MutableStateFlow(false)

    /**
     * true когда активность в PiP-режиме (свёрнутое окно поверх других
     * приложений). Compose читает и адаптирует UI: убирает кнопки и шапку,
     * оставляет только видеопоток.
     */
    private val isInPipMode = MutableStateFlow(false)

    // Launcher for POST_NOTIFICATIONS permission (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result — nothing to do, notifications will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // transparent навбар обоими способами: SystemBarStyle(transparent, transparent)
        // перекрывает автоматический scrim enableEdgeToEdge(), а
        // isNavigationBarContrastEnforced=false выключает контраст-оверлей
        // на Android 10+. Без этого свой кастомный градиент не виден.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Allow this activity to show over the lock screen — needed for incoming call UI
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        requestFullScreenIntentPermission()

        // Запускаем WebSocket сервис если пользователь уже авторизован.
        // FCM обеспечивает доставку когда сервис убит, поэтому foreground не нужен.
        if (authTokenProvider.hasToken()) {
            startService(Intent(this, MessagingService::class.java))
        }

        // Инициализируем настройки цветовой схемы и on-device AI
        ThemePreferences.init(this)
        TextEnhancer.initAi()

        // Определяем начальный экран: Auth → ProfileSetup (если нет username) → Home
        val startDestination = resolveStartDestination()

        // Поднимаем флаг для deep-link из FCM-уведомления (приложение могло быть
        // убито, и intent прилетит уже здесь).
        readPendingChatIdFromIntent(intent)

        setContent {
            val selectedScheme by ThemePreferences.colorScheme.collectAsState()
            SecureMessengerTheme(colorScheme = selectedScheme) {
                // Цвет иконок статус-бара / нав-бара подстраиваем под ФОН
                // приложения (а не под системную тему). Иначе при Material Dark
                // в приложении и светлой теме в системе иконки заряда/времени
                // рисуются тёмными и плохо видны на тёмном фоне.
                val colorScheme = androidx.compose.material3.MaterialTheme.colorScheme
                val isAppDark = colorScheme.background.luminance() < 0.5f
                val view = androidx.compose.ui.platform.LocalView.current
                if (!view.isInEditMode) {
                    androidx.compose.runtime.SideEffect {
                        val controller = androidx.core.view.WindowCompat
                            .getInsetsController(window, view)
                        // true = тёмные иконки (для светлого фона), false = светлые
                        controller.isAppearanceLightStatusBars = !isAppDark
                        controller.isAppearanceLightNavigationBars = !isAppDark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    // Отслеживаем «мы сейчас на экране звонка» — нужно для PiP
                    // (входим в picture-in-picture при Home, только если звонок).
                    LaunchedEffect(navController) {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            val route = entry.destination.route.orEmpty()
                            // Под "call screen" попадают и 1-1 (call/), и
                            // групповые звонки (group-call/) — оба должны
                            // уходить в PiP при свёртке.
                            val onCall = route.startsWith("call/") ||
                                route.startsWith("group-call/")
                            isOnCallScreen.value = onCall
                            updateAutoEnterPip(onCall)
                        }
                    }

                    // Слушаем force_logout — при отзыве сессии перекидываем на авторизацию
                    LaunchedEffect(Unit) {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(ctx: Context, intent: Intent) {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                        ContextCompat.registerReceiver(
                            this@MainActivity, receiver,
                            IntentFilter(MessagingService.ACTION_FORCE_LOGOUT),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                    }

                    AppNavHost(
                        navController = navController,
                        startDestination = startDestination,
                        pendingChatIdFlow = pendingChatId,
                        onPendingChatIdConsumed = { pendingChatId.value = null },
                        isInPipModeFlow = isInPipMode,
                        pendingGroupCallFlow = pendingGroupCall,
                        onPendingGroupCallConsumed = { pendingGroupCall.value = null },
                        pendingIncomingCallFlow = pendingIncomingCall,
                        onPendingIncomingCallConsumed = { pendingIncomingCall.value = null },
                    )

                    // Диалог обновления приложения
                    UpdateDialog(updateManager = updateManager)
                }

                // Reveal-анимация смены темы теперь встроена внутрь
                // SecureMessengerTheme через graphicsLayer — отдельный overlay
                // больше не нужен.
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Presence (online/offline) и isInForeground теперь живут в
        // MessengerApp.registerProcessLifecycleObserver — это надёжнее
        // чем Activity-lifecycle на проблемных прошивках. Здесь оставлены
        // только действия завязанные именно на эту Activity.
        if (authTokenProvider.hasToken()) {
            // Сообщаем серверу актуальную версию APK — для админ-панели.
            registerAppVersion()
            // Re-sync чатов: подтягиваем актуальные имена/аватарки/онлайн-статусы
            // партнёров. Fallback если WS-event user_updated был пропущен.
            lifecycleScope.launch {
                runCatching {
                    val me = authRepository.currentUser.firstOrNull() ?: return@runCatching
                    chatRepository.syncChats(me.id)
                }
            }
        }
    }

    /**
     * Шлёт текущую версию APK на сервер. Не блокирует запуск, ошибки логируются.
     * Используем строго типизированный data class — Map<String, Any> через Moshi
     * для смешанных типов (Int + String) работал нестабильно.
     */
    private fun registerAppVersion() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            runCatching {
                api.registerAppVersion(
                    com.secure.messenger.data.remote.api.AppVersionRequest(
                        versionCode = com.secure.messenger.BuildConfig.VERSION_CODE,
                        versionName = com.secure.messenger.BuildConfig.VERSION_NAME,
                    )
                )
            }.onFailure { timber.log.Timber.e(it, "registerAppVersion failed") }
        }
    }

    /**
     * Если активность уже жива и юзер тапнул по уведомлению — Android доставляет
     * новый Intent сюда (благодаря FLAG_ACTIVITY_SINGLE_TOP в FcmService).
     * Поднимаем pendingChatId — Compose увидит, перейдёт на ChatScreen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPendingChatIdFromIntent(intent)
    }

    private fun readPendingChatIdFromIntent(intent: Intent?) {
        if (intent == null) return
        // Сначала проверяем deep-link на групповой звонок (приоритетнее обычного чата).
        val groupCallChatId = intent.getStringExtra(MessagingService.EXTRA_GROUP_CALL_CHAT_ID)
        val groupCallId = intent.getStringExtra(MessagingService.EXTRA_GROUP_CALL_ID)
        if (groupCallChatId != null && groupCallId != null) {
            val isVideo = intent.getBooleanExtra(MessagingService.EXTRA_GROUP_CALL_VIDEO, false)
            intent.removeExtra(MessagingService.EXTRA_GROUP_CALL_CHAT_ID)
            intent.removeExtra(MessagingService.EXTRA_GROUP_CALL_ID)
            intent.removeExtra(MessagingService.EXTRA_GROUP_CALL_VIDEO)
            pendingGroupCall.value = PendingGroupCall(groupCallChatId, groupCallId, isVideo)
            return
        }

        // Deep-link на ВХОДЯЩИЙ 1-1 звонок из FCM или WS-notification.
        // Если процесс был убит и WS-event incoming_call не успел дойти,
        // FCM-fallback приносит данные звонка прямо в Intent extras —
        // открываем CallScreen даже без активного WS-state.
        val incomingCallId = intent.getStringExtra(FcmService.EXTRA_INCOMING_CALL_ID)
        val incomingCallerId = intent.getStringExtra(FcmService.EXTRA_INCOMING_CALLER_ID)
        if (!incomingCallId.isNullOrBlank() && !incomingCallerId.isNullOrBlank()) {
            val isVideo = intent.getBooleanExtra(FcmService.EXTRA_INCOMING_CALL_VIDEO, false)
            val callerName = intent.getStringExtra(FcmService.EXTRA_INCOMING_CALLER_NAME).orEmpty()
            intent.removeExtra(FcmService.EXTRA_INCOMING_CALL_ID)
            intent.removeExtra(FcmService.EXTRA_INCOMING_CALLER_ID)
            intent.removeExtra(FcmService.EXTRA_INCOMING_CALL_VIDEO)
            intent.removeExtra(FcmService.EXTRA_INCOMING_CALLER_NAME)
            pendingIncomingCall.value = PendingIncomingCall(
                callId = incomingCallId,
                callerId = incomingCallerId,
                isVideo = isVideo,
                callerName = callerName.ifBlank { incomingCallerId },
            )
            return
        }

        val chatId = intent.getStringExtra(FcmService.EXTRA_OPEN_CHAT_ID) ?: return
        // Сразу убираем extra, чтобы при последующих re-create (поворот экрана,
        // тёмная тема и т.д.) повторно не перенавигировать на чат.
        intent.removeExtra(FcmService.EXTRA_OPEN_CHAT_ID)
        pendingChatId.value = chatId
    }

    override fun onStop() {
        super.onStop()
        // isInForeground / presence теперь обрабатывается в
        // MessengerApp.registerProcessLifecycleObserver на уровне процесса.
    }

    /**
     * Вызывается когда пользователь нажимает Home / переключается в Recents.
     * Если сейчас идёт звонок — автоматически уходим в Picture-in-Picture,
     * чтобы юзер мог продолжать видеть собеседника поверх других приложений
     * вместо того, чтобы стрим резко обрывался.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isOnCallScreen.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tryEnterPip()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun tryEnterPip() {
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }.onFailure { e ->
            timber.log.Timber.w(e, "tryEnterPip failed")
        }
    }

    /**
     * Включает auto-enter PiP при свёртке (Android 12+). На современных
     * прошивках с gesture-навигацией onUserLeaveHint часто не вызывается —
     * приложение уходит через onPause без хинта. setAutoEnterEnabled
     * заставляет систему автоматически переводить Activity в PiP при любом
     * способе свёртки, без необходимости явного вызова enterPictureInPictureMode.
     * На API < 31 — fallback на onUserLeaveHint + onPause-вход.
     */
    private fun updateAutoEnterPip(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(9, 16))
                .setAutoEnterEnabled(enabled)
                .build()
            setPictureInPictureParams(params)
        }.onFailure { e ->
            timber.log.Timber.w(e, "setPictureInPictureParams failed")
        }
    }

    /**
     * Для устройств API < 31 (где нет setAutoEnterEnabled): входим в PiP
     * вручную при onPause, если мы на экране звонка. Покрывает gesture
     * navigation которая может не звать onUserLeaveHint.
     */
    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isOnCallScreen.value &&
            !isInPipMode.value &&
            !isFinishing
        ) {
            tryEnterPip()
        }
    }

    @Deprecated("override for old API; we read isInPictureInPictureMode in modern API too")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    /**
     * Проверяет, заполнен ли username у авторизованного пользователя.
     * Если нет — отправляем на экран настройки профиля.
     */
    private fun resolveStartDestination(): String {
        if (!authTokenProvider.hasToken()) return Screen.Auth.route

        // Быстрая проверка: загружаем профиль с сервера (блокирующий вызов во время splash)
        return try {
            val user = runBlocking { api.getMe().data }
            if (user?.username.isNullOrBlank()) Screen.ProfileSetup.route else Screen.Home.route
        } catch (e: HttpException) {
            // 401 — токен невалиден (истёк, другой сервер и т.д.) → сносим все локальные данные
            if (e.code() == 401) {
                runBlocking { authRepository.logout() }
                Screen.Auth.route
            } else {
                // Другие HTTP-ошибки (500, 503...) — сеть есть, но сервер проблемный.
                // Показываем кэш, пользователь увидит ошибку в UI.
                Screen.Home.route
            }
        } catch (_: Exception) {
            // Нет сети — показываем кэш
            Screen.Home.route
        }
    }

    /**
     * On Android 13+ (API 33) notifications require a runtime permission.
     * Without POST_NOTIFICATIONS, the OS silently drops every notification.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Ask the user to exempt this app from battery optimization.
     * Without this, Android aggressively kills the MessagingService on most devices
     * (especially Xiaomi, Samsung, Huawei) when the screen turns off.
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    /**
     * On Android 14+ (API 34), USE_FULL_SCREEN_INTENT became a special permission that
     * users must grant explicitly. Without it, incoming call notifications won't show
     * over the lock screen — they fall back to regular heads-up notifications only.
     */
    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
        }
    }
}

// ── Диалог обновления приложения ────────────────────────────────────────────

@androidx.compose.runtime.Composable
private fun UpdateDialog(updateManager: UpdateManager) {
    // Наблюдаем единый источник правды — UpdateManager.updateAvailable.
    // Это позволяет триггерить диалог из любого места (например, из настроек),
    // вызвав updateManager.checkForUpdate() — флоу обновится автоматически.
    val updateInfo by updateManager.updateAvailable.collectAsState()
    var dismissed by remember { mutableStateOf(false) }

    // Состояние скачивания: null = idle, иначе (downloaded, total). total=-1 если неизвестно.
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val isDownloading = downloadJob?.isActive == true

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Сбрасываем dismissed когда приходит новый updateInfo (новая версия после dismiss)
    LaunchedEffect(updateInfo?.versionCode) {
        if (updateInfo != null) dismissed = false
    }

    // Фоновая проверка обновлений каждые 5 минут
    LaunchedEffect(Unit) {
        while (true) {
            updateManager.checkForUpdate()
            kotlinx.coroutines.delay(5 * 60 * 1000L)
        }
    }

    val info = updateInfo
    if (info == null || dismissed) return

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = {
            // Во время скачивания диалог нельзя закрыть тапом снаружи / системным «назад» —
            // только кнопкой «Отмена», которая ещё и job отменяет.
            if (!isDownloading) dismissed = true
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Шапка с градиентом и круглой аватаркой медведя
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.avatar_placeholder),
                                contentDescription = null,
                                modifier = Modifier.size(76.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (isDownloading) "Скачивание обновления" else "Доступно обновление",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Версия ${info.versionName ?: info.versionCode}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isDownloading) {
                    // ── Режим скачивания: прогресс-бар + размер ──────────────
                    val (downloaded, total) = progress ?: (0L to -1L)
                    val percent = if (total > 0) {
                        ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                    } else 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "$percent% — ${formatBytes(downloaded)} / ${formatBytes(total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // Сервер не вернул Content-Length — индикатор без процента
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Скачано ${formatBytes(downloaded)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (!info.changelog.isNullOrBlank()) {
                    // ── Режим просмотра: список изменений ────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.verticalScroll(scrollState)) {
                            Text(
                                text = info.changelog!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isDownloading) {
                        // Во время скачивания — единственная кнопка «Отмена» на всю ширину
                        OutlinedButton(
                            onClick = {
                                downloadJob?.cancel()
                                downloadJob = null
                                progress = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Отмена")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { dismissed = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Позже")
                        }
                        Button(
                            onClick = {
                                progress = 0L to -1L
                                downloadJob = scope.launch {
                                    runCatching {
                                        updateManager.downloadAndInstall(
                                            downloadUrl = info.downloadUrl!!,
                                            activityContext = context,
                                            onProgress = { downloaded, total ->
                                                progress = downloaded to total
                                            },
                                        )
                                    }
                                    // По завершении (успех или ошибка) — сбрасываем
                                    downloadJob = null
                                    progress = null
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Обновить")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

/** Форматирует байты в человекочитаемый размер: 12.3 МБ / 845 КБ. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return "%.1f МБ".format(mb)
    val kb = bytes / 1024.0
    return "%.0f КБ".format(kb)
}
