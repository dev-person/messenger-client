package com.secure.messenger.presentation.ui.main

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
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
import com.secure.messenger.service.MessagingService
import com.secure.messenger.utils.UpdateManager
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
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

    // Launcher for POST_NOTIFICATIONS permission (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result — nothing to do, notifications will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        setContent {
            val selectedScheme by ThemePreferences.colorScheme.collectAsState()
            SecureMessengerTheme(colorScheme = selectedScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController, startDestination = startDestination)

                    // Диалог обновления приложения
                    UpdateDialog(updateManager = updateManager)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MessengerApp.isInForeground = true
        // Приложение на переднем плане — сообщаем серверу что мы в сети
        if (authTokenProvider.hasToken()) {
            signalingClient.isAppForeground = true
            signalingClient.sendPresence(true)
            // Сообщаем серверу актуальную версию APK — нужно для админ-панели,
            // чтобы видеть распределение версий по пользователям
            registerAppVersion()
            // Re-sync чатов: подтягиваем актуальные имена/аватарки/онлайн-статусы
            // партнёров. Нужно как fallback если пока приложение было свёрнуто
            // юзер B обновил профиль и WS-event user_updated не дошёл (или WS
            // был отключён). Без этого аватарка партнёра в списке оставалась
            // старой до перезапуска приложения.
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

    override fun onStop() {
        super.onStop()
        MessengerApp.isInForeground = false
        // Приложение свёрнуто — уходим в офлайн (WebSocket остаётся для сообщений/звонков)
        if (authTokenProvider.hasToken()) {
            signalingClient.isAppForeground = false
            signalingClient.sendPresence(false)
        }
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
