package com.secure.messenger.presentation.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.secure.messenger.BuildConfig
import com.secure.messenger.R
import com.secure.messenger.data.remote.api.SupportInfoDto
import com.secure.messenger.domain.model.Chat
import com.secure.messenger.domain.model.ChatType
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.domain.model.User
import com.secure.messenger.presentation.ui.components.ActionMenu
import com.secure.messenger.presentation.ui.components.ActionMenuItem
import com.secure.messenger.presentation.ui.components.longPressActionable
import com.secure.messenger.presentation.ui.components.toIntOffset
import com.secure.messenger.presentation.viewmodel.ChatListViewModel
import com.secure.messenger.utils.formatTimestamp
import kotlin.math.abs

// Базовый URL сервера (без /v1/) — нужен для относительных путей аватаров
private val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')

// ── Палитра цветов для инициалов аватара ─────────────────────────────────────
private val AvatarPalette = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828),
    Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFE65100),
    Color(0xFF4527A0), Color(0xFF558B2F),
)

// ── Главный экран списка чатов ────────────────────────────────────────────────

@Composable
fun ChatListScreen(
    onChatClick: (chatId: String) -> Unit,
    onNewChatClick: () -> Unit = {},
    onCreateGroupClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val typingChats by viewModel.typingChats.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isInitialLoading by viewModel.isInitialLoading.collectAsStateWithLifecycle()

    // Задержка перед показом "Нет соединения" — убирает мигание при первом запуске.
    // isConnected стартует как false пока WS устанавливается (~1-2с после запуска).
    var showDisconnected by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            showDisconnected = false
        } else {
            delay(3_000)
            showDisconnected = true
        }
    }

    var showSupportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
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
            // ── OneUI-стиль: большой заголовок ─────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Аватар пользователя слева — тап → профиль
                        Box(modifier = Modifier.clickable { onProfileClick() }) {
                            AvatarImage(
                                url = currentUser?.avatarUrl,
                                name = currentUser?.displayName ?: "?",
                                size = 52,
                            )
                            // Индикатор подключения
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isConnected -> Color(0xFF4CAF50)
                                            showDisconnected -> Color(0xFFFF9800)
                                            else -> Color(0xFF9E9E9E)
                                        }
                                    )
                                    .align(Alignment.BottomEnd),
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Имя пользователя + статус сети — тап → профиль.
                        // Пока currentUser ещё не подгрузился, рисуем скелетон-плашки
                        // вместо «прыгающего» текста заглушки.
                        Column(modifier = Modifier.weight(1f).clickable { onProfileClick() }) {
                            if (currentUser == null) {
                                com.secure.messenger.presentation.ui.components.SkeletonBox(
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(24.dp),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                com.secure.messenger.presentation.ui.components.SkeletonBox(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .height(14.dp),
                                )
                            } else {
                                val title = when {
                                    currentUser?.displayName?.isNotEmpty() == true -> currentUser!!.displayName
                                    currentUser?.username?.isNotEmpty() == true -> "@${currentUser!!.username}"
                                    else -> "Чаты"
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        isConnected -> "В сети"
                                        showDisconnected -> "Нет соединения"
                                        else -> "Подключение..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isConnected) Color(0xFF4CAF50)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }

                        // Иконка «Поддержать автора»
                        IconButton(onClick = { showSupportDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Поддержать автора",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // ── Список чатов ───────────────────────────────────────────────
            if (chats.isEmpty() && isInitialLoading) {
                // Первая загрузка: показываем 7 скелетон-строк вместо «Нет чатов»,
                // чтобы не мелькала заглушка пустого состояния.
                ChatListSkeleton(modifier = Modifier.weight(1f))
            } else if (chats.isEmpty()) {
                EmptyChatList(modifier = Modifier.weight(1f))
            } else {
                val activeCallChats by viewModel.activeCallChats.collectAsStateWithLifecycle()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            currentUserId = currentUser?.id,
                            isTyping = chat.id in typingChats,
                            hasActiveGroupCall = chat.id in activeCallChats,
                            onClick = {
                                // Eager prefetch: запускаем fetchMessages в момент
                                // тапа, не дожидаясь монтирования ChatScreen. Пока
                                // Compose навигирует, сетевой запрос уже летит —
                                // и к моменту когда ChatViewModel.init вызовет тот
                                // же fetchMessages, кеш либо уже наполнен, либо
                                // запрос на полпути. Видим сообщения почти сразу.
                                viewModel.prefetchChat(chat.id)
                                onChatClick(chat.id)
                            },
                            onDeleteChat = { viewModel.deleteChat(chat.id) },
                            onBlockUser = {
                                chat.otherUserId?.let { userId -> viewModel.blockUser(userId) }
                            },
                        )
                    }
                }
            }
        }

        // FAB + меню с выбором «Новый чат» / «Новая группа». Клик открывает
        // dropdown под кнопкой; долгий тап сразу ведёт на Контакты (быстрый
        // путь к новому direct-чату) без всплывающего меню.
        var fabMenuExpanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            FloatingActionButton(
                onClick = { fabMenuExpanded = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Написать")
            }
            DropdownMenu(
                expanded = fabMenuExpanded,
                onDismissRequest = { fabMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Новый чат") },
                    onClick = {
                        fabMenuExpanded = false
                        onNewChatClick()
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                    text = { Text("Новая группа") },
                    onClick = {
                        fabMenuExpanded = false
                        onCreateGroupClick()
                    },
                )
            }
        }

        // Snackbar для ошибок
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Диалог «Поддержать автора»
    if (showSupportDialog) {
        SupportAuthorDialog(onDismiss = { showSupportDialog = false })
    }
}

// ── Диалог «Поддержать автора» ───────────────────────────────────────────────

@Composable
private fun SupportAuthorDialog(onDismiss: () -> Unit) {
    var supportInfo by remember { mutableStateOf<SupportInfoDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val viewModel: ChatListViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        val result = runCatching { viewModel.loadSupportInfo() }
        result.onSuccess { info ->
            supportInfo = info
            isLoading = false
        }.onFailure { e ->
            error = e.message
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(supportInfo?.title ?: "Поддержать автора") },
        text = {
            when {
                isLoading -> Text("Загрузка...")
                error != null -> Text("Не удалось загрузить: $error")
                else -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Column {
                        supportInfo?.message?.takeIf { it.isNotBlank() }?.let { msg ->
                            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
                        }
                        val links = remember(supportInfo?.links) {
                            runCatching {
                                val json = org.json.JSONArray(supportInfo?.links ?: "[]")
                                (0 until json.length()).map { i ->
                                    val obj = json.getJSONObject(i)
                                    obj.optString("title", "") to obj.optString("url", "")
                                }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
                            }.getOrDefault(emptyList())
                        }
                        if (links.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            links.forEach { (title, url) ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            context.startActivity(
                                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

// ── Строка одного чата (в карточке) ──────────────────────────────────────────

@Composable
private fun ChatRow(
    chat: Chat,
    currentUserId: String?,
    isTyping: Boolean,
    hasActiveGroupCall: Boolean = false,
    onClick: () -> Unit,
    onDeleteChat: () -> Unit,
    onBlockUser: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .longPressActionable(
                        onClick = onClick,
                        onLongPress = { offset ->
                            menuOffset = offset.toIntOffset()
                            menuVisible = true
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Аватар с индикатором онлайн-статуса / печатает.
                // Источник правды — isOtherOnline из ChatRepository.observeChats()
                // (формируется через JOIN с users в SQL DAO). chat.members в Flow
                // из Room всегда пустой — это поле остаётся для совместимости.
                val isOtherUserOnline = chat.isOtherOnline
                Box {
                    AvatarImage(url = chat.avatarUrl, name = chat.title, size = 52)
                    // Бейдж группы — иконка с двумя силуэтами в правом нижнем
                    // углу. Показываем для GROUP всегда, даже если есть pin
                    // (тип чата важнее визуально). Pin для группы передаём
                    // через сортировку и не показываем на аватаре отдельно.
                    if (chat.type == ChatType.GROUP) {
                        // Если в группе сейчас идёт звонок — заменяем иконку
                        // силуэтов зелёной телефонной трубкой (пульсирующей),
                        // чтобы юзер сразу видел «здесь звонок» в списке чатов.
                        // Анимацию rememberInfiniteTransition запускаем ТОЛЬКО для
                        // группы с активным звонком: каждый infiniteTransition
                        // регистрирует frame callback ~60fps, и для списка из
                        // 10+ групп без звонка это даёт ощутимый jank при заходе
                        // на экран (особенно при возврате из чата).
                        if (hasActiveGroupCall) {
                            val callPulse by rememberInfiniteTransition(label = "call-pulse")
                                .animateFloat(
                                    initialValue = 0.85f,
                                    targetValue = 1.15f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse,
                                    ),
                                    label = "pulse",
                                )
                            Box(
                                modifier = Modifier
                                    .size((20 * callPulse).dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                                    .align(Alignment.BottomEnd),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Идёт звонок",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.BottomEnd),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = "Группа",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    } else if (chat.isPinned && !isTyping) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .align(Alignment.BottomEnd),
                        ) { Text(text = "📌", fontSize = 10.sp) }
                    } else if (isTyping && chat.type == ChatType.DIRECT) {
                        // Пульсирующий синий индикатор «печатает»
                        val pulse by rememberInfiniteTransition(label = "typing")
                            .animateFloat(
                                initialValue = 0.4f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(500, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "pulse",
                            )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulse))
                                .align(Alignment.BottomEnd),
                        )
                    } else if (isOtherUserOnline && chat.type == ChatType.DIRECT) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chat.isMuted) Text(text = "🔇", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                        Text(
                            text = formatTimestamp(chat.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = if (isTyping && chat.type == ChatType.DIRECT) {
                                "печатает..."
                            } else {
                                chat.lastMessage?.let { msg ->
                                    val raw = when (msg.type) {
                                        MessageType.TEXT   -> msg.content
                                        MessageType.IMAGE  -> "📷 Фото"
                                        MessageType.VIDEO  -> "🎬 Видео"
                                        MessageType.AUDIO  -> "🎤 Аудиосообщение"
                                        MessageType.FILE   -> "📎 Файл"
                                        MessageType.SYSTEM -> if (msg.senderId == "00000000-0000-0000-0000-000000000001") {
                                            msg.content
                                        } else {
                                            displaySystemPreviewText(
                                                rawText = msg.content,
                                                isOwnMessage = msg.senderId == currentUserId,
                                            )
                                        }
                                    }
                                    // Если decryptedContent — плашка (sender-keys
                                    // ещё не загружены на cold start), показываем
                                    // нейтральный плейсхолдер вместо «не удалось».
                                    when (raw) {
                                        "[Не удалось расшифровать]",
                                        "[Групповые чаты не поддерживаются]" -> "🔒 Зашифрованное сообщение"
                                        else -> raw
                                    }
                                } ?: "Нет сообщений"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isTyping && chat.type == ChatType.DIRECT)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chat.unreadCount > 0) {
                            UnreadBadge(
                                count = chat.unreadCount,
                                muted = chat.isMuted,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // Контекстное меню при долгом нажатии — появляется в точке касания
            ActionMenu(
                visible = menuVisible,
                anchorOffset = menuOffset,
                onDismiss = { menuVisible = false },
                actions = buildList {
                    add(
                        ActionMenuItem(
                            label = "Удалить чат",
                            icon = Icons.Default.Delete,
                            danger = true,
                            onClick = onDeleteChat,
                        )
                    )
                    if (chat.type == ChatType.DIRECT && chat.otherUserId != null) {
                        add(
                            ActionMenuItem(
                                label = "Заблокировать",
                                icon = Icons.Default.Block,
                                danger = true,
                                onClick = onBlockUser,
                            )
                        )
                    }
                },
            )
        }
    }
}

// ── Бейдж непрочитанных сообщений ────────────────────────────────────────────

@Composable
private fun UnreadBadge(count: Int, muted: Boolean, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Скелетон-плейсхолдер списка чатов на время первой загрузки ───────────────

@Composable
private fun ChatListSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Семь строк-скелетонов имитируют примерное содержимое списка
        repeat(7) {
            ChatRowSkeleton()
        }
    }
}

@Composable
private fun ChatRowSkeleton() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Кружок-аватар
            com.secure.messenger.presentation.ui.components.SkeletonBox(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Имя
                com.secure.messenger.presentation.ui.components.SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Превью последнего сообщения
                com.secure.messenger.presentation.ui.components.SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(12.dp),
                )
            }
        }
    }
}

// ── Пустое состояние ──────────────────────────────────────────────────────────

@Composable
private fun EmptyChatList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Нет чатов",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Нажмите ✏ чтобы начать переписку",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Перерасчёт текста системного сообщения для превью в списке чатов.
 * Дублирует логику ChatScreen.displaySystemMessageText. Префикс
 * «Исходящий» / «Входящий» добавляется в зависимости от того, кто звонил.
 */
private fun displaySystemPreviewText(rawText: String, isOwnMessage: Boolean): String {
    return when (rawText) {
        "Пропущенный Звонок"      -> if (isOwnMessage) "Звонок без ответа" else "Пропущенный звонок"
        "Пропущенный Видеозвонок" -> if (isOwnMessage) "Видеозвонок без ответа" else "Пропущенный видеозвонок"
        "Звонок отклонён"         -> if (isOwnMessage) "Исходящий звонок отклонён" else "Входящий звонок отклонён"
        "Видеозвонок отклонён"    -> if (isOwnMessage) "Исходящий видеозвонок отклонён" else "Входящий видеозвонок отклонён"
        else -> {
            val direction = if (isOwnMessage) "Исходящий" else "Входящий"
            when {
                rawText.startsWith("Звонок · ")      -> "$direction звонок · ${rawText.removePrefix("Звонок · ")}"
                rawText.startsWith("Видеозвонок · ") -> "$direction видеозвонок · ${rawText.removePrefix("Видеозвонок · ")}"
                else -> rawText
            }
        }
    }
}

// ── Аватар (публичный — используется в других экранах) ────────────────────────

@Composable
fun AvatarImage(url: String?, name: String, size: Int) {
    val initials = name.split(" ").take(2)
        .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
    val bgColor = remember(name) { AvatarPalette[abs(name.hashCode()) % AvatarPalette.size] }

    val resolvedUrl = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        url.startsWith("/") -> "$serverRoot$url"
        else -> url
    }

    if (resolvedUrl != null) {
        AsyncImage(
            model = resolvedUrl,
            contentDescription = name,
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        // Заглушка — логотип медведя на цветном фоне
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size.dp).clip(CircleShape).background(bgColor),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.avatar_placeholder),
                contentDescription = name,
                modifier = Modifier
                    .size((size * 0.82f).dp)
                    .clip(CircleShape),
            )
        }
    }
}
