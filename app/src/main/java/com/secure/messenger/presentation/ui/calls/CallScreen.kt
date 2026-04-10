package com.secure.messenger.presentation.ui.calls

import android.Manifest
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.secure.messenger.data.remote.webrtc.WebRtcCallState
import com.secure.messenger.domain.model.CallState
import com.secure.messenger.domain.model.CallType
import com.secure.messenger.presentation.viewmodel.CallViewModel
import io.getstream.webrtc.android.compose.VideoRenderer
import kotlinx.coroutines.flow.drop
import org.webrtc.RendererCommon
import kotlin.math.abs

// Avatar background palette (same as ChatListScreen)
private val AvatarPalette = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828),
    Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFFE65100),
    Color(0xFF4527A0), Color(0xFF558B2F),
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CallScreen(
    userId: String,
    isVideo: Boolean,
    peerName: String,         // initial placeholder from nav arg
    onCallEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val uiState        by viewModel.uiState.collectAsStateWithLifecycle()
    val webRtcState    by viewModel.webRtcState.collectAsStateWithLifecycle()
    val remoteVideo    by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val localVideo     by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val resolvedName   by viewModel.peerDisplayName.collectAsStateWithLifecycle()
    val peerAvatarUrl  by viewModel.peerAvatarUrl.collectAsStateWithLifecycle()

    // Защита от двойного popBackStack — вызываем onCallEnd только один раз
    var callEndHandled by remember { mutableStateOf(false) }
    val safeCallEnd: () -> Unit = {
        if (!callEndHandled) {
            callEndHandled = true
            onCallEnd()
        }
    }

    // Resolve peer info as soon as the screen opens
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

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted
            && userId.isNotEmpty()
            && uiState.call?.state != CallState.RINGING
        ) {
            viewModel.startCall(userId, if (isVideo) CallType.VIDEO else CallType.AUDIO)
        }
    }

    // Закрываем экран когда звонок завершён:
    // 1) WebRTC перешёл в ENDED (нормальное завершение соединённого звонка)
    // 2) activeCall стал null (собеседник отменил/завершил до соединения)
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
    // Show resolved name if available, else fall back to the nav-arg placeholder
    val displayName = resolvedName.ifBlank { peerName.ifBlank { "..." } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0D1117), Color(0xFF1A237E)))
            ),
    ) {
        // ── Remote video (full screen) ─────────────────────────────────────────
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

        // ── Local video PiP (top-right) ────────────────────────────────────────
        AnimatedVisibility(
            visible = isVideoCall && localVideo != null && uiState.isCameraOn,
            enter = fadeIn(), exit = fadeOut(),
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

        // ── Caller info: avatar + name + status ───────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar
            if (peerAvatarUrl != null) {
                AsyncImage(
                    model = peerAvatarUrl,
                    contentDescription = displayName,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape),
                )
            } else {
                // Colored initials fallback
                val initials = displayName.split(" ").take(2)
                    .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
                    .ifEmpty { "?" }
                val bgColor = AvatarPalette[abs(displayName.hashCode()) % AvatarPalette.size]
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                ) {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isRinging                                   -> "Входящий звонок..."
                    webRtcState == WebRtcCallState.CALLING      -> "Вызов..."
                    webRtcState == WebRtcCallState.CONNECTING   -> "Подключение..."
                    webRtcState == WebRtcCallState.CONNECTED    -> "Соединено"
                    else                                        -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // ── Controls ──────────────────────────────────────────────────────────
        if (isRinging) {
            // Incoming: decline / accept
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(80.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Отклонить",
                    tint = Color.White,
                    background = Color(0xFFD32F2F),
                    size = 72,
                    onClick = {
                        viewModel.declineCall()
                        safeCallEnd()
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
            // Active call: media controls + hang up
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
                    tint = if (uiState.isSpeakerOn) Color(0xFF4FC3F7) else Color.White,
                    background = Color.White.copy(alpha = 0.2f),
                    onClick = viewModel::toggleSpeaker,
                )
                CallControlButton(
                    icon = Icons.Default.CallEnd,
                    label = "Завершить",
                    tint = Color.White,
                    background = Color(0xFFD32F2F),
                    size = 72,
                    onClick = {
                        viewModel.hangUp()
                        safeCallEnd()
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
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}
