package com.secure.messenger.presentation.ui.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.presentation.viewmodel.CallViewModel
import io.getstream.webrtc.android.compose.VideoRenderer
import org.webrtc.RendererCommon

@Composable
fun CallScreen(
    userId: String,
    isVideo: Boolean,
    peerName: String,
    onCallEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState     by viewModel.uiState.collectAsStateWithLifecycle()
    val webRtcState by viewModel.webRtcState.collectAsStateWithLifecycle()
    val remoteVideo by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val localVideo  by viewModel.localVideoTrack.collectAsStateWithLifecycle()

    // Запустить исходящий звонок один раз при открытии экрана.
    // Для входящих звонков (RINGING) звонок уже есть в activeCall — не стартуем повторно.
    LaunchedEffect(userId) {
        if (userId.isNotEmpty() && uiState.call?.state != CallState.RINGING) {
            viewModel.startCall(userId, if (isVideo) CallType.VIDEO else CallType.AUDIO)
        }
    }

    // Закрыть экран когда звонок завершился
    LaunchedEffect(webRtcState) {
        if (webRtcState == WebRtcCallState.ENDED) onCallEnd()
    }

    val isVideoCall  = uiState.call?.type == CallType.VIDEO || isVideo
    val isRinging    = uiState.call?.state == CallState.RINGING
    val displayName  = peerName.ifBlank { uiState.call?.callerId ?: uiState.call?.calleeId ?: "..." }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)),
    ) {

        // ── Видео собеседника (на весь экран) ─────────────────────────────────
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
        }

        // ── Своё видео (PiP, правый верхний угол) ─────────────────────────────
        AnimatedVisibility(
            visible = isVideoCall && localVideo != null && uiState.isCameraOn,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 16.dp)
                .size(110.dp, 150.dp)
                .clip(MaterialTheme.shapes.medium),
        ) {
            localVideo?.let { track ->
                VideoRenderer(
                    videoTrack = track,
                    modifier = Modifier.fillMaxSize(),
                    eglBaseContext = viewModel.webRtcManager.eglBase.eglBaseContext,
                    rendererEvents = object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit
                        override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                    },
                )
            }
        }

        // ── Имя и статус (сверху по центру) ───────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isRinging -> "Входящий звонок..."
                    webRtcState == WebRtcCallState.CALLING     -> "Вызов..."
                    webRtcState == WebRtcCallState.CONNECTING  -> "Подключение..."
                    webRtcState == WebRtcCallState.CONNECTED   -> "Соединено"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // ── Управление (снизу) ────────────────────────────────────────────────
        if (isRinging) {
            // Входящий звонок: принять / отклонить
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Отклонить",
                    tint = Color.White,
                    background = Color.Red,
                    size = 72,
                    onClick = {
                        viewModel.declineCall()
                        onCallEnd()
                    },
                )
                CallControlButton(
                    icon = Icons.Default.Call,
                    label = "Принять",
                    tint = Color.White,
                    background = Color(0xFF1DB954),
                    size = 72,
                    onClick = {
                        uiState.call?.id?.let { viewModel.acceptCall(it) }
                    },
                )
            }
        } else {
            // Активный звонок: управление медиа + завершить
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isVideoCall) {
                    CallControlButton(
                        icon = if (uiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (uiState.isCameraOn) "Камера" else "Камера выкл",
                        tint = Color.White,
                        background = Color.White.copy(alpha = 0.2f),
                        onClick = viewModel::toggleCamera,
                    )
                    CallControlButton(
                        icon = Icons.Default.Cameraswitch,
                        label = "Перевернуть",
                        tint = Color.White,
                        background = Color.White.copy(alpha = 0.2f),
                        onClick = viewModel::switchCamera,
                    )
                }
                CallControlButton(
                    icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (uiState.isMuted) "Включить микр." else "Выкл. микр.",
                    tint = Color.White,
                    background = Color.White.copy(alpha = 0.2f),
                    onClick = viewModel::toggleMute,
                )
                CallControlButton(
                    icon = Icons.Default.VolumeUp,
                    label = "Громкая",
                    tint = if (uiState.isSpeakerOn) MaterialTheme.colorScheme.primary else Color.White,
                    background = Color.White.copy(alpha = 0.2f),
                    onClick = viewModel::toggleSpeaker,
                )
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Завершить",
                    tint = Color.White,
                    background = Color.Red,
                    size = 72,
                    onClick = {
                        viewModel.hangUp()
                        onCallEnd()
                    },
                )
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    background: Color,
    size: Int = 56,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(background),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size((size * 0.45f).dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
