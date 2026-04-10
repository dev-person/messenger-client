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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import dagger.hilt.android.AndroidEntryPoint
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
        }
    }

    /**
     * Шлёт текущую версию APK на сервер. Не блокирует запуск, ошибки игнорируются.
     */
    private fun registerAppVersion() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            runCatching {
                api.registerAppVersion(mapOf(
                    "versionCode" to com.secure.messenger.BuildConfig.VERSION_CODE,
                    "versionName" to com.secure.messenger.BuildConfig.VERSION_NAME,
                ))
            }
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
    var updateInfo by remember { mutableStateOf<UpdateInfoDto?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Проверяем обновление при запуске и каждые 5 минут
    LaunchedEffect(Unit) {
        while (true) {
            val info = updateManager.checkForUpdate()
            if (info != null) {
                updateInfo = info
                dismissed = false // Показать снова если появилось новое обновление
            }
            kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 минут
        }
    }

    if (updateInfo != null && !dismissed) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Доступно обновление") },
            text = {
                Text(
                    buildString {
                        append("Версия ${info.versionName ?: info.versionCode}")
                        if (!info.changelog.isNullOrBlank()) {
                            append("\n\n${info.changelog}")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!isDownloading) {
                            isDownloading = true
                            scope.launch {
                                runCatching {
                                    updateManager.downloadAndInstall(info.downloadUrl!!, context)
                                }
                                isDownloading = false
                            }
                        }
                    },
                    enabled = !isDownloading,
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Обновить")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) {
                    Text("Позже")
                }
            },
        )
    }
}
