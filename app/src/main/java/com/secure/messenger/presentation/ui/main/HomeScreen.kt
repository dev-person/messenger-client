package com.secure.messenger.presentation.ui.main

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.secure.messenger.presentation.ui.chat.ChatListScreen
import com.secure.messenger.presentation.ui.contacts.ContactsScreen
import com.secure.messenger.presentation.ui.profile.ProfileEditScreen
import com.secure.messenger.presentation.ui.settings.SettingsScreen

// ── Главный экран с нижней навигацией ─────────────────────────────────────────

/**
 * Запрос на показ круглого crop-оверлея над всем HomeScreen-ом (включая нижнюю
 * навигацию). Поднимается «снизу» из вложенных экранов через колбэк, а
 * рендерится здесь — поверх Scaffold-а, чтобы фон полностью покрывал bottomBar.
 */
private data class CropRequest(
    val bitmap: android.graphics.Bitmap,
    val onConfirmCrop: (android.graphics.Bitmap) -> Unit,
)

@Composable
fun HomeScreen(
    onChatClick: (chatId: String) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onLogout: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    // Индекс активной вкладки — сохраняется при повороте экрана
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    // Активный crop-запрос. null = крепер скрыт. ProfileEditScreen вызывает
    // setCropRequest() после выбора фото, мы рисуем оверлей в самом верху Box-а.
    var cropRequest by remember { mutableStateOf<CropRequest?>(null) }

    // Кнопка «Назад»: с любой вкладки → на Чаты, с Чатов → подтверждение выхода
    BackHandler {
        if (selectedTab != 0) {
            selectedTab = 0
        } else {
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Выход") },
            text = { Text("Вы действительно хотите выйти из приложения?") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) {
                    Text("Выйти")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    // Корневой Box: внутри Scaffold + сверху crop-оверлей. Box нужен чтобы
    // оверлей мог накрыть И контент И bottom-bar Scaffold-а — сам Scaffold
    // «забирает» bottomBar-ом часть экрана и не даёт его покрыть изнутри.
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        // Отключаем автоматическое применение window insets в HomeScreen —
        // каждый вложенный экран сам управляет своими отступами (статусбар, навбар).
        // Без этого CompactTopBar получает двойной отступ статусбара.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                // Явно задаём surface из выбранной схемы — по умолчанию Material3
                // берёт surfaceContainer, который не определён во всех наших темах
                // и поэтому цвет nav-бара не менялся при смене цветовой схемы.
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Чаты") },
                    label = { Text("Чаты") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.People, contentDescription = "Контакты") },
                    label = { Text("Контакты") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = "Профиль") },
                    label = { Text("Профиль") },
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        SettingsTabIcon(
                            highlight = com.secure.messenger.presentation.ui.permissions
                                .shouldHighlightSettingsTab(),
                        )
                    },
                    label = { Text("Настройки") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                0 -> ChatListScreen(
                    onChatClick = onChatClick,
                    onNewChatClick = { selectedTab = 1 },
                    onCreateGroupClick = onCreateGroupClick,
                    onProfileClick = { selectedTab = 2 },
                )
                1 -> ContactsScreen(
                    onBack = { selectedTab = 0 },
                    onStartChat = onChatClick,
                )
                2 -> ProfileEditScreen(
                    showBackButton = true,
                    onBack = { selectedTab = 0 },
                    onLogout = onLogout,
                    onRequestCrop = { bmp, onResult ->
                        cropRequest = CropRequest(bmp, onResult)
                    },
                )
                3 -> SettingsScreen(
                    onBack = { selectedTab = 0 },
                    onOpenDiagnostics = onDiagnosticsClick,
                )
            }
        }
    }

        // Crop-оверлей рендерится ПОВЕРХ Scaffold-а, поэтому покрывает и
        // контент, и bottomBar — а cropper внутри сам сделает fillMaxSize().
        val activeCropRequest = cropRequest
        if (activeCropRequest != null) {
            com.secure.messenger.presentation.ui.components.AvatarCropDialog(
                sourceBitmap = activeCropRequest.bitmap,
                onCancel = { cropRequest = null },
                onConfirm = { cropped ->
                    activeCropRequest.onConfirmCrop(cropped)
                    cropRequest = null
                },
            )
        }

        // Диалог-онбординг по критичным разрешениям. Показываем один раз
        // после login, если пользователь не нажал «не напоминать». Пункты —
        // прямые ссылки в системные настройки этих разрешений.
        val missingPerms by com.secure.messenger.utils.rememberMissingCriticalPermissions()
        val permsPrefs = com.secure.messenger.utils.rememberPermissionsPrefs()
        var dialogShown by rememberSaveable { mutableStateOf(false) }
        if (missingPerms.isNotEmpty() && !permsPrefs.warningDismissed && !dialogShown) {
            com.secure.messenger.presentation.ui.permissions.PermissionsWarningDialog(
                missing = missingPerms,
                onDismiss = { dialogShown = true },
                onDismissForever = {
                    permsPrefs.warningDismissed = true
                    dialogShown = true
                },
            )
        }
    }
}

/**
 * Иконка вкладки «Настройки» с алерт-индикатором, если у пользователя
 * не выданы критичные разрешения и он не отказался от напоминания.
 * Индикатор — красная пульсирующая точка в правом верхнем углу.
 */
@Composable
private fun SettingsTabIcon(highlight: Boolean) {
    if (!highlight) {
        Icon(Icons.Default.Settings, contentDescription = "Настройки")
        return
    }
    val infinite = rememberInfiniteTransition(label = "perm-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box {
        Icon(Icons.Default.Settings, contentDescription = "Настройки")
        Box(
            modifier = Modifier
                .size(10.dp)
                .offset(x = 12.dp, y = (-2).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = pulse)),
        )
    }
}
