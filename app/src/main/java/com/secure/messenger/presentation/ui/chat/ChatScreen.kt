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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
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

// ── Типы элементов в чате (сообщение или альбом картинок) ────────────────────

/**
 * Плоский элемент списка чата. Альбом из нескольких картинок одного
 * отправителя рендерится как ОДИН элемент-плитка (а не N отдельных bubble).
 * DateHeader — горизонтальная плашка с датой («Сегодня», «14 марта» и т.д.),
 * раньше рисовалась инлайново внутри itemsIndexed; вынесена в отдельный тип,
 * чтобы работать с reverseLayout без костылей.
 */
private sealed interface ChatItem {
    val key: String
    val timestamp: Long

    data class DateHeader(override val timestamp: Long) : ChatItem {
        override val key get() = "date-$timestamp"
    }

    data class Single(val message: Message) : ChatItem {
        override val key get() = message.id
        override val timestamp get() = message.timestamp
    }

    data class Album(
        val groupId: String,
        val senderId: String,
        val messages: List<Message>,
    ) : ChatItem {
        // key привязан к groupId + последнему id — обновится если группа растёт
        override val key get() = "album-$groupId-${messages.size}"
        override val timestamp get() = messages.first().timestamp
    }
}

/**
 * Группирует подряд идущие IMAGE-сообщения одного отправителя с одинаковым
 * groupId в альбом + вставляет [ChatItem.DateHeader] в начале каждого нового дня.
 * Возвращает элементы в ХРОНОЛОГИЧЕСКОМ порядке (oldest → newest). Для отображения
 * с reverseLayout=true список нужно перевернуть непосредственно перед передачей
 * в LazyColumn.
 */
private fun groupChatItems(messages: List<Message>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()

    // 1. Группируем картинки в альбомы
    val grouped = mutableListOf<ChatItem>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        val gi = if (m.type == MessageType.IMAGE) {
            com.secure.messenger.utils.ImageCodec.extractGroupInfo(m.content)
        } else null

        if (gi != null && gi.size > 1) {
            val album = mutableListOf(m)
            var j = i + 1
            while (j < messages.size) {
                val next = messages[j]
                if (next.senderId != m.senderId || next.type != MessageType.IMAGE) break
                val ni = com.secure.messenger.utils.ImageCodec.extractGroupInfo(next.content)
                if (ni == null || ni.groupId != gi.groupId) break
                album.add(next)
                j++
            }
            grouped.add(
                ChatItem.Album(
                    groupId = gi.groupId,
                    senderId = m.senderId,
                    messages = album.sortedBy { msg ->
                        com.secure.messenger.utils.ImageCodec.extractGroupInfo(msg.content)?.index ?: 0
                    },
                )
            )
            i = j
        } else {
            grouped.add(ChatItem.Single(m))
            i++
        }
    }

    // 2. Вставляем DateHeader перед первым элементом каждого нового календарного дня
    val result = mutableListOf<ChatItem>()
    for ((idx, item) in grouped.withIndex()) {
        val prev = if (idx > 0) grouped[idx - 1] else null
        if (prev == null || !isSameDay(prev.timestamp, item.timestamp)) {
            result.add(ChatItem.DateHeader(item.timestamp))
        }
        result.add(item)
    }
    return result
}

// ── Экран чата ────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onCallClick: (userId: String, isVideo: Boolean, peerName: String) -> Unit,
    onGroupInfoClick: (chatId: String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatInfo by viewModel.chatInfo.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val isOtherOnline by viewModel.isOtherUserOnline.collectAsStateWithLifecycle()
    val otherUser by viewModel.otherUser.collectAsStateWithLifecycle()
    val hasOutdatedMembers by viewModel.hasOutdatedGroupMembers.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()
    val groupOnlineCount by viewModel.groupOnlineCount.collectAsStateWithLifecycle()
    val groupTypingNames by viewModel.groupTypingNames.collectAsStateWithLifecycle()
    val isBotChat = chatInfo?.otherUserId == "00000000-0000-0000-0000-000000000001"
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingSeconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()
    val recordingWaveform by viewModel.recordingWaveform.collectAsStateWithLifecycle()
    val pendingVoice by viewModel.pendingVoice.collectAsStateWithLifecycle()
    val albumProgress by viewModel.albumSendProgress.collectAsStateWithLifecycle()
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

    // Хэлпер: декодирует URI и отправляет картинку, либо сообщает об ошибке
    // пользователю (стикер слишком большой / формат не поддержан). Раньше
    // стикеры, не прошедшие по размеру, молча проглатывались — пользователь
    // тапал и ничего не происходило.
    val sendMediaFromUri: (android.net.Uri) -> Unit = { uri ->
        when (val r = com.secure.messenger.utils.ImageCodec.loadAndCompressDetailed(context, uri)) {
            is com.secure.messenger.utils.ImageCodec.LoadResult.Ok -> {
                viewModel.sendImage(r.data)
            }
            is com.secure.messenger.utils.ImageCodec.LoadResult.TooLarge -> {
                val mb = r.sizeBytes / 1024.0 / 1024.0
                val limitMb = r.limitBytes / 1024.0 / 1024.0
                viewModel.reportError(
                    "Стикер слишком большой (%.1f МБ, максимум %.1f МБ)"
                        .format(mb, limitMb)
                )
            }
            is com.secure.messenger.utils.ImageCodec.LoadResult.Failed -> {
                viewModel.reportError(r.reason)
            }
        }
    }

    // Image picker — современный системный медиа-пикер для ОДНОЙ картинки
    // (используется для стикеров из клавиатуры, он работает по одному URI)
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) sendMediaFromUri(uri)
    }

    // Множественный пикер — до 15 картинок за раз. При выборе одной автоматически
    // посылается как обычное одиночное сообщение, при выборе нескольких —
    // группируются по groupId и рендерятся в UI как плитка.
    val multiImagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = 15)
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // Для альбома ужимаем АГРЕССИВНО (720px / quality 68) — 15 крупных
        // картинок иначе разгоняют Room до десятков МБ и приложение падает
        // по OOM при эмиссиях Flow-ов.
        val isAlbum = uris.size > 1
        val decoded = uris.mapNotNull { uri ->
            com.secure.messenger.utils.ImageCodec.loadAndCompress(
                context = context,
                uri = uri,
                maxDim = if (isAlbum) 720 else 1280,
                quality = if (isAlbum) 68 else 80,
            )
        }
        if (decoded.isNotEmpty()) viewModel.sendImages(decoded)
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

    // Группируем сообщения в chatItems (альбом = один элемент, плюс DateHeader
    // перед каждой новой датой). Затем реверсим ОДИН раз для reverseLayout:
    // индекс 0 в этом списке = САМОЕ новое сообщение, визуально находится
    // внизу экрана. Это убирает проблему «сначала загружается верх, потом
    // скроллится вниз» — LazyColumn сразу рендерит нижнюю часть списка,
    // где только что и находится пользователь.
    val chatItems = remember(messages) { groupChatItems(messages) }
    val reversedItems = remember(chatItems) { chatItems.asReversed() }

    // ── Автоскролл при НОВОМ сообщении ─────────────────────────────────────
    // При reverseLayout=true индекс 0 — это низ экрана (самое новое). Если
    // пользователь листал историю вверх и пришло новое сообщение от
    // собеседника — мы НЕ перебиваем чтение. Но если сообщение своё, или
    // пользователь уже у «низа» (firstVisibleItemIndex == 0), — плавно
    // возвращаем к самому низу, чтобы сообщение было видно.
    val newestKey = reversedItems.firstOrNull()?.key
    LaunchedEffect(newestKey, currentUserId) {
        if (newestKey == null || reversedItems.isEmpty()) return@LaunchedEffect
        val newest = reversedItems.first()
        val ownSend = when (newest) {
            is ChatItem.Single -> newest.message.senderId == currentUserId
            is ChatItem.Album  -> newest.senderId == currentUserId
            is ChatItem.DateHeader -> false
        }
        val userAtBottom = listState.firstVisibleItemIndex == 0
        if (ownSend || userAtBottom) {
            listState.animateScrollToItem(0)
        }
    }

    // ── Умная подгрузка старых сообщений ──────────────────────────────────
    // В reverseLayout=true листание ВВЕРХ (к старым) = увеличение индекса.
    // Триггерим подгрузку, когда до верхнего конца списка осталось ≤ 5
    // айтемов — так, чтобы новые сообщения успели подъехать невидимо для
    // пользователя. loadOlderMessages() сам защищён от retry-шторма.
    LaunchedEffect(listState, reversedItems.size) {
        if (reversedItems.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .filter { it >= 0 && it >= reversedItems.size - 5 }
            .collect { viewModel.loadOlderMessages() }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Высоты инпут-панели и top-bar — нужны чтобы LazyColumn скроллился
    // ПОД обоими через contentPadding: сверху под шапку, снизу под инпут.
    // Тогда сквозь их полупрозрачные подложки видно текущее содержимое.
    var inputBarHeightPx by remember { mutableStateOf(0) }
    var topBarHeightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inputBarHeightDp = with(density) { inputBarHeightPx.toDp() }
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }

    // GraphicsLayer для захвата контента ПОД шапкой и отрисовки его
    // с blur-эффектом. На Android 12+ (API 31) renderEffect работает как
    // настоящее помутнение/frosted glass. На более старых версиях — просто
    // полупрозрачная подложка без блюра.
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val blurSupported = android.os.Build.VERSION.SDK_INT >= 31
    androidx.compose.runtime.DisposableEffect(blurSupported) {
        if (blurSupported) {
            backdropLayer.renderEffect = androidx.compose.ui.graphics.BlurEffect(
                radiusX = 30f,
                radiusY = 30f,
                edgeTreatment = androidx.compose.ui.graphics.TileMode.Clamp,
            )
        }
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Обои располагаем САМЫМ НИЖНИМ слоем — поверх них (полупрозрачно)
        // рендерится инпут и эмоджи-панель. Иначе за ними видно только
        // сплошной surfaceContainerLow и прозрачность не даёт эффекта.
        ChatWallpaper()

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Список сообщений: занимает ВСЮ высоту экрана. Шапка и инпут
            //    рендерятся оверлеями сверху/снизу — сообщения просвечивают
            //    через их полупрозрачные подложки при скролле. ──────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Захватываем всё что рисуется в этом Box (обои + сообщения)
                    // в graphicsLayer, затем отрисовываем дополнительно без
                    // эффекта (так как у layer установлен blur) — layer потом
                    // нарисуется за шапкой с блюром.
                    .drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    },
            ) {
                // Плашка-подсказка когда чат пустой
                if (messages.isEmpty() && !uiState.isLoadingOlder) {
                    EmptyChatHint()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // reverseLayout=true — индекс 0 (самое новое сообщение)
                    // находится ВНИЗУ экрана. Пользователь открывает чат и
                    // сразу видит последние сообщения, без промежуточного
                    // «загружаем всю пачку сверху, потом скроллим вниз».
                    reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        // При reverseLayout top/bottom paddings трактуются
                        // от соответствующих краёв экрана: top = сверху под
                        // шапкой (там прогрузятся старые), bottom = сверху
                        // над инпутом (там живёт самое новое).
                        top = topBarHeightDp + 8.dp,
                        bottom = inputBarHeightDp + 8.dp,
                    ),
                ) {
                    itemsIndexed(reversedItems, key = { _, it -> it.key }) { index, item ->
                        when (item) {
                            is ChatItem.DateHeader -> DateSeparator(timestamp = item.timestamp)
                            is ChatItem.Single -> {
                                val message = item.message
                                val isBotMessage = message.senderId == "00000000-0000-0000-0000-000000000001"
                                if (message.type == MessageType.SYSTEM && !isBotMessage) {
                                    val displayText = displaySystemMessageText(
                                        rawText = message.content,
                                        isOwnMessage = message.senderId == currentUserId,
                                    )
                                    SystemMessage(text = displayText, timestamp = message.timestamp)
                                } else {
                                    val isOutgoing = message.senderId == currentUserId
                                    val replyTo = message.replyToId?.let { id ->
                                        messages.firstOrNull { it.id == id }
                                    }
                                    MessageBubble(
                                        message = message,
                                        replyTo = replyTo,
                                        isOutgoing = isOutgoing,
                                        isHighlighted = highlightedMessageId == message.id,
                                        senderName = resolveGroupSenderName(
                                            chat = chatInfo,
                                            senderId = message.senderId,
                                            isOutgoing = isOutgoing,
                                            members = groupMembers,
                                        ),
                                        onReply  = { viewModel.startReplying(message) },
                                        onQuoteClick = { quotedId ->
                                            val targetItemIndex = reversedItems.indexOfFirst {
                                                when (it) {
                                                    is ChatItem.Single -> it.message.id == quotedId
                                                    is ChatItem.Album -> it.messages.any { m -> m.id == quotedId }
                                                    is ChatItem.DateHeader -> false
                                                }
                                            }
                                            if (targetItemIndex >= 0) {
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(targetItemIndex)
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
                            is ChatItem.Album -> {
                                val isOutgoing = item.senderId == currentUserId
                                AlbumBubble(
                                    messages = item.messages,
                                    isOutgoing = isOutgoing,
                                    senderName = resolveGroupSenderName(
                                        chat = chatInfo,
                                        senderId = item.senderId,
                                        isOutgoing = isOutgoing,
                                        members = groupMembers,
                                    ),
                                    onDelete = if (isOutgoing) {
                                        { msg -> viewModel.deleteMessage(msg) }
                                    } else null,
                                )
                            }
                        }
                    }

                    // Индикатор загрузки старых сообщений — размещаем ПОСЛЕ
                    // всех айтемов в DSL-порядке. При reverseLayout=true это
                    // означает визуально САМЫЙ ВЕРХ экрана. Слот всегда имеет
                    // фиксированную высоту (пока есть что грузить), чтобы его
                    // появление/исчезновение не двигало сообщения ниже — это и
                    // давало «дрыгание» списка в прошлой версии.
                    if (uiState.hasOlderMessages) {
                        item(key = "older_loader_slot", contentType = "older_loader") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.isLoadingOlder) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Шапка чата — оверлей сверху, под ней скроллятся сообщения
        //    через blur-лэйер (на Android 12+) — эффект frosted glass. ──────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { topBarHeightPx = it.height }
                // Рисуем захваченный layer с blur-эффектом ДО контента шапки —
                // так он становится подложкой, поверх которой уже лежит
                // полупрозрачный surface с иконками/текстом.
                .drawBehind {
                    clipRect(right = size.width, bottom = size.height) {
                        drawLayer(backdropLayer)
                    }
                },
        ) {
            // Column нужен чтобы шапка и плашка-предупреждение рендерились
            // друг под другом, а не накладывались поверх (внешний Box —
            // именно с `align(TopStart)` для blur-оверлея, и его дети по
            // дефолту стэкаются). onSizeChanged внешнего Box получит
            // суммарную высоту Column → topBarHeightPx учитывает и плашку,
            // LazyColumn ниже не залезает под неё.
            Column {
                ChatTopBar(
                    chatInfo = chatInfo,
                    currentUserId = currentUserId,
                    isOtherOnline = isOtherOnline,
                    isTyping = isTyping,
                    groupMemberCount = groupMembers.size,
                    groupOnlineCount = groupOnlineCount,
                    groupTypingNames = groupTypingNames.values.toList(),
                    onBack = onBack,
                    onCallClick = onCallClick,
                    onAvatarClick = { otherUser?.let { showProfileUser = it } },
                    onGroupInfoClick = { chatInfo?.id?.let(onGroupInfoClick) },
                )
                if (chatInfo?.type == ChatType.GROUP && hasOutdatedMembers) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chatInfo?.id?.let(onGroupInfoClick) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Кто-то из участников ещё не обновил приложение и не сможет читать сообщения",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        // ── Поле ввода рендерится ОВЕРЛЕЕМ в outer Box, а не в Column —
        //    тогда LazyColumn тянется на всю высоту до низа экрана, и при
        //    скролле сообщения просвечивают через полупрозрачный инпут. ──
        if (!isBotChat) MessageInputBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onSizeChanged { inputBarHeightPx = it.height },
            text = inputText,
            editingMessage = uiState.editingMessage,
            replyingTo = uiState.replyingTo,
            showEmojiPicker = uiState.showEmojiPicker,
            isRecording = isRecording,
            recordingSeconds = recordingSeconds,
            recordingWaveform = recordingWaveform,
            onTextChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onCancelEdit = viewModel::cancelEditing,
            onCancelReply = viewModel::cancelReplying,
            onToggleEmoji = {
                if (!uiState.showEmojiPicker) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
                viewModel.toggleEmojiPicker()
            },
            onEmojiPick = viewModel::appendEmoji,
            onBackspace = viewModel::backspaceInput,
            onAttachImage = {
                multiImagePickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onContentReceived = { uri -> sendMediaFromUri(uri) },
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

        // Fade-полоса у самого низа экрана: прозрачный сверху → тёмный внизу.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(13.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                ),
        )

        // Плашка прогресса отправки альбома — над инпутом, пока идёт отправка
        albumProgress?.let { progress ->
            AlbumProgressBanner(
                sent = progress.sent,
                total = progress.total,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputBarHeightDp + 12.dp, start = 16.dp, end = 16.dp),
            )
        }

        // Snackbar — поднимаем над инпутом (иначе сообщение «Запись пуста»
        // и прочие ошибки отрисовывались поверх/за инпутом снизу).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = inputBarHeightDp + 12.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            ChatSnackbar(message = data.visuals.message)
        }
    }
}

// ── Шапка чата (стиль как у экрана Чаты) ─────────────────────────────────────

@Composable
private fun ChatTopBar(
    chatInfo: Chat?,
    currentUserId: String?,
    isOtherOnline: Boolean,
    isTyping: Boolean,
    groupMemberCount: Int = 0,
    groupOnlineCount: Int = 0,
    groupTypingNames: List<String> = emptyList(),
    onBack: () -> Unit,
    onCallClick: (userId: String, isVideo: Boolean, peerName: String) -> Unit,
    onAvatarClick: () -> Unit = {},
    onGroupInfoClick: () -> Unit = {},
) {
    val peerId   = chatInfo?.otherUserId ?: ""
    val peerName = chatInfo?.title ?: ""
    val isDirectChat = chatInfo == null || chatInfo.type != ChatType.GROUP
    var menuVisible by remember { mutableStateOf(false) }

    // На Android 12+ под шапкой живёт real-blur (BackdropBlurEffect) и 0.72
        // достаточно для «матового стекла». На более ранних версиях блюра нет —
        // полупрозрачная шапка выглядит как просто тускло-серая, и сообщения
        // через неё читаются невнятно. Повышаем альфу до 0.96, чтобы шапка
        // оставалась чёткой и не мешала чтению.
    val topBarAlpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.72f else 0.96f
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = topBarAlpha),
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

            // Аватар с индикатором онлайн. Для direct — клик открывает профиль
            // собеседника; для group — клик открывает экран информации о группе
            // (такой же как клик по названию).
            Box(
                modifier = if (isDirectChat) Modifier.clickable(onClick = onAvatarClick)
                else Modifier.clickable(onClick = onGroupInfoClick),
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

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    // Клик по названию и подзаголовку: для группы открывает
                    // экран информации; для direct — профиль собеседника.
                    .clickable(onClick = if (isDirectChat) onAvatarClick else onGroupInfoClick),
            ) {
                Text(
                    text = chatInfo?.title ?: "Чат",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val isGroupTyping = chatInfo?.type == ChatType.GROUP && groupTypingNames.isNotEmpty()
                val subtitle = chatSubtitle(
                    chat = chatInfo,
                    isDirectChat = isDirectChat,
                    isOtherOnline = isOtherOnline,
                    isDirectTyping = isTyping,
                    groupMemberCount = groupMemberCount,
                    groupOnlineCount = groupOnlineCount,
                    groupTypingNames = groupTypingNames,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isTyping || isGroupTyping -> MaterialTheme.colorScheme.primary
                            isOtherOnline -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                    )
                }
            }

            // Кнопка меню действий (3 точки) — звонки и др. (скрыто для бота)
            val isBotPeer = peerId == "00000000-0000-0000-0000-000000000001"
            if (isDirectChat && !isBotPeer) {
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

/**
 * Перерасчёт текста системного сообщения с учётом перспективы текущего юзера.
 *
 * Сервер кладёт в БД одну строку для обоих участников звонка ("Пропущенный
 * звонок"), но звонящему такое формулирование некорректно — он не пропускал,
 * это он звонил и не дозвонился. Для звонящего меняем на "Звонок без ответа".
 *
 * Текст «Звонок · 1:23» / «Звонок отклонён» / «Видеозвонок · 0:14» оставляем
 * как есть — они одинаково подходят обоим.
 */
// ── Helpers для групповых сообщений (1.0.68) ─────────────────────────────────

/**
 * Имя автора в групповом чате — для шапки над чужими сообщениями. Возвращает
 * displayName из подгруженных members; для DIRECT, своих сообщений или ещё не
 * загруженных участников — null (UI не рендерит ничего, чтобы не показывать
 * сырой UUID).
 */
private fun resolveGroupSenderName(
    chat: Chat?,
    senderId: String,
    isOutgoing: Boolean,
    members: List<User>,
): String? {
    if (chat?.type != ChatType.GROUP || isOutgoing) return null
    return members.firstOrNull { it.id == senderId }
        ?.displayName
        ?.takeIf { it.isNotBlank() }
}

/**
 * Текст под названием чата в шапке. Изолирован в отдельную функцию, чтобы не
 * раздувать composable: для DIRECT/GROUP логика и приоритеты разные, а в
 * самой шапке остаётся только Text(subtitle).
 */
private fun chatSubtitle(
    chat: Chat?,
    isDirectChat: Boolean,
    isOtherOnline: Boolean,
    isDirectTyping: Boolean,
    groupMemberCount: Int,
    groupOnlineCount: Int,
    groupTypingNames: List<String>,
): String = when {
    // DIRECT
    isDirectChat && isDirectTyping -> "печатает..."
    isDirectChat && isOtherOnline -> "в сети"
    isDirectChat -> "не в сети"
    // GROUP
    chat?.type == ChatType.GROUP && groupTypingNames.isNotEmpty() ->
        groupTypingSubtitle(groupTypingNames)
    chat?.type == ChatType.GROUP -> {
        val count = groupMemberCount.takeIf { it > 0 } ?: chat.members.size
        if (groupOnlineCount > 0) "$count участников · $groupOnlineCount онлайн"
        else "$count участников"
    }
    else -> ""
}

/**
 * Компактный текст «X печатает…» для группы. До трёх человек называем
 * по именам, дальше — счётчик «X и ещё N».
 */
private fun groupTypingSubtitle(typingNames: List<String>): String = when (typingNames.size) {
    0 -> ""
    1 -> "${typingNames[0]} печатает…"
    2 -> "${typingNames[0]} и ${typingNames[1]} печатают…"
    else -> "${typingNames[0]} и ещё ${typingNames.size - 1} печатают…"
}

/** Подпись «Имя автора» над/внутри bubble для чужих сообщений в группе. */
@Composable
private fun GroupSenderLabel(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun displaySystemMessageText(rawText: String, isOwnMessage: Boolean): String {
    if (!isOwnMessage) return rawText
    return when {
        rawText == "Пропущенный Звонок"      -> "Звонок без ответа"
        rawText == "Пропущенный Видеозвонок" -> "Видеозвонок без ответа"
        else                                  -> rawText
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
    /** Имя автора — показывается над bubble в групповых чатах для чужих сообщений. */
    senderName: String? = null,
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
                // Имя автора в групповом чате (только для чужих сообщений).
                // Показываем ВНУТРИ bubble сверху; для медиа-режима (стикеры,
                // одиночные картинки) подавляем — там нет фона, имя смотрелось
                // бы инородно поверх контента.
                if (senderName != null && !isOutgoing && !isMediaOnly) {
                    GroupSenderLabel(name = senderName)
                    Spacer(modifier = Modifier.height(2.dp))
                }

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
                    // Копирование текста (только для TEXT-сообщений)
                    if (message.type == MessageType.TEXT && message.content.isNotEmpty()) {
                        add(
                            ActionMenuItem(
                                label = "Скопировать",
                                icon = Icons.Default.ContentCopy,
                                onClick = {
                                    val cm = context.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE
                                    ) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "message", message.content,
                                        )
                                    )
                                    // На Android 13+ система сама показывает визуальный feedback
                                    // о копировании, на более старых показываем Toast сами.
                                    if (android.os.Build.VERSION.SDK_INT < 33) {
                                        android.widget.Toast.makeText(
                                            context, "Скопировано", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        )
                    }
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
        // Заглушка для битого / отсутствующего payload-а: показываем плашку
        // вместо красного текста об ошибке. Картинка может быть «недоступна»
        // если: payload пришёл повреждённым, JSON не парсится, base64 битый.
        // Размер совпадает с обычной картинкой, чтобы пузырь не «прыгал».
        ImagePlaceholder(
            modifier = Modifier.size(220.dp, 160.dp),
            label = "Изображение недоступно",
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
    // Для обычных картинок строим ImageRequest с .size() — Coil самплит
    // bitmap при декодировании до нужного размера, а не держит в памяти
    // оригинал 1280px. Без этого в чате с десятками фото приложение падало
    // по OOM при одновременном декодировании (см. FIX#2).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val photoModel = remember(payload, imageData.bytes, maxWidth, maxHeight) {
        if (!isAnimation && !imageData.isSticker) {
            val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
            val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
            coil.request.ImageRequest.Builder(context)
                .data(imageData.bytes)
                .size(widthPx, heightPx)
                .memoryCacheKey("photo-${payload.hashCode()}-${widthPx}x${heightPx}")
                .build()
        } else null
    }
    // Если Coil не смог декодировать байты (битый формат / неподдерживаемый
    // кодек) — показываем плашку-заглушку вместо «битой иконки» по умолчанию.
    var loadFailed by remember(payload) { mutableStateOf(false) }

    if (loadFailed) {
        ImagePlaceholder(
            modifier = Modifier
                .then(sizeModifier)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = onLongPress)
                },
            label = "Изображение недоступно",
        )
    } else {
        coil.compose.AsyncImage(
            model = animationModel ?: photoModel ?: imageData.bytes,
            contentDescription = if (imageData.isSticker) "Стикер" else "Картинка",
            contentScale = if (isAnimation) ContentScale.Crop else ContentScale.Fit,
            onError = { loadFailed = true },
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
    }

    if (fullscreenOpen && !imageData.isSticker) {
        FullscreenImageViewer(
            imageBytes = imageData.bytes,
            onDismiss = { fullscreenOpen = false },
        )
    }
}

// ── Альбом из нескольких картинок (плитка как в Telegram) ────────────────────

/**
 * Рендерит N картинок одного отправителя, отправленных одним альбомом, как
 * плитку с фиксированной шириной 260dp. Layout:
 *  - 2 шт — 2 колонки в один ряд
 *  - 3 шт — 3 колонки в один ряд
 *  - 4 шт — 2x2
 *  - 5–9 шт — 3 колонки, N/3 рядов (последний неполный ряд из 1–2 тайлов)
 *  - 10–15 шт — 3 колонки, 4–5 рядов
 *
 * Каждая ячейка — квадрат с ContentScale.Crop. Плитка статична (нет zoom),
 * тап по ячейке — открывает fullscreen просмотр ИМЕННО этой картинки.
 *
 * Метадата (время + статус) рисуется оверлеем в правом нижнем углу последней
 * картинки, аналогично одиночному IMAGE.
 */
@Composable
private fun AlbumBubble(
    messages: List<Message>,
    isOutgoing: Boolean,
    onDelete: ((Message) -> Unit)? = null,
    /** Имя автора альбома — показывается над плиткой в групповых чатах. */
    senderName: String? = null,
) {
    if (messages.isEmpty()) return
    val count = messages.size
    val columns = when {
        count <= 2 -> count
        count == 4 -> 2
        count == 3 -> 3
        else -> 3
    }
    val albumWidth = 260.dp
    val gap = 2.dp
    val tileSize = (albumWidth - gap * (columns - 1)) / columns

    val last = messages.last()
    val context = androidx.compose.ui.platform.LocalContext.current

    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
    // Режим множественного выбора — включается долгим нажатием на плитку
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedIds.clear()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            // Внешний Column — содержит подпись автора (для групп) и сами плитки.
            // Внутренний Column ниже — это собственно «карточка» альбома: clip,
            // longPress, действия с альбомом. Делим, чтобы текст имени не попадал
            // под clip и не зависел от longPressActionable плиток.
            Column(modifier = Modifier.width(albumWidth)) {
                if (senderName != null && !isOutgoing) {
                    GroupSenderLabel(
                        name = senderName,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .longPressActionable(
                        onLongPress = { offset ->
                            // Если в selection-режиме — пустое долгое нажатие не открывает меню
                            if (!selectionMode) {
                                menuOffset = offset.toIntOffset()
                                menuVisible = true
                            }
                        },
                    ),
            ) {
                // Панель действий в selection-режиме: крестик + счётчик + «Удалить»
                if (selectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = exitSelection,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Отмена",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Выбрано: ${selectedIds.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (onDelete != null && selectedIds.isNotEmpty()) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    val toDelete = selectedIds.toList()
                                    exitSelection()
                                    messages.filter { it.id in toDelete }.forEach { onDelete(it) }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp, vertical = 4.dp,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Удалить",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                // Разбиваем на ряды
                messages.chunked(columns).forEachIndexed { rowIndex, rowMessages ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowMessages.forEachIndexed { colIndex, msg ->
                            val globalIndex = rowIndex * columns + colIndex
                            val isTileSelected = msg.id in selectedIds
                            val data = remember(msg.id) {
                                com.secure.messenger.utils.ImageCodec.decode(msg.content)
                            }
                            Box(
                                modifier = Modifier
                                    .size(tileSize)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .pointerInput(msg.id, selectionMode) {
                                        detectTapGestures(
                                            onTap = {
                                                if (selectionMode) {
                                                    if (isTileSelected) selectedIds.remove(msg.id)
                                                    else selectedIds.add(msg.id)
                                                } else {
                                                    fullscreenIndex = globalIndex
                                                }
                                            },
                                            onLongPress = { _ ->
                                                // Вход в режим выбора только если есть право удаления
                                                if (onDelete != null) {
                                                    selectionMode = true
                                                    if (msg.id !in selectedIds) selectedIds.add(msg.id)
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (data != null) {
                                    val tilePx = with(androidx.compose.ui.platform.LocalDensity.current) {
                                        tileSize.toPx().toInt().coerceAtLeast(1)
                                    }
                                    val tileRequest = remember(msg.id, tilePx) {
                                        coil.request.ImageRequest.Builder(context)
                                            .data(data.bytes)
                                            .size(tilePx, tilePx)
                                            .memoryCacheKey("album-${msg.id}-$tilePx")
                                            .build()
                                    }
                                    coil.compose.AsyncImage(
                                        model = tileRequest,
                                        contentDescription = "Картинка альбома",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp),
                                    )
                                }

                                // Мета-оверлей только на последнем тайле (когда не в режиме выбора)
                                if (globalIndex == count - 1 && !selectionMode) {
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
                                            text = formatMessageTime(last.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                        )
                                        if (isOutgoing) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            MessageStatusIcon(
                                                status = last.status,
                                                metaColor = Color.White.copy(alpha = 0.85f),
                                            )
                                        }
                                    }
                                }

                                // Чекбокс и затемнение в режиме выбора
                                if (selectionMode) {
                                    if (isTileSelected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.35f)),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isTileSelected) MaterialTheme.colorScheme.primary
                                                else Color.Black.copy(alpha = 0.45f)
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isTileSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (colIndex < rowMessages.size - 1) {
                                Spacer(modifier = Modifier.width(gap))
                            }
                        }
                    }
                    if (rowIndex < messages.chunked(columns).size - 1) {
                        Spacer(modifier = Modifier.height(gap))
                    }
                }
            }

            // Контекстное меню альбома целиком — вызов долгим нажатием на пустой
            // области/рамке альбома (где нет конкретной плитки).
            ActionMenu(
                visible = menuVisible,
                anchorOffset = menuOffset,
                onDismiss = { menuVisible = false },
                actions = buildList {
                    add(
                        ActionMenuItem(
                            label = "Сохранить в галерею",
                            icon = Icons.Default.Download,
                            onClick = {
                                var saved = 0
                                messages.forEach { msg ->
                                    val data = com.secure.messenger.utils.ImageCodec.decode(msg.content)
                                    if (data != null) {
                                        if (com.secure.messenger.utils.ImageSaver.save(
                                                context, data.bytes, data.mime,
                                            )) saved++
                                    }
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    "Сохранено: $saved из ${messages.size}",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    )
                    if (onDelete != null) {
                        add(
                            ActionMenuItem(
                                label = "Удалить альбом",
                                icon = Icons.Default.Delete,
                                danger = true,
                                onClick = {
                                    messages.forEach { onDelete(it) }
                                },
                            )
                        )
                    }
                },
            )
            }   // ColumnExt (sender label + плитки)
        }       // Box
    }           // Row

    fullscreenIndex?.let { idx ->
        val msg = messages.getOrNull(idx) ?: return@let
        val data = remember(msg.id) { com.secure.messenger.utils.ImageCodec.decode(msg.content) }
        if (data != null) {
            FullscreenImageViewer(
                imageBytes = data.bytes,
                onDismiss = { fullscreenIndex = null },
            )
        } else {
            fullscreenIndex = null
        }
    }
}

// ── Плашка прогресса отправки альбома ───────────────────────────────────────

/**
 * Компактная плашка «Отправка 3 / 15…» с линейным прогресс-баром. Показывается
 * над инпутом пока идёт отправка альбома из множества картинок.
 */
@Composable
private fun AlbumProgressBanner(
    sent: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Отправка $sent / $total",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (total > 0) sent.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
            )
        }
    }
}

// ── Красивый Snackbar для чата (ошибки записи, отправки, и т.п.) ─────────────

/**
 * Компактная плашка-уведомление. Иконка подбирается автоматически по
 * префиксу сообщения: «Ошибка…» / «Не удалось…» — красный, всё остальное —
 * нейтральный info-стиль. Показывается над инпут-баром.
 */
@Composable
private fun ChatSnackbar(message: String) {
    val isError = message.startsWith("Ошибка", ignoreCase = true) ||
            message.startsWith("Не удалось", ignoreCase = true) ||
            message.contains("пуста", ignoreCase = true)

    val bg = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.inverseSurface
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.inverseOnSurface
    val iconColor = if (isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val icon = if (isError) Icons.Default.Close else Icons.Default.Info

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 460.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Заглушка для битой/недоступной картинки ──────────────────────────────────

/**
 * Плашка-заглушка вместо красного текста об ошибке. Показывается:
 *  - когда ImageCodec.decode не смог распарсить payload
 *  - когда Coil не смог декодировать байты (битый формат)
 * Стиль — нейтральная карточка с иконкой «сломанного фото» по центру.
 */
@Composable
private fun ImagePlaceholder(
    modifier: Modifier = Modifier,
    label: String,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
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
    modifier: Modifier = Modifier,
    text: String,
    editingMessage: Message?,
    replyingTo: Message?,
    showEmojiPicker: Boolean,
    isRecording: Boolean,
    recordingSeconds: Int,
    recordingWaveform: IntArray,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onToggleEmoji: () -> Unit = {},
    onEmojiPick: (String) -> Unit = {},
    onBackspace: () -> Unit = {},
    onAttachImage: () -> Unit = {},
    onContentReceived: (android.net.Uri) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: (cancel: Boolean) -> Unit = {},
) {
    // На Android 12+ под инпутом живёт real-blur, 0.88 даёт «матовое стекло».
    // На более ранних Android блюра нет, полупрозрачная панель смотрится
    // тускло и буквы сообщений просвечивают сквозь инпут — поднимаем альфу
    // до 0.97, чтобы поле ввода оставалось чётким и собранным.
    val inputAlpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.88f else 0.97f
    Surface(
        modifier = modifier,
        shadowElevation = 8.dp,
        // Полупрозрачная подложка — сквозь инпут просвечивают обои чата,
        // как у плашки «Здесь пока нет сообщений».
        color = MaterialTheme.colorScheme.surface.copy(alpha = inputAlpha),
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

            // Row не убираем при isRecording — иначе кнопка микрофона уходит из
            // композиции, pointerInput умирает и волны вокруг пальца негде
            // рисовать. Вместо этого подменяем содержимое инпута на индикатор
            // записи (красный кружок + таймер + «Отпустите для отправки»),
            // а сам микрофон остаётся на своём месте — палец на нём, волны
            // вокруг него рисуются.
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
                        // Когда панель эмоджи открыта — нельзя применять imePadding
                        // (keyboard всё ещё мог быть в процессе закрытия и даёт
                        // большой отступ между Row и эмоджи-гридом). Паддинги
                        // обрабатывает сама панель через navigationBarsPadding().
                        if (showEmojiPicker) Modifier
                        else Modifier.navigationBarsPadding().imePadding()
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Кнопка эмоджи слева — во время записи скрываем
                if (!isRecording) {
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
                }

                if (isRecording) {
                    // Полоска записи на месте инпута: красный «дышащий» кружок +
                    // таймер + живая waveform-волна, реагирующая на громкость.
                    RecordingInlineIndicator(
                        seconds = recordingSeconds,
                        amplitudes = recordingWaveform,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 4.dp),
                    )
                } else {
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
                }

                // Кнопка отправки (если есть текст) или микрофон-удержание (если пусто).
                // Обе кнопки в покое 46dp — ряд не «прыгает» при вводе первой буквы
                // (send ↔ mic-idle оба 46dp). Микрофон расширяется до 72dp ТОЛЬКО
                // во время записи — чтобы дать место пульсирующим волнам.
                if (text.isNotEmpty() && !isRecording) {
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
                    HoldToRecordMicButton(
                        isRecording = isRecording,
                        onStart = onStartRecording,
                        onStop = { onStopRecording(false) },
                    )
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
                    onBackspace = onBackspace,
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

// ── Кнопка «удерживайте для записи» с пульсирующими волнами ──────────────────

/**
 * Кнопка микрофона: пока палец на иконке — идёт запись, вокруг кнопки
 * крутятся пульсирующие волны (как в Telegram). Отпустили палец — стоп.
 *
 * Жесты ловим через [pointerInput] + awaitFirstDown / awaitPointerEvent —
 * это надёжнее clickable + ACTION_UP, который часто терялся при свайпах.
 *
 * Анимация:
 *  - 3 концентрических круга расходятся наружу с задержками 0/300/600 мс
 *  - используется единый infiniteTransition, чтобы гарантировать совпадение фаз
 *  - кнопка чуть увеличивается (scale 1.0 → 1.08) пока запись активна
 */
@Composable
private fun HoldToRecordMicButton(
    isRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic_pulse")
    // Три волны с разными задержками — расширяются от 1.0 до 2.4 от размера
    // кнопки, прозрачность плавно уходит в 0.
    val waveSpec = androidx.compose.animation.core.infiniteRepeatable<Float>(
        animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
    )
    val wave1 by infiniteTransition.animateFloat(0f, 1f, waveSpec, label = "w1")
    val wave2 by infiniteTransition.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1500, delayMillis = 500, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "w2",
    )
    val wave3 by infiniteTransition.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1500, delayMillis = 1000, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "w3",
    )
    // Лёгкий «вдох» самой кнопки во время записи
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isRecording) 1.12f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
        ),
        label = "mic_scale",
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    // Размер контейнера: 46dp в покое (совпадает с кнопкой send — ряд не прыгает),
    // 72dp во время записи — чтобы поместились пульсирующие волны.
    val containerSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isRecording) 72.dp else 46.dp,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "mic_container_size",
    )

    Box(
        modifier = Modifier.size(containerSize),
        contentAlignment = Alignment.Center,
    ) {
        // Рисуем волны только когда идёт запись — иначе кнопка спокойная
        if (isRecording) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = size.minDimension * 0.28f // ~46dp / 2
                val maxRadius = size.minDimension * 0.5f
                fun drawWave(progress: Float) {
                    val radius = baseRadius + (maxRadius - baseRadius) * progress * 1.6f
                    val alpha = (1f - progress).coerceIn(0f, 1f) * 0.55f
                    drawCircle(
                        color = primaryColor.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                    )
                }
                drawWave(wave1)
                drawWave(wave2)
                drawWave(wave3)
            }
        }

        Box(
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onStart()
                        var allReleased = false
                        while (!allReleased) {
                            val event = awaitPointerEvent()
                            allReleased = event.changes.none { it.pressed }
                        }
                        onStop()
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

// ── Компактный индикатор записи (на месте инпута) ────────────────────────────

/**
 * Показывается на месте текстового инпута пока пользователь держит палец на
 * микрофоне. Красный «дышащий» кружок + таймер + живая waveform-волна,
 * реагирующая на громкость микрофона. Сама кнопка микрофона остаётся справа
 * в Row-е и слушает pointer-события.
 */
@Composable
private fun RecordingInlineIndicator(
    seconds: Int,
    amplitudes: IntArray,
    modifier: Modifier = Modifier,
) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec_dot_pulse")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(700),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "rec_dot_alpha",
        )
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935).copy(alpha = pulse)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatVoiceDuration(seconds),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.width(12.dp))
        // Живая waveform-волна — последние ~40 сэмплов амплитуды плывут справа
        // налево, новые приходят справа. Высота каждого бара = sqrt(amp/MAX),
        // sqrt-шкала смягчает разницу между тихим и громким голосом.
        LiveWaveform(
            amplitudes = amplitudes,
            color = accent,
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
    }
}

/**
 * Canvas-волна из вертикальных баров. Отображает последние [BAR_COUNT] значений
 * амплитуды; новый сэмпл появляется справа, старый уходит слева. Если данных
 * меньше BAR_COUNT — слева остаются «тихие» бары минимальной высоты.
 *
 * Нормализация: maxAmplitude у MediaRecorder может доходить до ~32767, но
 * обычная речь редко выходит за 8000–15000 — поэтому делим на 16000 + sqrt
 * чтобы голос «играл» по высоте, а не плющился к минимуму.
 */
@Composable
private fun LiveWaveform(
    amplitudes: IntArray,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val barCount = 40
    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val maxBarHeight = size.height
        val gap = 2.dp.toPx()
        val barWidth = ((totalWidth - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
        val minBar = maxBarHeight * 0.15f

        // Берём последние barCount значений; если меньше — добиваем нулями слева
        val visible = if (amplitudes.size >= barCount) {
            amplitudes.copyOfRange(amplitudes.size - barCount, amplitudes.size)
        } else {
            IntArray(barCount - amplitudes.size) + amplitudes
        }

        for (i in 0 until barCount) {
            val raw = visible[i].coerceAtLeast(0)
            val norm = (raw / 16_000f).coerceIn(0f, 1f)
            val scaled = kotlin.math.sqrt(norm) // более «живая» шкала
            val barHeight = (minBar + (maxBarHeight - minBar) * scaled).coerceAtMost(maxBarHeight)
            val x = i * (barWidth + gap)
            val y = (maxBarHeight - barHeight) / 2f
            // Старые бары (слева) чуть прозрачнее — даёт ощущение «уплывания»
            val alpha = 0.5f + 0.5f * (i.toFloat() / barCount)
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
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

// ── Обои чата: либо картинка из настроек, либо ничего (NONE) ────────────────

@Composable
private fun ChatWallpaper() {
    val selectedWp by com.secure.messenger.presentation.theme.ThemePreferences.wallpaper
        .collectAsStateWithLifecycle()
    val blurPercent by com.secure.messenger.presentation.theme.ThemePreferences.wallpaperBlur
        .collectAsStateWithLifecycle()
    val wpDrawable = selectedWp.drawableRes ?: return  // NONE → фон не рисуем

    // 0..100% → 0..30dp радиуса размытия. Modifier.blur работает на API 31+,
    // на более старых — без эффекта, но крашей нет.
    val blurRadius = (blurPercent * 0.30f).dp

    val extra = LocalMessengerColors.current
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(wpDrawable),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurRadius > 0.dp) Modifier.blur(blurRadius)
                    else Modifier
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(extra.chatWallpaper.copy(alpha = 0.25f)),
        )
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
