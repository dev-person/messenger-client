package com.secure.messenger.presentation.ui.contacts

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.presentation.ui.chat.AvatarImage
import com.secure.messenger.presentation.viewmodel.ContactsViewModel

// ── Экран контактов (OneUI стиль) ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onStartChat: (chatId: String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val contactsPermission = rememberPermissionState(android.Manifest.permission.READ_CONTACTS)

    // Автоматически запрашиваем разрешение на контакты при открытии экрана
    LaunchedEffect(Unit) {
        if (!contactsPermission.status.isGranted) {
            contactsPermission.launchPermissionRequest()
        }
    }

    // После получения разрешения — синхронизируем контакты
    LaunchedEffect(contactsPermission.status.isGranted) {
        if (contactsPermission.status.isGranted) {
            viewModel.syncContacts()
        }
    }

    LaunchedEffect(uiState.openChatId) {
        uiState.openChatId?.let { chatId ->
            viewModel.clearOpenChat()
            onStartChat(chatId)
        }
    }

    val displayList = if (searchQuery.length >= 2) {
        uiState.searchResults.map { user ->
            Contact(
                id = user.id, userId = user.id,
                displayName = user.displayName, phone = user.phone,
                avatarUrl = user.avatarUrl, isRegistered = true,
            )
        }
    } else {
        // Зарегистрированные контакты — первыми
        contacts.sortedByDescending { it.isRegistered }
    }

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
                    text = "Контакты",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Строка поиска ─────────────────────────────────────────────
            SearchBar(
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    viewModel.onSearchQueryChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Анимированный прогресс-бар ────────────────────────────────
            AnimatedVisibility(
                visible = uiState.isSyncing,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }

            // ── Pull-to-refresh + список контактов ────────────────────────
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = uiState.isSyncing,
                onRefresh = {
                    if (contactsPermission.status.isGranted) viewModel.syncContacts()
                    else contactsPermission.launchPermissionRequest()
                },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (displayList.isEmpty() && !uiState.isSyncing) {
                    EmptyContacts(isSearching = searchQuery.length >= 2)
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(displayList, key = { it.id }) { contact ->
                            ContactRow(
                                contact = contact,
                                onOpenChat = { userId -> viewModel.openDirectChat(userId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Поле поиска (OneUI стиль) ────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Поиск по имени или @username",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Очистить поиск",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// ── Строка контакта (в карточке) ─────────────────────────────────────────────

@Composable
private fun ContactRow(
    contact: Contact,
    onOpenChat: (chatId: String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = contact.isRegistered) {
                    contact.userId?.let { onOpenChat(it) }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(url = contact.avatarUrl, name = contact.displayName, size = 48)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = contact.phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            if (contact.isRegistered && contact.userId != null) {
                IconButton(onClick = { onOpenChat(contact.userId!!) }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Написать",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ── Пустое состояние ──────────────────────────────────────────────────────────

@Composable
private fun EmptyContacts(isSearching: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isSearching) "Никого не найдено" else "Список контактов пуст",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isSearching)
                "Попробуйте другое имя или @username"
            else
                "Потяните вниз, чтобы синхронизировать\nконтакты из записной книжки",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
