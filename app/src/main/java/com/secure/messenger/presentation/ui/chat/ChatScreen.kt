package com.secure.messenger.presentation.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.presentation.theme.IncomingBubble
import com.secure.messenger.presentation.theme.IncomingBubbleDark
import com.secure.messenger.presentation.theme.OutgoingBubble
import com.secure.messenger.presentation.theme.OutgoingBubbleDark
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
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
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
            )

            // ── Список сообщений ───────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
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

            // ── Поле ввода ─────────────────────────────────────────────────
            MessageInputBar(
                text = inputText,
                editingMessage = uiState.editingMessage,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onCancelEdit = viewModel::cancelEditing,
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

            // Аватар с индикатором онлайн
            Box {
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
    val darkTheme = isSystemInDarkTheme()
    var showMenu by remember { mutableStateOf(false) }

    val bubbleColor = when {
        isOutgoing && darkTheme -> OutgoingBubbleDark
        isOutgoing -> OutgoingBubble
        darkTheme -> IncomingBubbleDark
        else -> IncomingBubble
    }

    val textColor = if (darkTheme) Color.White else Color.Black
    val metaColor = if (darkTheme) Color.White.copy(alpha = 0.45f) else Color.Gray

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

                Spacer(modifier = Modifier.width(8.dp))

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
