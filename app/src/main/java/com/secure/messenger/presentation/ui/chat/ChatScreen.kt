package com.secure.messenger.presentation.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.secure.messenger.BuildConfig
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.model.User
import com.secure.messenger.presentation.theme.LocalMessengerColors
import com.secure.messenger.presentation.viewmodel.ChatViewModel
import com.secure.messenger.utils.formatDateSeparator
import com.secure.messenger.utils.formatMessageTime
import com.secure.messenger.utils.isSameDay

// ── Экран чата ────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onCallClick: (userId: String, isVideo: Boolean, peerName: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatInfo by viewModel.chatInfo.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val isOtherOnline by viewModel.isOtherUserOnline.collectAsStateWithLifecycle()
    val otherUser by viewModel.otherUser.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showProfileUser by remember { mutableStateOf<User?>(null) }

    // Диалог профиля пользователя
    showProfileUser?.let { user ->
        UserProfileDialog(
            user = user,
            isOnline = isOtherOnline,
            onDismiss = { showProfileUser = null },
        )
    }

    // Прокрутка к последнему сообщению при первом открытии чата
    LaunchedEffect(Unit) {
        snapshotFlow { messages.size }
            .filter { it > 0 }
            .first()
        listState.scrollToItem(messages.size - 1)
    }

    // Авто-скролл при новом сообщении — только если уже у конца списка
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= messages.size - 3) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Подгрузка старых сообщений при скролле вверх
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .filter { it == 0 }
            .collect { viewModel.loadOlderMessages() }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── OneUI-стиль: шапка чата ────────────────────────────────────
            ChatTopBar(
                chatInfo = chatInfo,
                currentUserId = currentUserId,
                isOtherOnline = isOtherOnline,
                isTyping = isTyping,
                onBack = onBack,
                onCallClick = onCallClick,
                onAvatarClick = { otherUser?.let { showProfileUser = it } },
            )

            // ── Список сообщений с обоями ────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ChatWallpaper()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    // Индикатор загрузки старых сообщений
                    if (uiState.isLoadingOlder) {
                        item("loading_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                        val prevMessage = messages.getOrNull(index - 1)

                        if (prevMessage == null || !isSameDay(prevMessage.timestamp, message.timestamp)) {
                            DateSeparator(timestamp = message.timestamp)
                        }

                        if (message.type == MessageType.SYSTEM) {
                            SystemMessage(text = message.content, timestamp = message.timestamp)
                        } else {
                            val isOutgoing = message.senderId == currentUserId
                            MessageBubble(
                            message = message,
                            isOutgoing = isOutgoing,
                            onDelete = if (isOutgoing) { { viewModel.deleteMessage(message) } } else null,
                            onEdit   = if (isOutgoing) { { viewModel.startEditing(message) } } else null,
                        )
                    }
                }
                }
            }

            // ── Поле ввода ─────────────────────────────────────────────────
            MessageInputBar(
                text = inputText,
                editingMessage = uiState.editingMessage,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onCancelEdit = viewModel::cancelEditing,
                onEnhance = viewModel::enhanceText,
            )
        }

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ── Шапка чата (стиль как у экрана Чаты) ─────────────────────────────────────

@Composable
private fun ChatTopBar(
    chatInfo: Chat?,
    currentUserId: String?,
    isOtherOnline: Boolean,
    isTyping: Boolean,
    onBack: () -> Unit,
    onCallClick: (userId: String, isVideo: Boolean, peerName: String) -> Unit,
    onAvatarClick: () -> Unit = {},
) {
    val peerId   = chatInfo?.otherUserId ?: ""
    val peerName = chatInfo?.title ?: ""
    val isDirectChat = chatInfo == null || chatInfo.type != ChatType.GROUP

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Аватар с индикатором онлайн (клик — профиль)
            Box(
                modifier = if (isDirectChat) Modifier.clickable(onClick = onAvatarClick)
                else Modifier,
            ) {
                AvatarImage(url = chatInfo?.avatarUrl, name = chatInfo?.title ?: "?", size = 46)
                if (isDirectChat && isOtherOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                            .align(Alignment.BottomEnd),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chatInfo?.title ?: "Чат",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Статус: печатает / в сети / не в сети / кол-во участников
                val subtitle = when {
                    isDirectChat && isTyping -> "печатает..."
                    chatInfo?.type == ChatType.GROUP -> "${chatInfo.members.size} участников"
                    isDirectChat && isOtherOnline -> "в сети"
                    isDirectChat -> "не в сети"
                    else -> ""
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isTyping -> MaterialTheme.colorScheme.primary
                            isOtherOnline -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                    )
                }
            }

            if (isDirectChat) {
                IconButton(
                    onClick = { if (peerId.isNotEmpty()) onCallClick(peerId, false, peerName) },
                    enabled = peerId.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Call, "Аудиозвонок",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = { if (peerId.isNotEmpty()) onCallClick(peerId, true, peerName) },
                    enabled = peerId.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Videocam, "Видеозвонок",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ── Разделитель дат между группами сообщений ─────────────────────────────────

@Composable
private fun DateSeparator(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatDateSeparator(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ── Системное сообщение (звонки, события) ────────────────────────────────────

@Composable
private fun SystemMessage(text: String, timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatMessageTime(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
    }
}

// ── Пузырь сообщения ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    onDelete: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    val extra = LocalMessengerColors.current
    var showMenu by remember { mutableStateOf(false) }

    val bubbleColor = if (isOutgoing) extra.outgoingBubble else extra.incomingBubble

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = if (isDark) Color.White else Color.Black
    val metaColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Gray

    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (onDelete != null || onEdit != null) showMenu = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .animateContentSize(),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (message.isEdited) {
                        Text(
                            text = "ред.",
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                            fontSize = 10.sp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        fontSize = 11.sp,
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status, metaColor = metaColor)
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text("Редактировать") },
                        onClick = { showMenu = false; onEdit() },
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        }
    }
}

// ── Иконка статуса сообщения ─────────────────────────────────────────────────

@Composable
private fun MessageStatusIcon(status: MessageStatus, metaColor: Color) {
    val iconSize = Modifier.size(14.dp)
    when (status) {
        MessageStatus.SENDING   -> Icon(Icons.Default.Schedule, null, tint = metaColor, modifier = iconSize)
        MessageStatus.SENT      -> Icon(Icons.Default.Done,    null, tint = metaColor, modifier = iconSize)
        MessageStatus.DELIVERED -> Icon(Icons.Default.DoneAll, null, tint = metaColor, modifier = iconSize)
        MessageStatus.READ      -> Icon(Icons.Default.DoneAll, null, tint = Color(0xFF4FC3F7), modifier = iconSize)
        MessageStatus.FAILED    -> Icon(Icons.Default.Done,    null, tint = MaterialTheme.colorScheme.error, modifier = iconSize)
    }
}

// ── Поле ввода сообщения ──────────────────────────────────────────────────────

@Composable
private fun MessageInputBar(
    text: String,
    editingMessage: Message?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onEnhance: () -> Unit = {},
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            if (editingMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = editingMessage.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Отмена",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Сообщение…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                    )
                }

                // Кнопка улучшения текста (ИИ)
                if (text.length >= 3) {
                    IconButton(
                        onClick = onEnhance,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "Улучшить текст",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }

                IconButton(
                    onClick = onSend,
                    enabled = text.isNotEmpty(),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (text.isNotEmpty()) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Диалог профиля пользователя ──────────────────────────────────────────────

@Composable
private fun UserProfileDialog(
    user: User,
    isOnline: Boolean,
    onDismiss: () -> Unit,
) {
    val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')
    val resolvedAvatarUrl = user.avatarUrl?.let { url ->
        when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$serverRoot$url"
            else -> url
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Аватар-карточка (как в ProfileEditScreen) ────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                ) {
                    if (resolvedAvatarUrl != null) {
                        AsyncImage(
                            model = resolvedAvatarUrl,
                            contentDescription = user.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Person, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(72.dp),
                            )
                        }
                    }

                    // Градиент снизу
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.65f),
                                )
                            ),
                    )

                    // Имя и статус внизу
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = if (isOnline) "в сети" else "не в сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOnline) Color(0xFF81C784) else Color.White.copy(alpha = 0.7f),
                        )
                    }

                    // Индикатор онлайн
                    if (isOnline) {
                        Box(
                            modifier = Modifier
                                .padding(16.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                                .align(Alignment.TopEnd),
                        )
                    }
                }

                // ── Информация ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Username
                    if (user.username.isNotEmpty()) {
                        ProfileInfoRow(
                            icon = Icons.Default.AlternateEmail,
                            label = "Username",
                            value = "@${user.username}",
                        )
                    }

                    // Телефон
                    if (user.phone.isNotEmpty()) {
                        ProfileInfoRow(
                            icon = Icons.Default.Phone,
                            label = "Телефон",
                            value = user.phone,
                        )
                    }

                    // О себе
                    if (!user.bio.isNullOrEmpty()) {
                        ProfileInfoRow(
                            icon = Icons.Default.Info,
                            label = "О себе",
                            value = user.bio,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Обои чата с паттерном (как в Telegram) ──────────────────────────────────

@Composable
private fun ChatWallpaper() {
    val extra = LocalMessengerColors.current
    val bgColor = extra.chatWallpaper
    val patternColor = extra.chatPattern

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(bgColor)

        val cellSize = 80f
        val cols = (size.width / cellSize).toInt() + 2
        val rows = (size.height / cellSize).toInt() + 2

        for (row in 0..rows) {
            for (col in 0..cols) {
                val cx = col * cellSize + if (row % 2 == 0) 0f else cellSize / 2
                val cy = row * cellSize
                val shape = (row * 7 + col * 3) % 5
                drawPatternShape(shape, cx, cy, patternColor)
            }
        }
    }
}

private fun DrawScope.drawPatternShape(shape: Int, cx: Float, cy: Float, color: Color) {
    when (shape) {
        0 -> {
            // Маленький круг
            drawCircle(color, radius = 4f, center = Offset(cx, cy))
        }
        1 -> {
            // Ромб
            val s = 6f
            val path = Path().apply {
                moveTo(cx, cy - s)
                lineTo(cx + s, cy)
                lineTo(cx, cy + s)
                lineTo(cx - s, cy)
                close()
            }
            drawPath(path, color)
        }
        2 -> {
            // Крестик
            val s = 5f
            drawLine(color, Offset(cx - s, cy - s), Offset(cx + s, cy + s), strokeWidth = 1.5f)
            drawLine(color, Offset(cx + s, cy - s), Offset(cx - s, cy + s), strokeWidth = 1.5f)
        }
        3 -> {
            // Маленький квадрат (повёрнутый)
            rotate(45f, Offset(cx, cy)) {
                drawRect(color, topLeft = Offset(cx - 3.5f, cy - 3.5f), size = Size(7f, 7f))
            }
        }
        4 -> {
            // Точка
            drawCircle(color, radius = 2.5f, center = Offset(cx, cy))
        }
    }
}
