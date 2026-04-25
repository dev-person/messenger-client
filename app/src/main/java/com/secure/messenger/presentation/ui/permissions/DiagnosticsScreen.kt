package com.secure.messenger.presentation.ui.permissions

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secure.messenger.utils.CriticalPermission
import com.secure.messenger.utils.PermissionGrantStatus
import com.secure.messenger.utils.VendorAutostartIntents
import com.secure.messenger.utils.rememberMissingCriticalPermissions
import com.secure.messenger.utils.statusOf

/**
 * Полный чек-лист разрешений, важных для входящих звонков и сообщений.
 * Каждое разрешение показано со статусом (✓ выдано / ✗ отсутствует /
 * ⚠ нужно проверить вручную) и кнопкой перехода в системные настройки.
 *
 * Открывается из SettingsScreen → пункт «Диагностика разрешений».
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val titleFontSize = when {
        configuration.screenWidthDp <= 360 -> 22.sp
        configuration.screenWidthDp <= 420 -> 26.sp
        else                               -> 30.sp
    }

    // Реактивно перерисовываемся когда юзер вернулся из системных настроек.
    val missing by rememberMissingCriticalPermissions()
    // Используем missing как ключ — статусы пересчитываются именно когда он
    // меняется (то есть на ON_RESUME через rememberMissingCriticalPermissions).
    val statuses = remember(missing) {
        CriticalPermission.values().map { perm -> perm to statusOf(context, perm) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ── Шапка в стиле Профиля ────────────────────────────────────────
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 16.dp, top = 0.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = "Диагностика разрешений",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = titleFontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Чтобы входящие звонки и сообщения доходили до вас даже когда экран спит, " +
                    "Android и производитель устройства требуют несколько разрешений. " +
                    "Здесь видно, что выдано, а что — нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            statuses.forEach { (perm, status) ->
                if (status == PermissionGrantStatus.NOT_APPLICABLE) return@forEach
                DiagnosticRow(
                    permission = perm,
                    status = status,
                    onClick = {
                        runCatching { context.startActivity(perm.systemSettingsIntent(context)) }
                    },
                )
            }

            // Информационный блок: какое устройство, какие версии. Помогает
            // при разборе багов: пользователь может прочитать и сообщить.
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "Устройство",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(4.dp))
                    val vendor = VendorAutostartIntents.manufacturerLabel() ?: "стандартный Android"
                    Text(
                        text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $vendor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    permission: CriticalPermission,
    status: PermissionGrantStatus,
    onClick: () -> Unit,
) {
    val (statusIcon, statusTint) = when (status) {
        PermissionGrantStatus.GRANTED -> Icons.Default.Check to Color(0xFF2E7D32)
        PermissionGrantStatus.DENIED -> Icons.Default.Warning to MaterialTheme.colorScheme.error
        PermissionGrantStatus.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline to Color(0xFFE65100)
        PermissionGrantStatus.NOT_APPLICABLE -> Icons.Default.Check to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(statusTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusTint,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when (status) {
                        PermissionGrantStatus.GRANTED -> "Выдано"
                        PermissionGrantStatus.DENIED -> "Не выдано · откройте настройки"
                        PermissionGrantStatus.UNKNOWN -> "Проверьте вручную · откройте настройки"
                        PermissionGrantStatus.NOT_APPLICABLE -> "Не требуется"
                    },
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
}
