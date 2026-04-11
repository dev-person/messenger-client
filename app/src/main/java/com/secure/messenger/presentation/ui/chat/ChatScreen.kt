package com.secure.messenger.presentation.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
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
import kotlinx.coroutines.launch
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.repeatCount
import com.secure.messenger.BuildConfig
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.Message
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.model.User
import com.secure.messenger.presentation.theme.LocalMessengerColors
import com.secure.messenger.presentation.ui.components.ActionMenu
import com.secure.messenger.presentation.ui.components.ActionMenuItem
import com.secure.messenger.presentation.ui.components.longPressActionable
import com.secure.messenger.presentation.ui.components.toIntOffset
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
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingSeconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()
    val pendingVoice by viewModel.pendingVoice.collectAsStateWithLifecycle()
    val highlightedMessageId by viewModel.highlightedMessageId.collectAsStateWithLifecycle()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showProfileUser by remember { mutableStateOf<User?>(null) }

    // Permission launcher для записи голоса
    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording()
    }

    // Image picker — современный системный медиа-пикер (Android 13+ без permissions)
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Декодируем + сжимаем в фоне (через корутину ViewModel-а внутри sendImage было бы лучше,
            // но для простоты сжимаем тут на main и сразу отправляем — картинка ужимается до 1280px)
            val data = com.secure.messenger.utils.ImageCodec.loadAndCompress(context, uri)
            if (data != null) viewModel.sendImage(data)
        }
    }

    // Закрытие emoji-панели по системной кнопке «Назад»
    androidx.activity.compose.BackHandler(enabled = uiState.showEmojiPicker) {
        viewModel.closeEmojiPicker()
    }

    // Модалка превью голосового сообщения после записи
    pendingVoice?.let { pending ->
        VoicePreviewDialog(
            voice = pending,
            onSend = viewModel::confirmSendPendingVoice,
            onDiscard = viewModel::discardPendingVoice,
        )
    }

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

                // Плашка-подсказка когда чат пустой
                if (messages.isEmpty() && !uiState.isLoadingOlder) {
                    EmptyChatHint()
                }

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
                            // Найти цитируемое сообщение в текущем списке (если оно ещё на странице)
                            val replyTo = message.replyToId?.let { id ->
                                messages.firstOrNull { it.id == id }
                            }
                            MessageBubble(
                                message = message,
                                replyTo = replyTo,
                                isOutgoing = isOutgoing,
                                isHighlighted = highlightedMessageId == message.id,
                                onReply  = { viewModel.startReplying(message) },
                                onQuoteClick = { quotedId ->
                                    // Скроллим к оригинальному сообщению + подсвечиваем
                                    val targetIndex = messages.indexOfFirst { it.id == quotedId }
                                    if (targetIndex >= 0) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                        }
                                        viewModel.highlightMessage(quotedId)
                                    }
                                },
                                onDelete = if (isOutgoing) { { viewModel.deleteMessage(message) } } else null,
                                onEdit   = if (isOutgoing && message.type == MessageType.TEXT) {
                                    { viewModel.startEditing(message) }
                                } else null,
                            )
                        }
                    }
                }
            }

            // ── Поле ввода ─────────────────────────────────────────────────
            MessageInputBar(
                text = inputText,
                editingMessage = uiState.editingMessage,
                replyingTo = uiState.replyingTo,
                showEmojiPicker = uiState.showEmojiPicker,
                isRecording = isRecording,
                recordingSeconds = recordingSeconds,
                onTextChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onCancelEdit = viewModel::cancelEditing,
                onCancelReply = viewModel::cancelReplying,
                onToggleEmoji = {
                    // При открытии панели эмоджи закрываем системную клавиатуру —
                    // иначе она перекрывает панель и пользователь видит только дёрганье
                    if (!uiState.showEmojiPicker) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                    viewModel.toggleEmojiPicker()
                },
                onEmojiPick = viewModel::appendEmoji,
                onAttachImage = {
                    imagePickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onContentReceived = { uri ->
                    // Картинка/стикер из системной клавиатуры — обрабатываем как обычное вложение
                    val data = com.secure.messenger.utils.ImageCodec.loadAndCompress(context, uri)
                    if (data != null) viewModel.sendImage(data)
                },
                onStartRecording = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.startVoiceRecording()
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecording = viewModel::stopVoiceRecording,
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
    var menuVisible by remember { mutableStateOf(false) }

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
                    fontSize = 22.sp,
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

            // Кнопка меню действий (3 точки) — звонки и др. вынесены сюда
            if (isDirectChat) {
                Box {
                    IconButton(
                        onClick = { menuVisible = true },
                        enabled = peerId.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Действия",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    ActionMenu(
                        visible = menuVisible,
                        anchorOffset = IntOffset(0, 0),
                        onDismiss = { menuVisible = false },
                        actions = listOf(
                            ActionMenuItem(
                                label = "Аудиозвонок",
                                icon = Icons.Default.Call,
                                onClick = { onCallClick(peerId, false, peerName) },
                            ),
                            ActionMenuItem(
                                label = "Видеозвонок",
                                icon = Icons.Default.Videocam,
                                onClick = { onCallClick(peerId, true, peerName) },
                            ),
                            ActionMenuItem(
                                label = "Профиль",
                                icon = Icons.Default.Person,
                                onClick = onAvatarClick,
                            ),
                        ),
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

// ── Плашка-подсказка для пустого чата ────────────────────────────────────────

@Composable
private fun EmptyChatHint() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "👋", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Здесь пока нет сообщений",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Напишите первое сообщение, отправьте картинку или голосовое — собеседник получит его сразу",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// ── Пузырь сообщения ──────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: Message,
    replyTo: Message?,
    isOutgoing: Boolean,
    isHighlighted: Boolean,
    onReply: () -> Unit,
    onQuoteClick: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    val extra = LocalMessengerColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuVisible by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }

    // Анимированный фон для подсветки при клике на цитату
    val baseBubble = if (isOutgoing) extra.outgoingBubble else extra.incomingBubble
    val bubbleColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isHighlighted) {
            // Подмешиваем primary к базовому цвету для эффекта подсветки
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                .compositeOver(baseBubble)
        } else {
            baseBubble
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
        label = "bubble-highlight",
    )

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = if (isDark) Color.White else Color.Black
    val metaColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Gray

    // Все углы скруглены одинаково — без «хвостика». Маленький радиус,
    // чтобы метаданные (время + статус) не подрезались закруглением угла.
    val shape = RoundedCornerShape(14.dp)

    // Определяем — это стикер или обычная картинка (для решения как рендерить).
    // Декодируем payload только для IMAGE-сообщений (один раз на пересоздание).
    val isSticker = remember(message.id, message.type) {
        if (message.type == MessageType.IMAGE) {
            com.secure.messenger.utils.ImageCodec.decode(message.content)?.isSticker == true
        } else false
    }

    // «Медиа-режим» — картинка/стикер без bubble (как в Telegram/WhatsApp).
    // Активируется для IMAGE без цитаты. Стикеры дополнительно скрывают мета-оверлей.
    val isMediaOnly = message.type == MessageType.IMAGE && replyTo == null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = if (isMediaOnly) 260.dp else 280.dp)
                    .then(
                        // В медиа-режиме (стикеры/анимации/картинки) не клипаем углы —
                        // у медиа собственная форма с прозрачностью, скругление искажает её.
                        if (isMediaOnly) Modifier
                        else Modifier.clip(shape).background(bubbleColor)
                    )
                    .longPressActionable(
                        onLongPress = { offset ->
                            menuOffset = offset.toIntOffset()
                            menuVisible = true
                        },
                    )
                    .then(
                        if (isMediaOnly) Modifier
                        else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    .animateContentSize(),
            ) {
                // Цитата (если ответ на другое сообщение)
                if (replyTo != null) {
                    QuotedMessagePreview(
                        replyTo = replyTo,
                        accentColor = MaterialTheme.colorScheme.primary,
                        textColor = textColor,
                        onClick = { onQuoteClick(replyTo.id) },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Содержимое: текст / плеер для голосового / картинка
                Box {
                    when (message.type) {
                        MessageType.AUDIO -> VoiceMessagePlayer(
                            message = message,
                            accentColor = MaterialTheme.colorScheme.primary,
                            textColor = textColor,
                        )
                        MessageType.IMAGE -> ImageMessageContent(
                            payload = message.content,
                            onLongPress = { offset ->
                                menuOffset = offset.toIntOffset()
                                menuVisible = true
                            },
                        )
                        else -> Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }

                    // В медиа-режиме метаданные оверлеем поверх правого нижнего угла
                    // картинки на полупрозрачном тёмном фоне (стиль Telegram).
                    // Для стикеров overlay не показываем — стикер чистый как в WhatsApp.
                    if (isMediaOnly && !isSticker) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatMessageTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontSize = 11.sp,
                            )
                            if (isOutgoing) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageStatusIcon(
                                    status = message.status,
                                    metaColor = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }

                // Стандартные метаданные снизу — для не-медиа сообщений и для стикеров
                // (у стикеров overlay не используется, мета идёт компактно под картинкой)
                if (!isMediaOnly || isSticker) Row(
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

            ActionMenu(
                visible = menuVisible,
                anchorOffset = menuOffset,
                onDismiss = { menuVisible = false },
                actions = buildList {
                    add(
                        ActionMenuItem(
                            label = "Ответить",
                            icon = Icons.AutoMirrored.Filled.Reply,
                            onClick = onReply,
                        )
                    )
                    // Скачать — только для обычных картинок (не стикеров)
                    if (message.type == MessageType.IMAGE && !isSticker) {
                        add(
                            ActionMenuItem(
                                label = "Скачать",
                                icon = Icons.Default.Download,
                                onClick = {
                                    val data = com.secure.messenger.utils.ImageCodec.decode(message.content)
                                    if (data != null) {
                                        val ok = com.secure.messenger.utils.ImageSaver.save(
                                            context, data.bytes, data.mime,
                                        )
                                        android.widget.Toast.makeText(
                                            context,
                                            if (ok) "Сохранено в галерею" else "Не удалось сохранить",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        )
                    }
                    if (onEdit != null) {
                        add(
                            ActionMenuItem(
                                label = "Редактировать",
                                icon = Icons.Default.Edit,
                                onClick = onEdit,
                            )
                        )
                    }
                    if (onDelete != null) {
                        add(
                            ActionMenuItem(
                                label = "Удалить",
                                icon = Icons.Default.Delete,
                                danger = true,
                                onClick = onDelete,
                            )
                        )
                    }
                },
            )
        }
    }
}

// ── Цитата (превью цитируемого сообщения внутри bubble) ──────────────────────

@Composable
private fun QuotedMessagePreview(
    replyTo: Message,
    accentColor: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
    ) {
        // Вертикальная цветная полоска слева
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "Ответ",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
            Text(
                text = messagePreviewText(replyTo),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Плеер голосового сообщения ────────────────────────────────────────────────

@Composable
private fun VoiceMessagePlayer(
    message: Message,
    accentColor: Color,
    textColor: Color,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(message.id) { com.secure.messenger.utils.VoicePlayer(context) }
    var isPlaying by remember(message.id) { mutableStateOf(false) }
    var progress by remember(message.id) { mutableStateOf(0f) }

    // Распарсить payload с длительностью и base64-байтами один раз
    val voiceData = remember(message.id) {
        com.secure.messenger.utils.VoiceCodec.decode(message.content)
    }

    androidx.compose.runtime.DisposableEffect(message.id) {
        onDispose { player.release() }
    }

    Row(
        modifier = Modifier.widthIn(min = 180.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Box+clickable вместо IconButton — у IconButton минимальный интерактивный
        // размер 48dp, который при .size(38dp) обрезает иконку.
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(accentColor)
                .clickable {
                    if (voiceData == null) return@clickable
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.play(voiceData.bytes) { pos, total ->
                            if (total > 0) progress = pos.toFloat() / total
                            if (pos >= total) {
                                isPlaying = false
                                progress = 0f
                            }
                        }
                        isPlaying = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            com.secure.messenger.presentation.ui.components.WaveformBars(
                amplitudes = voiceData?.waveform ?: IntArray(0),
                progress = progress,
                activeColor = accentColor,
                inactiveColor = textColor.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatVoiceDuration(voiceData?.durationSeconds ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

private fun formatVoiceDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/**
 * Текст для превью сообщения (в цитатах, в списке чатов и т.д.).
 * Для медиа-сообщений возвращает читаемое описание вместо JSON-payload-а.
 */
private fun messagePreviewText(message: Message): String = when (message.type) {
    MessageType.AUDIO -> "Голосовое сообщение"
    MessageType.IMAGE -> "Фото"
    MessageType.VIDEO -> "Видео"
    MessageType.FILE  -> "Файл"
    else              -> message.content
}

// ── Модалка превью голосового сообщения после записи ─────────────────────────

@Composable
private fun VoicePreviewDialog(
    voice: ChatViewModel.PendingVoice,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(voice) { com.secure.messenger.utils.VoicePlayer(context) }
    var isPlaying by remember(voice) { mutableStateOf(false) }
    var progress by remember(voice) { mutableStateOf(0f) }

    androidx.compose.runtime.DisposableEffect(voice) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onDiscard,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Голосовое сообщение",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    player.play(voice.bytes) { pos, total ->
                                        if (total > 0) progress = pos.toFloat() / total
                                        if (pos >= total) {
                                            isPlaying = false
                                            progress = 0f
                                        }
                                    }
                                    isPlaying = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        com.secure.messenger.presentation.ui.components.WaveformBars(
                            amplitudes = voice.waveform,
                            progress = progress,
                            activeColor = MaterialTheme.colorScheme.primary,
                            inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatVoiceDuration(voice.durationSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Без иконок и компактный contentPadding — иначе текст
                    // переносится / обрезается на узких диалогах
                    androidx.compose.material3.OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 8.dp,
                        ),
                    ) {
                        Text(
                            text = "Отменить",
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    androidx.compose.material3.Button(
                        onClick = onSend,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 8.dp,
                        ),
                    ) {
                        Text(
                            text = "Отправить",
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

// ── Картинка в сообщении ─────────────────────────────────────────────────────

@Composable
private fun ImageMessageContent(
    payload: String,
    onLongPress: (Offset) -> Unit,
) {
    // Декодируем JSON один раз — содержит base64 байты, размеры и флаг isSticker
    val imageData = remember(payload) {
        com.secure.messenger.utils.ImageCodec.decode(payload)
    }
    var fullscreenOpen by remember { mutableStateOf(false) }
    // Счётчик «перезапусков» анимации. Меняется при тапе на анимацию —
    // используется как часть memoryCacheKey, чтобы Coil заново декодировал
    // байты и создавал свежий AnimatedImageDrawable (старый уже остановился
    // на последнем кадре после 3 циклов).
    var animationPlay by remember(payload) { androidx.compose.runtime.mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (imageData == null) {
        Text(
            text = "[не удалось загрузить картинку]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    // Coil умеет работать с ByteArray напрямую — без записи во временный файл.
    // Анимированные GIF/WebP оживают благодаря ImageDecoderDecoder в MessengerApp.
    val aspectRatio = if (imageData.width > 0 && imageData.height > 0) {
        imageData.width.toFloat() / imageData.height.toFloat()
    } else 1f

    // Различаем три вида контента:
    // 1) Статичный стикер (PNG с прозрачностью) — компактный 160dp как в Telegram
    // 2) Анимация (GIF / animated WebP) — побольше 260dp чтобы было видно
    // 3) Обычная картинка (фото) — большой 260x360 + bubble + zoom + скачать
    val isAnimation = imageData.isSticker &&
            (imageData.mime == "image/gif" || imageData.mime == "image/webp")
    val maxWidth: androidx.compose.ui.unit.Dp
    val maxHeight: androidx.compose.ui.unit.Dp
    when {
        isAnimation -> {
            maxWidth = 260.dp
            maxHeight = 260.dp
        }
        imageData.isSticker -> {
            maxWidth = 160.dp
            maxHeight = 160.dp
        }
        else -> {
            maxWidth = 260.dp
            maxHeight = 360.dp
        }
    }
    // Анимации (GIF/animated WebP) кадрируем в фиксированный квадрат через
    // ContentScale.Crop — края обрезаются, но кадр заполняется целиком (как
    // CSS object-fit: cover). Стикеры и обычные фото остаются Fit с aspect.
    val sizeModifier = if (isAnimation) {
        Modifier.size(maxWidth, maxHeight)
    } else {
        Modifier
            .widthIn(max = maxWidth)
            .heightIn(max = maxHeight)
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
    }
    // Для анимаций строим ImageRequest с repeatCount(2) — после 1 первого
    // проигрывания + 2 повторов = 3 цикла, AnimatedImageDrawable замирает
    // на последнем кадре. Уникальный memoryCacheKey + animationPlay позволяет
    // тапом перезапустить анимацию: новый ключ → свежее декодирование →
    // новый Drawable, который снова крутится 3 раза.
    val animationModel = remember(payload, animationPlay) {
        if (isAnimation) {
            coil.request.ImageRequest.Builder(context)
                .data(imageData.bytes)
                .repeatCount(2)
                .memoryCacheKey("anim-${payload.hashCode()}-$animationPlay")
                .build()
        } else null
    }
    coil.compose.AsyncImage(
        model = animationModel ?: imageData.bytes,
        contentDescription = if (imageData.isSticker) "Стикер" else "Картинка",
        contentScale = if (isAnimation) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .then(sizeModifier)
            // Скругления у всех кроме статичных стикеров — у стикеров своя
            // прозрачная форма, которую clip искажает. Анимации теперь
            // кадрированы через Crop (фиксированный квадрат), так что
            // скруглять можно безопасно.
            .then(
                if (imageData.isSticker && !isAnimation) Modifier
                else Modifier.clip(RoundedCornerShape(14.dp))
            )
            .pointerInput(imageData.isSticker, isAnimation) {
                detectTapGestures(
                    onTap = when {
                        // Тап по анимации перезапускает 3 цикла
                        isAnimation -> { _ -> animationPlay++ }
                        // Тап по обычной картинке открывает fullscreen
                        !imageData.isSticker -> { _ -> fullscreenOpen = true }
                        // Статичные стикеры — тап ничего не делает
                        else -> null
                    },
                    onLongPress = onLongPress,
                )
            },
    )

    if (fullscreenOpen && !imageData.isSticker) {
        FullscreenImageViewer(
            imageBytes = imageData.bytes,
            onDismiss = { fullscreenOpen = false },
        )
    }
}

// ── Полноэкранный просмотр картинки ──────────────────────────────────────────

@Composable
private fun FullscreenImageViewer(
    imageBytes: ByteArray,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        // Пан работает только когда zoom > 1, иначе картинка центрирована
        offset = if (newScale > 1f) offset + panChange else Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = imageBytes,
                contentDescription = "Картинка",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(state = transformableState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Тап закрывает только когда zoom = 1 (не мешает рассматривать)
                                if (scale <= 1.05f) onDismiss()
                            },
                            onDoubleTap = {
                                // Двойной тап — переключение 1x ↔ 2.5x
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    },
            )

            // Кнопка закрытия в правом верхнем углу
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White,
                )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageInputBar(
    text: String,
    editingMessage: Message?,
    replyingTo: Message?,
    showEmojiPicker: Boolean,
    isRecording: Boolean,
    recordingSeconds: Int,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onToggleEmoji: () -> Unit = {},
    onEmojiPick: (String) -> Unit = {},
    onAttachImage: () -> Unit = {},
    onContentReceived: (android.net.Uri) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: (cancel: Boolean) -> Unit = {},
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            // Полоска редактирования
            if (editingMessage != null) {
                InputContextBar(
                    icon = Icons.Default.Edit,
                    title = "Редактирование",
                    subtitle = editingMessage.content,
                    onCancel = onCancelEdit,
                )
            }
            // Полоска ответа (цитаты)
            if (replyingTo != null) {
                InputContextBar(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    title = "Ответ",
                    subtitle = messagePreviewText(replyingTo),
                    onCancel = onCancelReply,
                )
            }

            // Если идёт запись — показываем индикатор записи вместо обычного инпута
            if (isRecording) {
                RecordingBar(
                    seconds = recordingSeconds,
                    onCancel = { onStopRecording(true) },
                    onSend = { onStopRecording(false) },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            // Когда панель эмоджи открыта — убираем нижний отступ,
                            // чтобы инпут и панель были визуально одним блоком
                            bottom = if (showEmojiPicker) 0.dp else 8.dp,
                        )
                        .then(
                            // Когда панель эмоджи открыта — её padding обрабатывает
                            // системную навигацию, не дублируем здесь
                            if (showEmojiPicker) Modifier.imePadding()
                            else Modifier.navigationBarsPadding().imePadding()
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Кнопка эмоджи слева
                    IconButton(
                        onClick = onToggleEmoji,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEmotions,
                            contentDescription = "Эмоджи",
                            tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // RichInputField оборачивает AppCompatEditText через AndroidView —
                    // это даёт реальную поддержку commitContent от Gboard (стикеры/гифы),
                    // которой нет в BasicTextField (его contentReceiver не пробрасывает
                    // MIME-types в EditorInfo).
                    com.secure.messenger.presentation.ui.components.RichInputField(
                        text = text,
                        placeholder = "Сообщение",
                        onTextChange = onTextChange,
                        onContentReceived = onContentReceived,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )

                    // Кнопка прикрепления картинки — справа от инпута,
                    // на месте где раньше была иконка ИИ-улучшения текста
                    IconButton(
                        onClick = onAttachImage,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Прикрепить",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Кнопка отправки (если есть текст) или микрофон-удержание (если пусто)
                    if (text.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onSend),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Hold-to-record как в Telegram: записываем пока палец удерживается,
                        // отпустили — отправили; уехали пальцем вверх (отмена) — не реализовано
                        // в этой версии, можно отменить кнопкой корзины в RecordingBar.
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        // Ждём первое касание → начало записи
                                        awaitFirstDown(requireUnconsumed = false)
                                        onStartRecording()
                                        // Ждём отпускание пальца → стоп → откроется модалка превью
                                        var allReleased = false
                                        while (!allReleased) {
                                            val event = awaitPointerEvent()
                                            allReleased = event.changes.none { it.pressed }
                                        }
                                        onStopRecording(false)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Удерживайте для записи",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            // Панель эмоджи под полем ввода (с slide-анимацией)
            androidx.compose.animation.AnimatedVisibility(
                visible = showEmojiPicker && !isRecording,
                enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                       androidx.compose.animation.fadeOut(),
            ) {
                com.secure.messenger.presentation.ui.components.EmojiPicker(
                    onEmojiSelected = onEmojiPick,
                )
            }
        }
    }
}

// ── Полоска контекста (редактирование / ответ) ───────────────────────────────

@Composable
private fun InputContextBar(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Вертикальная полоска-акцент слева
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Отмена",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Полоска записи голосового ─────────────────────────────────────────────────

@Composable
private fun RecordingBar(
    seconds: Int,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec_pulse")
        .animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(700),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(42.dp),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Отменить запись",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Красный мигающий индикатор
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935).copy(alpha = pulse)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Запись · ${formatVoiceDuration(seconds)}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Отправить",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
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
