package com.secure.messenger.presentation.ui.calls

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.presentation.viewmodel.CallViewModel
import com.secure.messenger.utils.ProximityScreenLock
import io.getstream.webrtc.android.compose.VideoRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import org.webrtc.RendererCommon
import kotlin.math.abs

// Палитра цветных аватаров (одинаковая с ChatListScreen)
private val AvatarPalette = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828),
    Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFE65100),
    Color(0xFF4527A0), Color(0xFF558B2F),
)

// Корень сервера (без /v1/) — нужен чтобы достроить относительные пути
// аватаров типа "/static/avatars/foo.png" до полного URL для Coil.
private val serverRoot = BuildConfig.API_BASE_URL.removeSuffix("v1/").trimEnd('/')

/**
 * Резолвит URL аватара из БД в загружаемый Coil-ом адрес. На сервере в БД
 * хранятся относительные пути ("/static/..."), Coil их не умеет — нужен
 * полный https://. Без этой функции аватар на экране звонка не показывался.
 */
private fun resolveAvatarUrl(raw: String?): String? = when {
    raw == null -> null
    raw.startsWith("http") -> raw
    raw.startsWith("/") -> "$serverRoot$raw"
    else -> raw
}

// Базовый фоновый градиент звонилки — глубокий тёмно-фиолетовый, как в премиум-мессенджерах
private val CallBackgroundGradient = Brush.verticalGradient(
    listOf(
        Color(0xFF0A0E27),
        Color(0xFF1A1F4D),
        Color(0xFF2D1B69),
    )
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CallScreen(
    userId: String,
    isVideo: Boolean,
    peerName: String,         // плейсхолдер из nav-args
    isIncoming: Boolean,      // true если экран открыт по входящему звонку
    onCallEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState        by viewModel.uiState.collectAsStateWithLifecycle()
    val webRtcState    by viewModel.webRtcState.collectAsStateWithLifecycle()
    val remoteVideo    by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val localVideo     by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val resolvedName   by viewModel.peerDisplayName.collectAsStateWithLifecycle()
    val peerAvatarUrl  by viewModel.peerAvatarUrl.collectAsStateWithLifecycle()

    // Пока открыт экран звонка — кнопки громкости управляют каналом
    // STREAM_VOICE_CALL (а не STREAM_RING, который Android по умолчанию
    // выбирает для приложения без активного телефонного режима). Без этого
    // прибавление громкости на громкой связи увеличивает уровень звонка,
    // а не голоса собеседника, что выглядит как «не работает».
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val previous = activity?.volumeControlStream
        activity?.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
        onDispose {
            // Восстанавливаем дефолтное поведение, чтобы вне звонка громкость
            // снова управляла мультимедиа.
            activity?.volumeControlStream =
                previous ?: android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    // Защита от двойного popBackStack — onCallEnd только один раз
    var callEndHandled by remember { mutableStateOf(false) }
    val safeCallEnd: () -> Unit = {
        if (!callEndHandled) {
            callEndHandled = true
            onCallEnd()
        }
    }

    // Подгружаем профиль собеседника как только экран открылся
    LaunchedEffect(userId) {
        if (userId.isNotEmpty()) viewModel.resolvePeer(userId)
    }

    val neededPermissions = if (isVideo) {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    } else {
        listOf(Manifest.permission.RECORD_AUDIO)
    }
    val permissionsState = rememberMultiplePermissionsState(neededPermissions)

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    // Auto-start ИСХОДЯЩЕГО звонка — только если экран открыт через chat → call
    // (isIncoming=false). Для входящих (isIncoming=true) этого делать НЕЛЬЗЯ:
    // если call к этому моменту уже null (звонящий бросил трубку до того как
    // юзер успел открыть приложение по нотификации), мы бы запустили
    // «фантомный» исходящий звонок назад звонящему.
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted
            && userId.isNotEmpty()
            && !isIncoming
            && uiState.call?.state != CallState.RINGING
        ) {
            viewModel.startCall(userId, if (isVideo) CallType.VIDEO else CallType.AUDIO)
        }
    }

    // Если открыли по входящему, но звонок уже завершился (звонящий бросил
    // трубку до того как мы успели открыть экран) — сразу же закрываем
    // CallScreen. Без этого экран висел бы в пустом RINGING-состоянии.
    LaunchedEffect(isIncoming) {
        if (isIncoming && uiState.call == null) {
            safeCallEnd()
        }
    }

    // Закрытие экрана при завершении звонка:
    // 1) WebRTC → ENDED (нормальное завершение)
    // 2) activeCall → null (собеседник отменил/завершил до соединения)
    LaunchedEffect(Unit) {
        viewModel.webRtcState.drop(1)
            .collect { state: WebRtcCallState -> if (state == WebRtcCallState.ENDED) safeCallEnd() }
    }
    LaunchedEffect(Unit) {
        viewModel.activeCallState.drop(1)
            .collect { call -> if (call == null) safeCallEnd() }
    }

    val isVideoCall = uiState.call?.type == CallType.VIDEO || isVideo
    val isRinging   = uiState.call?.state == CallState.RINGING
    val isConnected = webRtcState == WebRtcCallState.CONNECTED
    // Имя собеседника: сначала загруженное из БД, потом плейсхолдер из nav-args
    val displayName = resolvedName.ifBlank { peerName.ifBlank { "..." } }

    // ── Датчик приближения для аудио-звонков ─────────────────────────────────
    // Активируем СРАЗУ при открытии экрана аудио-звонка (не ждём CONNECTED) —
    // юзер может уже поднести телефон к уху во время дозвона.
    // Двухслойная защита: системный wake lock гасит экран физически + если он
    // не сработал (MIUI и т.п.), показываем чёрный Compose-оверлей в UI.
    val context = LocalContext.current
    val proximityLock = remember { ProximityScreenLock(context) }
    val isProximityNear by proximityLock.isNear.collectAsStateWithLifecycle()
    DisposableEffect(isVideoCall) {
        if (!isVideoCall) {
            proximityLock.acquire()
        } else {
            proximityLock.release()
        }
        onDispose { proximityLock.release() }
    }

    // Таймер длительности соединения (отображается в шапке после CONNECTED)
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds += 1
            }
        } else {
            elapsedSeconds = 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CallBackgroundGradient),
    ) {
        // ── Видео собеседника на весь экран ──────────────────────────────────
        if (isVideoCall && remoteVideo != null) {
            VideoRenderer(
                videoTrack = remoteVideo!!,
                modifier = Modifier.fillMaxSize(),
                eglBaseContext = viewModel.webRtcManager.eglBase.eglBaseContext,
                rendererEvents = object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit
                    override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                },
            )
            // Тёмная вуаль над видео — чтобы кнопки и текст оставались читаемыми
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.55f),
                            0.3f to Color.Transparent,
                            0.7f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.55f),
                        )
                    ),
            )
        } else if (!isVideoCall) {
            // Аудио-звонок: размытое цветное «свечение» на заднем плане
            BlurredAvatarBackground(
                avatarUrl = peerAvatarUrl,
                fallbackName = displayName,
            )
        }

        // ── Локальное видео в углу (PiP) ─────────────────────────────────────
        AnimatedVisibility(
            visible = isVideoCall && localVideo != null && uiState.isCameraOn,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp)
                .size(110.dp, 150.dp)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            localVideo?.let { track ->
                VideoRenderer(
                    videoTrack = track,
                    modifier = Modifier.fillMaxSize(),
                    eglBaseContext = viewModel.webRtcManager.eglBase.eglBaseContext,
                    // Зеркалим только своё превью (фронталка) — как зеркало в
                    // ванной. Без этого, если махнуть правой рукой, на превью
                    // выглядит «левая», и кажется, что камера сломана. У
                    // собеседника изображение придёт уже в нормальной
                    // ориентации (повторно отзеркаливать нельзя — он видит
                    // меня «как меня видят»).
                    onTextureViewCreated = { it.setMirror(true) },
                    rendererEvents = object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit
                        override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                    },
                )
            }
        }

        // ── Шапка: аватар + имя + статус ─────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Маркер шифрования (как в WhatsApp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "🔒",
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.size(width = 6.dp, height = 0.dp))
                Text(
                    text = "Сквозное шифрование",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Аватар не показываем поверх видео, если уже идёт видеозвонок
            if (!isVideoCall || remoteVideo == null) {
                Spacer(modifier = Modifier.height(40.dp))

                // Аватар с пульсирующими волнами пока соединение не установлено
                PulsatingAvatar(
                    avatarUrl = peerAvatarUrl,
                    fallbackName = displayName,
                    showWaves = !isConnected,
                )

                Spacer(modifier = Modifier.height(28.dp))
            } else {
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isRinging                                   -> "Входящий звонок..."
                    webRtcState == WebRtcCallState.CALLING      -> "Вызов..."
                    webRtcState == WebRtcCallState.CONNECTING   -> "Подключение..."
                    isConnected                                 -> formatCallDuration(elapsedSeconds)
                    else                                        -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isConnected) Color(0xFF81C784) else Color.White.copy(alpha = 0.75f),
                fontWeight = if (isConnected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 15.sp,
            )
        }

        // ── Управление ───────────────────────────────────────────────────────
        if (isRinging) {
            // Входящий звонок: отклонить / принять
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(80.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Отклонить",
                    background = Color(0xFFE53935),
                    iconTint = Color.White,
                    size = 76,
                    onClick = {
                        viewModel.declineCall()
                        safeCallEnd()
                    },
                )
                CallControlButton(
                    icon = Icons.Default.Call,
                    label = "Принять",
                    background = Color(0xFF1DB954),
                    iconTint = Color.White,
                    size = 76,
                    onClick = {
                        uiState.call?.id?.let { viewModel.acceptCall(it) }
                    },
                )
            }
        } else {
            // Активный звонок: медиа-контролы + завершение.
            // Контролы вынесены в «стеклянную» панель внизу.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Верхний ряд: переключатели (микрофон / камера / громкая)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isVideoCall) 14.dp else 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassToggleButton(
                        icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (uiState.isMuted) "Без звука" else "Микрофон",
                        active = uiState.isMuted,
                        onClick = viewModel::toggleMute,
                    )
                    GlassToggleButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "Динамик",
                        active = uiState.isSpeakerOn,
                        onClick = viewModel::toggleSpeaker,
                    )
                    if (isVideoCall) {
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

                Spacer(modifier = Modifier.height(24.dp))

                // Большая красная кнопка завершения
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "",
                    background = Color(0xFFE53935),
                    iconTint = Color.White,
                    size = 72,
                    onClick = {
                        viewModel.hangUp()
                        safeCallEnd()
                    },
                )
            }
        }

        // ── Чёрный оверлей при поднесении телефона к уху ──────────────────
        // Fallback на случай если PROXIMITY_SCREEN_OFF_WAKE_LOCK не сработал
        // (MIUI и др.). Накрывает весь экран чёрным и блокирует тачи, чтобы
        // юзер случайно не нажал кнопку щекой.
        if (!isVideoCall && isProximityNear) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        // Поглощаем все жесты — даже долгое нажатие не пройдёт
                        detectTapGestures { }
                    },
            )
        }
    }
}

// ── Аватар собеседника с пульсирующими волнами ────────────────────────────────

/**
 * Аватар собеседника по центру экрана + расходящиеся круговые волны вокруг
 * (пока звонок не соединён). Когда звонок соединился — волны останавливаются.
 */
@Composable
private fun PulsatingAvatar(
    avatarUrl: String?,
    fallbackName: String,
    showWaves: Boolean,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_pulse")
    val waveSpec = infiniteRepeatable<Float>(
        animation = tween(2000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )
    val wave1 by infiniteTransition.animateFloat(0f, 1f, waveSpec, label = "w1")
    val wave2 by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            animation = tween(2000, delayMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "w2",
    )
    val wave3 by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            animation = tween(2000, delayMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "w3",
    )

    val accent = Color(0xFF7C4DFF)

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showWaves) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = size.minDimension * 0.30f // ~140dp / 2
                val maxRadius = size.minDimension * 0.5f
                fun drawWave(progress: Float) {
                    val radius = baseRadius + (maxRadius - baseRadius) * progress * 1.4f
                    val alpha = (1f - progress).coerceIn(0f, 1f) * 0.5f
                    drawCircle(
                        color = accent.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                    )
                }
                drawWave(wave1)
                drawWave(wave2)
                drawWave(wave3)
            }
        }

        // Сам аватар. Резолвим относительный путь в полный URL — иначе Coil
        // ничего не загрузит и мы окажемся в else-ветке с инициалами.
        val resolvedUrl = resolveAvatarUrl(avatarUrl)
        if (resolvedUrl != null) {
            AsyncImage(
                model = resolvedUrl,
                contentDescription = fallbackName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape),
            )
        } else {
            val initials = fallbackName.split(" ").take(2)
                .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
                .ifEmpty { "?" }
            val bgColor = AvatarPalette[abs(fallbackName.hashCode()) % AvatarPalette.size]
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                bgColor,
                                bgColor.copy(red = (bgColor.red * 0.6f).coerceIn(0f, 1f)),
                            )
                        )
                    ),
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Размытый аватар на заднем плане для аудио-звонков (как в Telegram). */
@Composable
private fun BlurredAvatarBackground(avatarUrl: String?, fallbackName: String) {
    val resolvedUrl = resolveAvatarUrl(avatarUrl)
    if (resolvedUrl != null) {
        AsyncImage(
            model = resolvedUrl,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = 60.dp)
                .graphicsLayer { alpha = 0.35f },
        )
    } else {
        // Цветовое пятно из палитры — единственное украшение фона
        val bgColor = AvatarPalette[abs(fallbackName.hashCode()) % AvatarPalette.size]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            bgColor.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                        radius = 900f,
                    )
                ),
        )
    }
}

// ── Кнопки управления звонком ────────────────────────────────────────────────

/** Большая круглая кнопка (принять / отклонить / завершить). */
@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    background: Color,
    iconTint: Color,
    size: Int = 64,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size((size * 0.42f).dp),
            )
        }
        if (label.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * «Стеклянная» переключатель-кнопка для нижней панели управления звонком.
 * Когда [active] = true (например, mute включён) — заливка светлее.
 */
@Composable
private fun GlassToggleButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) Color.White.copy(alpha = 0.95f)
                    else Color.White.copy(alpha = 0.18f)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color(0xFF1A1F4D) else Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
        )
    }
}

/** Форматирует длительность звонка как «м:сс» или «ч:мм:сс». */
private fun formatCallDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
