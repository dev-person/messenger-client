package com.secure.messenger.presentation.ui.main

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.secure.messenger.presentation.ui.chat.ChatListScreen
import com.secure.messenger.presentation.ui.contacts.ContactsScreen
import com.secure.messenger.presentation.ui.profile.ProfileEditScreen
import com.secure.messenger.presentation.ui.settings.SettingsScreen

// ── Главный экран с нижней навигацией ─────────────────────────────────────────

@Composable
fun HomeScreen(
    onChatClick: (chatId: String) -> Unit,
    onLogout: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    // Индекс активной вкладки — сохраняется при повороте экрана
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

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

    Scaffold(
        // Отключаем автоматическое применение window insets в HomeScreen —
        // каждый вложенный экран сам управляет своими отступами (статусбар, навбар).
        // Без этого CompactTopBar получает двойной отступ статусбара.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
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
                )
                3 -> SettingsScreen(onBack = { selectedTab = 0 })
            }
        }
    }
}
