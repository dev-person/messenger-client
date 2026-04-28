package com.secure.messenger.presentation.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.presentation.viewmodel.GroupCallPickerViewModel

/**
 * Экран выбора участников перед групповым звонком. Открывается тапом на
 * иконку звонка / видео в шапке группового чата. По умолчанию выбраны все
 * (как в Telegram); юзер может снять галочки или нажать «Очистить» и
 * выбрать вручную. Кнопка снизу — «Позвонить» (audio/video, по тому что
 * пришло из шапки чата).
 *
 * Идентификация по группе берётся из nav-аргумента chatId через
 * SavedStateHandle в [GroupCallPickerViewModel].
 */
@Composable
fun GroupCallParticipantPickerScreen(
    chatId: String,
    isVideo: Boolean,
    onBack: () -> Unit,
    onConfirm: (selectedIds: List<String>) -> Unit,
    viewModel: GroupCallPickerViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    val q = ui.searchQuery.trim().lowercase()
    val filtered = remember(ui.members, q) {
        if (q.isEmpty()) ui.members else ui.members.filter {
            it.displayName.lowercase().contains(q) ||
                it.username.lowercase().contains(q) ||
                it.phone.lowercase().contains(q)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Шапка ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isVideo) "Видеозвонок" else "Звонок",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val count = ui.selectedIds.size
                    val total = ui.members.size
                    if (total > 0) {
                        Text(
                            text = "$count из $total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Toggle: всех выбрать / снять выделение. Меньше моторики чем
                // тыкать каждый чекбокс по очереди.
                if (ui.members.isNotEmpty()) {
                    val allSelected = ui.selectedIds.size == ui.members.size
                    TextButton(
                        onClick = {
                            if (allSelected) viewModel.clearSelection() else viewModel.selectAll()
                        },
                    ) {
                        Text(if (allSelected) "Очистить" else "Все")
                    }
                }
            }

            // ── Поиск ──────────────────────────────────────────────────────
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = viewModel::onSearchChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Поиск по группе") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(4.dp))

            // ── Список ─────────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    ui.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    ui.error != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ui.error ?: "",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (q.isNotEmpty()) "Ничего не найдено"
                                else "В группе нет других участников",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(filtered, key = { it.id }) { user ->
                                val selected = user.id in ui.selectedIds
                                MemberRow(
                                    displayName = user.displayName,
                                    subtitle = user.username.ifEmpty { user.phone },
                                    avatarUrl = user.avatarUrl,
                                    selected = selected,
                                    onClick = { viewModel.toggle(user.id) },
                                )
                            }
                        }
                    }
                }
            }

            // ── CTA ────────────────────────────────────────────────────────
            val canCall = ui.selectedIds.isNotEmpty() && !ui.isLoading
            Button(
                onClick = { onConfirm(ui.selectedIds.toList()) },
                enabled = canCall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
                    .height(52.dp),
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isVideo) "Видеозвонок" else "Позвонить",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MemberRow(
    displayName: String,
    subtitle: String,
    avatarUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.secure.messenger.presentation.ui.chat.AvatarImage(
            url = avatarUrl,
            name = displayName,
            size = 40,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
        )
    }
}
