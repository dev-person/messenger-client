package com.secure.messenger.presentation.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.secure.messenger.presentation.ui.components.CompactTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.BuildConfig

// URL политики конфиденциальности — замените на реальный
private const val PRIVACY_POLICY_URL = "https://example.com/privacy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CompactTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                title = {
                    Text(
                        "Настройки",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        modifier = Modifier.weight(1f),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Раздел: Уведомления ───────────────────────────────────────────
            SectionHeader("Уведомления")

            ListItem(
                headlineContent = { Text("Уведомления") },
                supportingContent = { Text(if (state.notificationsEnabled) "Включены" else "Выключены") },
                leadingContent = {
                    Icon(
                        if (state.notificationsEnabled) Icons.Default.Notifications
                        else Icons.Default.NotificationsOff,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.notificationsEnabled,
                        onCheckedChange = { viewModel.toggleNotifications() },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Звук") },
                supportingContent = { Text(if (state.soundEnabled) "Включён" else "Выключен") },
                leadingContent = {
                    Icon(
                        Icons.Default.Notifications, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.soundEnabled,
                        onCheckedChange = { viewModel.toggleSound() },
                        // Блокируем если уведомления выключены
                        enabled = state.notificationsEnabled,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Вибрация") },
                supportingContent = { Text(if (state.vibrationEnabled) "Включена" else "Выключена") },
                leadingContent = {
                    Icon(
                        if (state.vibrationEnabled) Icons.Default.PhoneAndroid
                        else Icons.Default.PhoneAndroid,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.vibrationEnabled,
                        onCheckedChange = { viewModel.toggleVibration() },
                        enabled = state.notificationsEnabled,
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Раздел: Приложение ────────────────────────────────────────────
            SectionHeader("Приложение")

            ListItem(
                headlineContent = { Text("Версия приложения") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
                leadingContent = {
                    Icon(
                        Icons.Default.SystemUpdate, null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Раздел: Прочее ────────────────────────────────────────────────
            SectionHeader("Прочее")

            // Политика конфиденциальности — тап открывает браузер
            ListItem(
                headlineContent = { Text("Политика конфиденциальности") },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew, null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    )
                },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Заголовок раздела настроек ────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
