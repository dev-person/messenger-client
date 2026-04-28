package com.secure.messenger.presentation.ui.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.secure.messenger.BuildConfig
import com.secure.messenger.presentation.viewmodel.GroupCallTile
import com.secure.messenger.presentation.viewmodel.GroupCallViewModel
import org.webrtc.RendererCommon

/** Singleton-доступ к разделяемому EglBase для рендера видео в тайлах. */
private object GroupCallEgl { @Volatile var context: org.webrtc.EglBase.Context? = null }

/**
 * Экран группового звонка (mesh P2P до 4 участников).
 *
 * Лэйаут сетки в зависимости от количества тайлов:
 *   1 → один тайл на весь экран
 *   2 → 1 ряд × 2 (или 2 × 1 если экран узкий — пока всегда 1×2)
 *   3 → 1 большой сверху + 2 маленьких снизу
 *   4 → 2×2 grid
 *
 * Контролы внизу: микрофон, камера (только для VIDEO), переключение камеры,
 * красная кнопка завершения. Тап по фону скрывает/показывает контролы (как
 * в обычном CallScreen).
 */
@Composable
fun GroupCallScreen(
    chatId: String,
    isVideo: Boolean,
    existingCallId: String? = null,
    inviteUserIds: List<String>? = null,
    onCallEnd: () -> Unit,
    viewModel: GroupCallViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(chatId, isVideo, existingCallId, inviteUserIds) {
        viewModel.enter(chatId, isVideo, existingCallId, inviteUserIds)
    }

    LaunchedEffect(uiState.ended) {
        if (uiState.ended) onCallEnd()
    }

    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(uiState.tiles.size) { controlsVisible = true }

    // Таймер длительности звонка — стартует когда подключился второй участник
    // (до этого «ожидание»), останавливается при ended.
    var elapsedSec by remember { mutableStateOf(0L) }
    val hasOtherParticipants = uiState.tiles.size >= 2
    LaunchedEffect(hasOtherParticipants, uiState.ended) {
        if (hasOtherParticipants && !uiState.ended) {
            // Сбрасываем при первом включении таймера, дальше тикает
            if (elapsedSec == 0L) elapsedSec = 0L
            while (!uiState.ended) {
                kotlinx.coroutines.delay(1_000L)
                elapsedSec += 1
            }
        }
    }

    // EglBase для рендера видео-тайлов — общий с WebRtcManager (он создаёт
    // factory). Достаём через GroupCallManager → сохраняем в Singleton-объект,
    // оттуда читают тайлы внутри сетки (через Composition было бы сложнее
    // прокидывать через 3 уровня вложенности).
    LaunchedEffect(Unit) {
        GroupCallEgl.context = viewModel.groupCallManager.eglBaseContext()
    }

    // Своё видео в основной сетке показываем только если в звонке кроме нас
    // никого нет (нечего смотреть, лучше большая своя камера). Как только
    // подключился хоть один peer — переезжаем в маленький плавающий тайл
    // в углу, как Zoom/Telegram.
    val localTile = uiState.tiles.firstOrNull { it.isLocal }
    val remoteTiles = uiState.tiles.filterNot { it.isLocal }
    val gridTiles = if (remoteTiles.isEmpty()) listOfNotNull(localTile) else remoteTiles

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            },
    ) {
        // ── Сетка тайлов ────────────────────────────────────────────────
        TileGrid(
            tiles = gridTiles,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Плавающий self-view (PiP) — только когда есть remote-участники ─
        if (remoteTiles.isNotEmpty() && localTile != null) {
            FloatingSelfView(
                tile = localTile,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 64.dp, end = 12.dp),
            )
        }

        // ── Шапка ───────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Transparent,
                        ),
                    )
                    .padding(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Групповой звонок",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                val subtitle = when {
                    !hasOtherParticipants -> "${uiState.tiles.size} участник(а) · ожидание"
                    else -> "${uiState.tiles.size} участник(а) · ${formatDuration(elapsedSec)}"
                }
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                )
            }
        }

        // ── Контролы ────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassToggleButton(
                        icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (uiState.isMuted) "Без звука" else "Микрофон",
                        active = uiState.isMuted,
                        onClick = viewModel::toggleMute,
                    )
                    if (uiState.isVideoCall) {
                        GlassToggleButton(
                            icon = if (uiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            label = if (uiState.isCameraOn) "Камера" else "Камера выкл",
                            active = !uiState.isCameraOn,
                            onClick = viewModel::toggleCamera,
                        )
                        GlassToggleButton(
                            icon = Icons.Default.Cameraswitch,
                            label = "Перевернуть",
                            active = false,
                            onClick = viewModel::switchCamera,
                        )
                    }
                }
                Spacer(modifier = Modifier.size(20.dp))
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "",
                    background = Color(0xFFE53935),
                    iconTint = Color.White,
                    size = 70,
                    onClick = viewModel::leave,
                )
            }
        }

        // ── Pre-join (входящий звонок, ждём решения юзера) ───────────────
        if (uiState.awaitingDecision) {
            IncomingGroupCallOverlay(
                chatTitle = uiState.chatTitle,
                chatAvatarUrl = uiState.chatAvatarUrl,
                isVideo = uiState.isVideoCall,
                onAccept = viewModel::accept,
                onDecline = viewModel::decline,
            )
        }

        // ── Спиннер при подключении (или ошибка) ─────────────────────────
        if (!uiState.awaitingDecision && uiState.tiles.size <= 1 && !uiState.ended) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val err = uiState.error
                    if (err != null) {
                        Text(
                            text = err,
                            color = Color(0xFFFF8A80),
                            fontSize = 14.sp,
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Ожидание участников…",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Сетка тайлов с лэйаутом 1/2/3/4. Позиционирование вычисляем сами через
 * Box+weight — пока без анимации перехода между размерами.
 */
@Composable
private fun TileGrid(
    tiles: List<GroupCallTile>,
    modifier: Modifier = Modifier,
) {
    when (tiles.size) {
        0, 1 -> {
            tiles.firstOrNull()?.let {
                CallTile(it, modifier = modifier)
            } ?: Box(modifier = modifier)
        }
        2 -> {
            Column(modifier = modifier) {
                CallTile(tiles[0], modifier = Modifier.weight(1f).fillMaxWidth())
                CallTile(tiles[1], modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
        3 -> {
            Column(modifier = modifier) {
                CallTile(tiles[0], modifier = Modifier.weight(1f).fillMaxWidth())
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CallTile(tiles[1], modifier = Modifier.weight(1f).fillMaxHeight())
                    CallTile(tiles[2], modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        else -> {
            // 4 → 2×2
            Column(modifier = modifier) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CallTile(tiles[0], modifier = Modifier.weight(1f).fillMaxHeight())
                    CallTile(tiles[1], modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CallTile(tiles[2], modifier = Modifier.weight(1f).fillMaxHeight())
                    CallTile(tiles[3], modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * Один тайл — либо видео-поток, либо аватарка-fallback с именем. На моём
 * тайле локальное превью зеркалится. Имя поверх снизу слева.
 */
@Composable
private fun CallTile(
    tile: GroupCallTile,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (tile.isSpeaking) Color(0xFF4CAF50) else Color.Transparent
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (tile.isSpeaking) 3.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val egl = GroupCallEgl.context
        if (tile.videoTrack != null && egl != null) {
            io.getstream.webrtc.android.compose.VideoRenderer(
                videoTrack = tile.videoTrack,
                modifier = Modifier.fillMaxSize(),
                eglBaseContext = egl,
                rendererEvents = object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit
                    override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                },
                onTextureViewCreated = { it.setMirror(tile.isLocal) },
            )
        } else {
            // Камера выключена / только аудио — большая аватарка по центру
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                AvatarPlaceholder(tile = tile)
            }
        }

        // Имя внизу слева
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (tile.isLocal) "Вы" else tile.displayName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // Иконка mute сверху справа
        if (tile.isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MicOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Спиннер пока пир ещё подключается (только для удалённых)
        if (tile.isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
    }
}

/**
 * Маленький плавающий self-view, показывается в верхнем правом углу когда
 * есть хотя бы один remote-участник. Размер ~110×140 dp — компромисс между
 * читаемостью и тем, чтобы не закрывать чужой тайл. Если камера выключена —
 * показывает аватар с инициалом, без чёрной дыры.
 */
@Composable
private fun FloatingSelfView(
    tile: GroupCallTile,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 110.dp, height = 150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val egl = GroupCallEgl.context
        if (tile.videoTrack != null && egl != null) {
            io.getstream.webrtc.android.compose.VideoRenderer(
                videoTrack = tile.videoTrack,
                modifier = Modifier.fillMaxSize(),
                eglBaseContext = egl,
                rendererEvents = object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit
                    override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                },
                onTextureViewCreated = { it.setMirror(true) },
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "Вы",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (tile.isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MicOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder(tile: GroupCallTile) {
    val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')
    val resolved = tile.avatarUrl?.let { url ->
        when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$serverRoot$url"
            else -> url
        }
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = tile.displayName,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

/** «1:23» / «42:05» / «1:02:03». */
private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Полноэкранный оверлей для входящего группового звонка: название группы,
 * иконка типа звонка (audio/video) и две большие кнопки — принять (зелёная)
 * и отклонить (красная). Закрывается после нажатия одной из них.
 */
@Composable
private fun IncomingGroupCallOverlay(
    chatTitle: String,
    chatAvatarUrl: String?,
    isVideo: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF1B5E20),
                    1f to Color(0xFF000000),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Входящий групповой звонок",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = chatTitle.ifBlank { "Группа" },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.size(24.dp))
            // Аватар группы; если его нет — иконка звонка как фолбэк.
            val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')
            val resolvedAvatar = chatAvatarUrl?.let { url ->
                when {
                    url.startsWith("http") -> url
                    url.startsWith("/") -> "$serverRoot$url"
                    else -> url
                }
            }
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (resolvedAvatar != null) {
                    AsyncImage(
                        model = resolvedAvatar,
                        contentDescription = chatTitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = if (isVideo) "Видеозвонок" else "Аудиозвонок",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Отклонить",
                    background = Color(0xFFE53935),
                    iconTint = Color.White,
                    size = 76,
                    onClick = onDecline,
                )
                CallControlButton(
                    icon = if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                    label = "Принять",
                    background = Color(0xFF4CAF50),
                    iconTint = Color.White,
                    size = 76,
                    onClick = onAccept,
                )
            }
        }
    }
}

