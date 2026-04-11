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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
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
    onProfileClick: () -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val typingChats by viewModel.typingChats.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

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

                        // Имя пользователя + статус сети — тап → профиль
                        Column(modifier = Modifier.weight(1f).clickable { onProfileClick() }) {
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
            if (chats.isEmpty()) {
                EmptyChatList(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            isTyping = chat.id in typingChats,
                            onClick = { onChatClick(chat.id) },
                            onDeleteChat = { viewModel.deleteChat(chat.id) },
                            onBlockUser = {
                                chat.otherUserId?.let { userId -> viewModel.blockUser(userId) }
                            },
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onNewChatClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Default.Edit, contentDescription = "Новый чат")
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
    isTyping: Boolean,
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
                // Аватар с индикатором онлайн-статуса / печатает
                val isOtherUserOnline = chat.members.firstOrNull { it.id == chat.otherUserId }?.isOnline == true
                Box {
                    AvatarImage(url = chat.avatarUrl, name = chat.title, size = 52)
                    if (chat.isPinned && !isTyping) {
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
                                    when (msg.type) {
                                        MessageType.TEXT   -> msg.content
                                        MessageType.IMAGE  -> "📷 Фото"
                                        MessageType.VIDEO  -> "🎬 Видео"
                                        MessageType.AUDIO  -> "🎤 Аудиосообщение"
                                        MessageType.FILE   -> "📎 Файл"
                                        MessageType.SYSTEM -> msg.content
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

// ── Аватар (публичный — используется в других экранах) ────────────────────────

@Composable
fun AvatarImage(url: String?, name: String, size: Int) {
    val initials = name.split(" ").take(2)
        .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
    val bgColor = remember(name) { AvatarPalette[abs(name.hashCode()) % AvatarPalette.size] }

    val resolvedUrl = when {
        url == null -> null
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
