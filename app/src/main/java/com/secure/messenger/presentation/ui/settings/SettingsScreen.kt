package com.secure.messenger.presentation.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.secure.messenger.data.remote.api.SessionDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.BuildConfig
import com.secure.messenger.presentation.theme.AppColorScheme
import com.secure.messenger.presentation.theme.previewColor
import kotlinx.coroutines.launch

// URL политики конфиденциальности — замените на реальный
private const val PRIVACY_POLICY_URL = "https://example.com/privacy"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // ── OneUI-стиль: большой заголовок ─────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 20.dp),
            ) {
                Text(
                    text = "Настройки",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Уведомления ────────────────────────────────────────────────
            OneUiSectionLabel("Уведомления")

            OneUiCard {
                OneUiToggleItem(
                    icon = if (state.notificationsEnabled) Icons.Default.Notifications
                    else Icons.Default.NotificationsOff,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Уведомления",
                    subtitle = if (state.notificationsEnabled) "Включены" else "Выключены",
                    checked = state.notificationsEnabled,
                    onCheckedChange = { viewModel.toggleNotifications() },
                )

                OneUiDivider()

                OneUiToggleItem(
                    icon = Icons.Default.VolumeUp,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Звук",
                    subtitle = if (state.soundEnabled) "Включён" else "Выключен",
                    checked = state.soundEnabled,
                    onCheckedChange = { viewModel.toggleSound() },
                    enabled = state.notificationsEnabled,
                )

                OneUiDivider()

                OneUiToggleItem(
                    icon = Icons.Default.Vibration,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Вибрация",
                    subtitle = if (state.vibrationEnabled) "Включена" else "Выключена",
                    checked = state.vibrationEnabled,
                    onCheckedChange = { viewModel.toggleVibration() },
                    enabled = state.notificationsEnabled,
                )
            }

            // ── Оформление ──────────────────────────────────────────────────
            OneUiSectionLabel("Оформление")

            OneUiCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Цветовая схема",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AppColorScheme.entries.forEach { scheme ->
                        val isSelected = scheme == state.colorScheme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setColorScheme(scheme) }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(scheme.previewColor())
                                    .then(
                                        if (isSelected) Modifier.border(
                                            2.5.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        ) else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = scheme.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // ── Безопасность ──────────────────────────────────────────────
            OneUiSectionLabel("Безопасность")

            OneUiCard {
                if (state.messagesLocked) {
                    OneUiClickItem(
                        icon = Icons.Default.LockOpen,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = "Разблокировать сообщения",
                        subtitle = "Введите пароль для расшифровки переписки",
                        onClick = { viewModel.showUnlockDialog() },
                    )
                } else {
                    OneUiClickItem(
                        icon = Icons.Default.Lock,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Пароль шифрования",
                        subtitle = "Установка или смена пароля",
                        onClick = { viewModel.showChangePasswordDialog() },
                    )
                }

                OneUiDivider()

                OneUiClickItem(
                    icon = Icons.Default.Devices,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Активные сессии",
                    subtitle = "Управление входами на устройствах",
                    onClick = { viewModel.showSessions() },
                )
            }

            if (state.showSessionsDialog) {
                SessionsDialog(
                    sessions = state.sessions,
                    isLoading = state.sessionsLoading,
                    onTerminate = viewModel::terminateSession,
                    onTerminateAll = viewModel::terminateAllOtherSessions,
                    onDismiss = viewModel::dismissSessions,
                )
            }

            if (state.showUnlockDialog) {
                UnlockMessagesDialog(
                    password = state.unlockPassword,
                    error = state.unlockError,
                    isLoading = state.unlockLoading,
                    onPasswordChange = viewModel::onUnlockPasswordChange,
                    onSubmit = viewModel::unlockMessages,
                    onDeleteKey = viewModel::deleteKeyFromSettings,
                    onDismiss = viewModel::dismissUnlockDialog,
                )
            }

            if (state.showDeleteKeyOtp) {
                DeleteKeyOtpSettingsDialog(
                    otp = state.deleteKeyOtpCode,
                    error = state.unlockError,
                    isLoading = state.unlockLoading,
                    onOtpChange = viewModel::onDeleteKeyOtpChange,
                    onConfirm = viewModel::confirmDeleteKeyFromSettings,
                    onDismiss = viewModel::cancelDeleteKeyOtp,
                )
            }

            if (state.showPasswordDialog) {
                ChangePasswordDialog(
                    hasPassword = state.hasExistingPassword,
                    oldPassword = state.oldPassword,
                    newPassword = state.newPassword,
                    confirmPassword = state.confirmPassword,
                    error = state.passwordError,
                    isLoading = state.passwordLoading,
                    onOldPasswordChange = viewModel::onOldPasswordChange,
                    onNewPasswordChange = viewModel::onNewPasswordChange,
                    onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
                    onSubmit = viewModel::changePassword,
                    onDismiss = viewModel::dismissChangePasswordDialog,
                )
            }

            // ── Обновление ─────────────────────────────────────────────────
            OneUiSectionLabel("Обновление")

            UpdateCard(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                onCheck = { scope.launch { viewModel.checkForUpdate(context) } },
            )

            // ── О приложении ───────────────────────────────────────────────
            OneUiSectionLabel("О приложении")

            OneUiCard {
                OneUiClickItem(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = "Политика конфиденциальности",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                        )
                    },
                )

                OneUiDivider()

                OneUiInfoItem(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.outline,
                    title = "Версия",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// OneUI-компоненты
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun OneUiSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun OneUiCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Большая красивая плашка проверки обновлений с круглой аватаркой медведя
 * (логотип приложения) и градиентным фоном на левой части.
 */
@Composable
private fun UpdateCard(
    versionName: String,
    versionCode: Int,
    onCheck: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheck),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Круглая аватарка медведя на градиентном фоне
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        com.secure.messenger.R.drawable.avatar_placeholder
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Grizzly Messenger",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Версия $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Тап для проверки обновлений",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }

            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun OneUiDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

@Composable
private fun OneUiToggleItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    fontSize = 13.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun OneUiClickItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun OneUiInfoItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Диалог активных сессий ─────────────────────────────────────────────────────

@Composable
private fun SessionsDialog(
    sessions: List<SessionDto>,
    isLoading: Boolean,
    onTerminate: (String) -> Unit,
    onTerminateAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withZone(ZoneId.systemDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Активные сессии") },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (sessions.isEmpty()) {
                Text("Нет активных сессий", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions) { session ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (session.isCurrent)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.deviceName.ifEmpty { "Неизвестное устройство" } +
                                                if (session.isCurrent) " (текущая)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    val locationText = if (session.location.isNotEmpty())
                                        session.location else session.ip
                                    Text(
                                        text = locationText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val lastSeen = runCatching {
                                        fmt.format(Instant.parse(session.lastSeenAt))
                                    }.getOrDefault(session.lastSeenAt)
                                    Text(
                                        text = "Активность: $lastSeen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!session.isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.PhonelinkErase,
                                        contentDescription = "Завершить",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { onTerminate(session.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (sessions.size > 1) {
                TextButton(onClick = onTerminateAll) {
                    Text("Завершить все другие", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

// ── OTP-подтверждение удаления ключа (настройки) ──────────────────────────────

@Composable
private fun DeleteKeyOtpSettingsDialog(
    otp: String,
    error: String?,
    isLoading: Boolean,
    onOtpChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтверждение удаления") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "На ваш номер отправлен код. Введите его для подтверждения удаления ключа.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) onOtpChange(it) },
                    label = { Text("Код из SMS") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = otp.length == 6 && !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Удалить ключ", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

// ── Диалог разблокировки сообщений ─────────────────────────────────────────────

@Composable
private fun UnlockMessagesDialog(
    password: String,
    error: String?,
    isLoading: Boolean,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDeleteKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    var passwordVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showDeleteWarning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разблокировать сообщения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Введите пароль шифрования для восстановления доступа к переписке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible)
                                    Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                TextButton(
                    onClick = { showDeleteWarning = true },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(
                        "Забыли пароль? Удалить ключ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isLoading && password.isNotEmpty()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Разблокировать")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )

    if (showDeleteWarning) {
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text("Удалить ключ шифрования?") },
            text = {
                Text("Все ранее зашифрованные сообщения будут потеряны безвозвратно. " +
                     "Вы сможете задать новый пароль.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteWarning = false
                    onDeleteKey()
                }) {
                    Text("Удалить безвозвратно", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWarning = false }) { Text("Отмена") }
            },
        )
    }
}

// ── Диалог смены пароля ───────────────────────────────────────────────────────

@Composable
private fun ChangePasswordDialog(
    hasPassword: Boolean,
    oldPassword: String,
    newPassword: String,
    confirmPassword: String,
    error: String?,
    isLoading: Boolean,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) "Смена пароля" else "Установка пароля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPassword) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = onOldPasswordChange,
                        label = { Text("Текущий пароль") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = onNewPasswordChange,
                    label = { Text(if (hasPassword) "Новый пароль" else "Пароль") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    label = { Text("Повторите пароль") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (hasPassword) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Все другие устройства будут разлогинены. " +
                                   "Ранее зашифрованные сообщения станут недоступны без нового пароля.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (hasPassword) "Сменить пароль" else "Установить",
                        color = if (hasPassword) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
