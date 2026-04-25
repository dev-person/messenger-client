package com.secure.messenger.presentation.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secure.messenger.utils.CriticalPermission
import com.secure.messenger.utils.rememberMissingCriticalPermissions
import com.secure.messenger.utils.rememberPermissionsPrefs

/**
 * Диалог-чек-лист недостающих разрешений. Показывается из MainActivity
 * на старте приложения — тот же контент доступен в SettingsScreen
 * через [PermissionsSection]. Каждый пункт ведёт сразу в системные
 * настройки этого разрешения.
 *
 * Кнопка «Не показывать» взводит флаг в [PermissionsPrefs], после чего
 * иконка настроек больше не пульсирует и диалог сам не выскакивает.
 * Сам блок в Settings продолжает быть доступен (юзер может вернуться).
 */
@Composable
fun PermissionsWarningDialog(
    missing: List<CriticalPermission>,
    onDismissForever: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Чтобы звонки доходили") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android требует разрешить кое-что вручную — иначе входящие" +
                        " звонки и сообщения могут не дойти до вас:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                missing.forEach { perm ->
                    PermissionRow(
                        permission = perm,
                        onClick = {
                            runCatching {
                                context.startActivity(perm.systemSettingsIntent(context))
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Ок") }
        },
        dismissButton = {
            TextButton(onClick = onDismissForever) { Text("Не напоминать") }
        },
    )
}

/**
 * Секция «Доступ для звонков» в SettingsScreen. Показывает только missing
 * разрешения; полный чек-лист (включая выданные и vendor-autostart) —
 * через ссылку «Полная диагностика», которая ведёт на DiagnosticsScreen.
 */
@Composable
fun PermissionsSection(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missing by rememberMissingCriticalPermissions()
    val context = LocalContext.current
    Surface(
        modifier = modifier,
        color = if (missing.isEmpty()) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (missing.isEmpty()) Icons.Default.Notifications else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (missing.isEmpty()) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (missing.isEmpty()) "Доступ для звонков"
                           else "Доступ для звонков · ${missing.size} проблем",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            missing.forEach { perm ->
                PermissionRow(
                    permission = perm,
                    onClick = {
                        runCatching {
                            context.startActivity(perm.systemSettingsIntent(context))
                        }
                    },
                )
            }
            // Кнопка «Полная диагностика» — открывает экран с чек-листом ВСЕХ
            // разрешений (включая выданные, vendor-autostart и сведения об
            // устройстве). Видна всегда, даже когда missing пустой — на случай
            // когда пользователь хочет проверить не сломалось ли что-то.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenDiagnostics)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Полная диагностика разрешений",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Возвращает true если иконку Настроек в нав-баре нужно подсвечивать.
 * Отдельный wrapper нужен потому, что флаг `dismissed` живёт в SharedPrefs
 * и его надо реактивно совмещать со списком missing-permissions.
 */
@Composable
fun shouldHighlightSettingsTab(): Boolean {
    val missing by rememberMissingCriticalPermissions()
    val prefs = rememberPermissionsPrefs()
    if (missing.isEmpty()) return false
    return !prefs.warningDismissed
}

@Composable
private fun PermissionRow(
    permission: CriticalPermission,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = permission.iconVector(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = permission.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = permission.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun CriticalPermission.iconVector(): ImageVector = when (this) {
    CriticalPermission.NOTIFICATIONS -> Icons.Default.Notifications
    CriticalPermission.FULL_SCREEN_INTENT -> Icons.AutoMirrored.Filled.PhoneCallback
    CriticalPermission.BATTERY_OPTIMIZATIONS -> Icons.Default.Battery6Bar
    CriticalPermission.VENDOR_AUTOSTART -> Icons.Default.Warning
}
