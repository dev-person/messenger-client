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
import androidx.compose.runtime.DisposableEffect
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
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.presentation.viewmodel.CallViewModel
import io.getstream.webrtc.android.compose.VideoRenderer
import org.webrtc.RendererCommon

@Composable
fun CallScreen(
    onCallEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val webRtcState by viewModel.webRtcState.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()

    // Закрыть экран по завершении звонка
    LaunchedEffect(webRtcState) {
        if (webRtcState == WebRtcCallState.ENDED) onCallEnd()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)),
    ) {
        // Видео собеседника (на весь экран)
        val isVideoCall = uiState.call?.type == CallType.VIDEO
        if (isVideoCall && remoteVideoTrack != null) {
            VideoRenderer(
                videoTrack = remoteVideoTrack!!,
                modifier = Modifier.fillMaxSize(),
                eglBaseContext = viewModel.webRtcManager.eglBase.eglBaseContext,
                rendererEvents = object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit
                    override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) = Unit
                },
            )
        }

        // Своё видео (картинка в картинке, правый верхний угол)
        AnimatedVisibility(
            visible = isVideoCall && localVideoTrack != null && uiState.isCameraOn,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(120.dp, 160.dp)
                .clip(MaterialTheme.shapes.medium),
        ) {
            localVideoTrack?.let { track ->
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

        // Статус звонка (поверх видео)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = uiState.call?.calleeId ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontSize = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (webRtcState) {
                    WebRtcCallState.CALLING -> "Звоним..."
                    WebRtcCallState.RINGING -> "Вызов..."
                    WebRtcCallState.CONNECTING -> "Подключение..."
                    WebRtcCallState.CONNECTED -> "Соединено"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // Кнопки управления (снизу)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
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

            // Mute
            CallControlButton(
                icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (uiState.isMuted) "Включить микр." else "Выключить микр.",
                tint = Color.White,
                background = Color.White.copy(alpha = 0.2f),
                onClick = viewModel::toggleMute,
            )

            // Громкая связь
            CallControlButton(
                icon = Icons.Default.VolumeUp,
                label = "Громкая",
                tint = if (uiState.isSpeakerOn) MaterialTheme.colorScheme.primary else Color.White,
                background = Color.White.copy(alpha = 0.2f),
                onClick = viewModel::toggleSpeaker,
            )

            // Завершить звонок (всегда видна)
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
