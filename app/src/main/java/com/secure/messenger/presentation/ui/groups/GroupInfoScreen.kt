package com.secure.messenger.presentation.ui.groups

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.secure.messenger.BuildConfig
import com.secure.messenger.presentation.ui.components.AvatarCropDialog
import com.secure.messenger.presentation.ui.components.collapsibleAvatarDismissOnSwipe
import java.io.ByteArrayOutputStream
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.domain.model.ChatRole
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.model.canManage
import com.secure.messenger.presentation.viewmodel.GroupInfoViewModel

/**
 * Минимальная клиентская версия, поддерживающая групповой E2E. Если у
 * участника [User.appVersionCode] меньше — он не сможет читать sender-keys,
 * и его клиент видит сообщения как «не удалось расшифровать». Значение 0
 * — пользователь ни разу не отчитался о версии, считаем «неизвестно» и
 * НЕ помечаем устаревшим (был бы false-positive у новых аккаунтов).
 */
private const val MIN_GROUP_VERSION_CODE = 68

private fun User.isOutdated(): Boolean =
    appVersionCode in 1 until MIN_GROUP_VERSION_CODE

/**
 * Экран информации о группе: название, список участников с ролями,
 * действия (добавить участника / кик / смена роли / выйти).
 *
 * Видимость действий зависит от [myRole] в текущем чате:
 *  - CREATOR: добавить, кик любого (кроме себя), сменить роль, выйти (каскадная передача).
 *  - ADMIN:   добавить, кик любого кроме CREATOR и себя, выйти (без передачи).
 *  - MEMBER:  только выйти.
 */
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onGroupCall: (chatId: String, isVideo: Boolean) -> Unit = { _, _ -> },
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val addable by viewModel.addableContacts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.leftGroup) {
        if (uiState.leftGroup) onLeft()
    }

    var showEditTitle by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAvatarFullscreen by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }

    val context = LocalContext.current
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri),
                ) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()
        if (bmp != null) cropSource = bmp
    }

    val myRole = uiState.chat?.myRole
    val chat = uiState.chat

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Свайп-вверх в любой точке экрана сворачивает раскрытый
                // аватар. Применяется ДО verticalScroll, чтобы успеть
                // перехватить жест до скролла.
                .collapsibleAvatarDismissOnSwipe(
                    expanded = showAvatarFullscreen,
                    onCollapse = { showAvatarFullscreen = false },
                )
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Шапка: стрелка-«назад» + крупный заголовок в одной строке.
            //    Размер заголовка адаптивный по ширине экрана: на маленьких
            //    устройствах (≤360dp) — 22sp, на средних — 26sp, на широких —
            //    30sp. maxLines=1 + Ellipsis гарантирует, что текст не съедет
            //    на вторую строку и останется на уровне стрелки.
            val configuration = LocalConfiguration.current
            val titleFontSize = when {
                configuration.screenWidthDp <= 360 -> 22.sp
                configuration.screenWidthDp <= 420 -> 26.sp
                else                               -> 30.sp
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
            ) {
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
                        text = "Информация о группе",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleFontSize,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .padding(start = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            if (uiState.isLoading && chat == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (chat == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Группа не найдена", color = MaterialTheme.colorScheme.error)
                }
                return@Column
            }

            // ── Плашка-предупреждение об устаревших участниках ─────────────
            val outdatedMembers = uiState.members.filter { it.isOutdated() }
            if (outdatedMembers.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Часть участников на старой версии",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "${outdatedMembers.size} из ${uiState.members.size} участников ещё не обновили приложение и не могут читать сообщения в группе. Попросите их обновиться.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // ── Большая карточка группы ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Аватар: тап = inline-раскрытие в стиле Telegram (картинка
                // расширяется до 50% экрана и заезжает поверх status-bar; back
                // или свайп вниз — свернуть). Для CREATOR/ADMIN рядом маленькая
                // кнопка «камера» — она ведёт в галерею → AvatarCropDialog →
                // upload (без неё каждый клик запускал бы галерею, что сильно
                // раздражает участников которые просто хотели посмотреть аватар).
                GroupAvatar(
                    title = chat.title,
                    avatarUrl = chat.avatarUrl,
                    canEdit = myRole.canManage(),
                    expanded = showAvatarFullscreen,
                    onExpandedChange = { showAvatarFullscreen = it },
                    onEditClick = { pickAvatarLauncher.launch("image/*") },
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (myRole.canManage()) {
                        IconButton(onClick = { showEditTitle = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Изменить название",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    // Состав читается из локального кеша (chat_members JOIN
                    // users), поэтому при первом открытии после сохранённой
                    // сессии цифра уже есть. Спиннер не нужен — данные
                    // мгновенные. Кейс «совсем нет данных» (логин на чистом
                    // устройстве) попадает на короткое окно перед syncChats.
                    text = if (uiState.members.isEmpty()) {
                        "загрузка участников…"
                    } else {
                        "${uiState.members.size} участник(а)"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Кнопки группового звонка: показываем для GROUP с 2-4
                // участниками (mesh-лимит). Две круглые таблетки рядом —
                // «Аудио» и «Видео», с иконкой и подписью. Стилистика
                // совпадает с подобными action-кнопками в Telegram /
                // WhatsApp профиле контакта.
                val canCall = uiState.members.size in 2..4
                if (canCall) {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GroupCallActionButton(
                            icon = Icons.Default.Call,
                            label = "Аудио",
                            onClick = { onGroupCall(chat.id, false) },
                            modifier = Modifier.weight(1f),
                        )
                        GroupCallActionButton(
                            icon = Icons.Default.Videocam,
                            label = "Видео",
                            onClick = { onGroupCall(chat.id, true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ── Уведомления ──────────────────────────────────────────────────
            // Локальный мьют: пуши приходят, но шторку клиент не показывает.
            // Серверу про мьют не сообщаем — иначе бы пришлось отдельной таблицей
            // хранить per-user mute state (пока избыточно).
            if (chat != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (chat.isMuted) Icons.Filled.NotificationsOff
                                else Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Уведомления",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = !chat.isMuted,
                            onCheckedChange = { enabled ->
                                viewModel.setMuted(!enabled)
                            },
                        )
                    }
                }
            }

            // ── Список участников ────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    if (myRole.canManage()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddDialog = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Добавить участника",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    uiState.members.forEach { member ->
                        MemberRow(
                            member = member,
                            isMe = member.id == uiState.myUserId,
                            myRole = myRole,
                            onMakeAdmin = { viewModel.changeRole(member.id, ChatRole.ADMIN) },
                            onDemoteToMember = { viewModel.changeRole(member.id, ChatRole.MEMBER) },
                            onKick = { viewModel.removeMember(member.id) },
                            onTransferOwnership = { viewModel.transferOwnership(member.id) },
                        )
                    }

                    // Состав теперь читается из локального кеша (chat_members),
                    // спиннер обычно не нужен — данные есть мгновенно. Только
                    // если кеш совсем пуст (свежий логин до syncChats) показываем
                    // тонкий индикатор, чтобы экран не выглядел сломанным.
                    if (uiState.members.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Выйти из группы ──────────────────────────────────────────────
            Button(
                onClick = { showLeaveConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Выйти из группы")
            }

            // ── Удалить группу (только CREATOR) ──────────────────────────────
            // CREATOR может удалить группу полностью у всех участников.
            // Действие необратимо — сообщения и ключи будут стёрты.
            if (myRole == ChatRole.CREATOR) {
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить группу")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Диалоги ──────────────────────────────────────────────────────────
    if (showEditTitle && chat != null) {
        EditTitleDialog(
            initial = chat.title,
            onDismiss = { showEditTitle = false },
            onConfirm = {
                viewModel.updateTitle(it)
                showEditTitle = false
            },
        )
    }
    if (showAddDialog) {
        AddMemberDialog(
            candidates = addable,
            onDismiss = { showAddDialog = false },
            onPick = {
                viewModel.addMember(it)
                showAddDialog = false
            },
        )
    }
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Выйти из группы?") },
            text = {
                Text(
                    if (myRole == ChatRole.CREATOR) {
                        "Вы создатель группы. При выходе права владельца " +
                        "автоматически передадутся случайному участнику. Если в " +
                        "группе больше никого — она будет удалена."
                    } else {
                        "Вы перестанете получать сообщения из этой группы. " +
                        "Чтобы вернуться, вас должен пригласить один из администраторов."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    viewModel.leaveGroup()
                }) { Text("Выйти", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Отмена") }
            },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить группу?") },
            text = {
                Text(
                    "Группа исчезнет у всех участников. История сообщений и " +
                    "ключи шифрования будут безвозвратно удалены. " +
                    "Это действие нельзя отменить.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteGroup()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            },
        )
    }

    cropSource?.let { source ->
        AvatarCropDialog(
            sourceBitmap = source,
            onConfirm = { cropped ->
                viewModel.updateAvatar(cropped.toJpegBytes())
                cropSource = null
            },
            onCancel = { cropSource = null },
        )
    }

}

/**
 * Аватар группы. По тапу разворачивается inline — высота анимируется до
 * половины экрана, ширина — до полной, форма — из круга в прямоугольник.
 * Свайп вниз / back / повторный тап — свернуть.
 *
 * [onEditClick] показывается маленькой иконкой-камерой в правом нижнем
 * углу только для CREATOR/ADMIN и только в свёрнутом состоянии — в
 * раскрытом она бы выглядела чужеродно поверх большого фото.
 */
@Composable
private fun GroupAvatar(
    title: String,
    avatarUrl: String?,
    canEdit: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
) {
    val resolved = remember(avatarUrl) {
        avatarUrl?.let { url ->
            val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')
            when {
                url.startsWith("http") -> url
                url.startsWith("/")    -> "$serverRoot$url"
                else                   -> url
            }
        }
    }

    // Карточка-аватар сама определяет свой размер; камера-кнопка живёт
    // отдельным Box рядом и видна только при collapsed.
    Box(contentAlignment = Alignment.BottomEnd) {
        com.secure.messenger.presentation.ui.components.CollapsibleAvatarHeader(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        ) { contentModifier ->
            if (resolved != null) {
                coil.compose.AsyncImage(
                    model = resolved,
                    contentDescription = "Аватар группы",
                    contentScale = ContentScale.Crop,
                    modifier = contentModifier,
                )
            } else {
                Box(
                    modifier = contentModifier.background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.firstOrNull()?.toString()?.uppercase() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 32.sp,
                    )
                }
            }
        }
        if (canEdit && !expanded) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onEditClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Изменить аватар",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Сжимает Bitmap в JPEG-байты. Качество 85 — компромисс размер/качество. */
private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

/**
 * Кнопка-таблетка для действий «Аудио» / «Видео» в шапке группы.
 * Округлый фон в primary-контейнере + иконка + подпись. Стилистически
 * согласуется с action-кнопками в Telegram-профиле.
 */
@Composable
private fun GroupCallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.height(64.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: User,
    isMe: Boolean,
    myRole: ChatRole?,
    onMakeAdmin: () -> Unit,
    onDemoteToMember: () -> Unit,
    onKick: () -> Unit,
    onTransferOwnership: () -> Unit,
) {
    // Меню должно лежать ВНУТРИ ячейки — иначе DropdownMenu не находит якорь
    // (IconButton) и Compose выкидывает его в верх списка/экрана. Поэтому
    // храним его state локально и рендерим Box-обёрткой вокруг IconButton.
    var menuVisible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Единый стиль аватара: фото с сервера если есть, иначе дефолт-медведь
        // на палитро-цветном фоне (как в списке чатов). Поверх — зелёная
        // точка-индикатор «онлайн» (если участник в сети).
        Box {
            com.secure.messenger.presentation.ui.chat.AvatarImage(
                url = member.avatarUrl,
                name = member.displayName,
                size = 40,
            )
            if (member.isOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isMe) "${member.displayName} (вы)" else member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (member.isOutdated()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Использует старую версию приложения",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            val roleLabel = when (member.groupRole) {
                ChatRole.CREATOR -> "создатель"
                ChatRole.ADMIN -> "администратор"
                else -> null
            }
            if (roleLabel != null) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (member.isOutdated()) {
                Text(
                    text = "не может читать сообщения — обновите приложение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Меню — показываем когда есть хоть одно действие. ADMIN не может
        // ничего делать с другим ADMIN'ом или с CREATOR'ом — кнопка скрыта.
        val canActOnMember = !isMe && when {
            member.groupRole == ChatRole.CREATOR -> false
            myRole == ChatRole.CREATOR -> true
            myRole == ChatRole.ADMIN && member.groupRole == ChatRole.ADMIN -> false
            myRole == ChatRole.ADMIN -> true
            else -> false
        }
        if (canActOnMember) {
            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                }
                MemberActionsMenu(
                    expanded = menuVisible,
                    member = member,
                    myRole = myRole,
                    onDismiss = { menuVisible = false },
                    onMakeAdmin = {
                        menuVisible = false
                        onMakeAdmin()
                    },
                    onDemoteToMember = {
                        menuVisible = false
                        onDemoteToMember()
                    },
                    onKick = {
                        menuVisible = false
                        onKick()
                    },
                    onTransferOwnership = {
                        menuVisible = false
                        onTransferOwnership()
                    },
                )
            }
        }
    }
}

@Composable
private fun MemberActionsMenu(
    expanded: Boolean,
    member: User,
    myRole: ChatRole?,
    onDismiss: () -> Unit,
    onMakeAdmin: () -> Unit,
    onDemoteToMember: () -> Unit,
    onKick: () -> Unit,
    onTransferOwnership: () -> Unit,
) {
    var showTransferConfirm by remember { mutableStateOf(false) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // «Сделать администратором» — доступно CREATOR'у и любому ADMIN'у
        // (поднять MEMBER до ADMIN). «Снять администратора» — только CREATOR.
        when (member.groupRole) {
            ChatRole.MEMBER -> {
                if (myRole == ChatRole.CREATOR || myRole == ChatRole.ADMIN) {
                    DropdownMenuItem(
                        text = { Text("Сделать администратором") },
                        onClick = onMakeAdmin,
                    )
                }
            }
            ChatRole.ADMIN -> {
                if (myRole == ChatRole.CREATOR) {
                    DropdownMenuItem(
                        text = { Text("Снять администратора") },
                        onClick = onDemoteToMember,
                    )
                }
            }
            else -> Unit
        }
        // «Передать владельца» — только текущий CREATOR. Доступно для ADMIN
        // и MEMBER (CREATOR'a в списке actions всё равно нет — canActOnMember).
        if (myRole == ChatRole.CREATOR) {
            DropdownMenuItem(
                text = { Text("Передать владельца") },
                onClick = { showTransferConfirm = true },
            )
        }
        DropdownMenuItem(
            text = { Text("Исключить", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onKick,
        )
    }

    if (showTransferConfirm) {
        AlertDialog(
            onDismissRequest = { showTransferConfirm = false },
            title = { Text("Передать владельца?") },
            text = {
                Text(
                    "${member.displayName} станет владельцем группы. " +
                    "Вы получите роль администратора и сможете управлять чатом, " +
                    "но снимать его с поста владельца можно будет только тем же способом " +
                    "(или каскадом при выходе)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTransferConfirm = false
                    onTransferOwnership()
                }) { Text("Передать") }
            },
            dismissButton = {
                TextButton(onClick = { showTransferConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun EditTitleDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить название") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank() && text != initial) onConfirm(text) },
                enabled = text.isNotBlank() && text != initial,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun AddMemberDialog(
    candidates: List<User>,
    onDismiss: () -> Unit,
    onPick: (userId: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(candidates, q) {
        if (q.isEmpty()) candidates
        else candidates.filter {
            it.displayName.lowercase().contains(q) ||
                it.username.lowercase().contains(q) ||
                it.phone.lowercase().contains(q)
        }
    }

    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 200.dp, max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = "Добавить участника",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Поиск по контактам") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))

                when {
                    candidates.isEmpty() -> EmptyHint(
                        text = "Нет зарегистрированных контактов, которых ещё нет в группе.",
                    )
                    filtered.isEmpty() -> EmptyHint(text = "По «$query» никого не нашлось.")
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(filtered, key = { it.id }) { user ->
                                AddMemberRow(
                                    user = user,
                                    onClick = { onPick(user.id) },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AddMemberRow(
    user: User,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Тот же AvatarImage что в списке чатов: при отсутствии avatarUrl
        // показывается дефолтный медведь-Grizzly на палитро-цветном фоне
        // по hash от имени — единый стиль во всём приложении.
        com.secure.messenger.presentation.ui.chat.AvatarImage(
            url = user.avatarUrl,
            name = user.displayName,
            size = 44,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = when {
                user.username.isNotBlank() -> "@${user.username}"
                else -> user.phone
            }
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
