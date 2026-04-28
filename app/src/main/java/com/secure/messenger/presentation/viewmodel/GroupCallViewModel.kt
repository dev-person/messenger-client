package com.secure.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.remote.api.dto.GroupCallDto
import com.secure.messenger.data.remote.webrtc.GroupCallManager
import com.secure.messenger.data.remote.webrtc.GroupCallState
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.AuthRepository
import com.secure.messenger.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject

/**
 * UI-стейт одного тайла группового звонка.
 *
 * Тайл — это либо МОЯ карточка (с локальным видео и индикаторами), либо одна
 * из карточек других участников. У меня видео всегда в [localTrack], у других
 * — в [remoteTrack] (приходит через PeerState).
 */
data class GroupCallTile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    /** true для тайла самого пользователя ViewModel'а. */
    val isLocal: Boolean,
    /** Видеопоток. null = камера выключена / только аудио / ещё не подключился. */
    val videoTrack: VideoTrack?,
    /** true = микрофон отключён (для отображения иконки на тайле). */
    val isMuted: Boolean = false,
    /** true = ещё устанавливается соединение. Показываем спиннер. */
    val isConnecting: Boolean = false,
    /** true = от этого участника сейчас идёт звук. UI рисует подсветку рамкой. */
    val isSpeaking: Boolean = false,
)

/** Высокоуровневый UI-стейт экрана группового звонка. */
data class GroupCallUiState(
    val state: GroupCallState = GroupCallState.IDLE,
    val callId: String? = null,
    val isVideoCall: Boolean = false,
    val tiles: List<GroupCallTile> = emptyList(),
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val error: String? = null,
    val ended: Boolean = false,
    /**
     * true если экран показан по входящему приглашению, и юзер ещё не нажал
     * «Принять». Пока true — UI показывает pre-join оверлей с кнопками
     * принять/отклонить, треки не захвачены, peer connections не созданы.
     */
    val awaitingDecision: Boolean = false,
    /** Название чата (группы) — для отображения в pre-join UI. */
    val chatTitle: String = "",
    /** Аватар группы — для входящего pre-join overlay. */
    val chatAvatarUrl: String? = null,
)

/**
 * ViewModel экрана группового звонка. Координирует [GroupCallManager]
 * (mesh peer connections) с REST API сервера (start / join / leave) и
 * формирует UI-стейт с сеткой тайлов.
 */
@HiltViewModel
class GroupCallViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val userDao: UserDao,
    val groupCallManager: GroupCallManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupCallUiState())
    val uiState: StateFlow<GroupCallUiState> = _uiState.asStateFlow()

    /** Кеш профилей участников звонка (для имён/аватарок в тайлах). */
    private val userCache = HashMap<String, User>()

    /**
     * Список приглашённых юзеров (только для инициатора). Передаётся серверу,
     * сервер использует его для решения кому показать full-screen ringing.
     * null/пустой — приглашаем всех (legacy-поведение).
     */
    private var inviteUserIds: List<String>? = null

    /**
     * Вход на экран. Два режима:
     *  - existingCallId == null → ИНИЦИАТОР, сразу стартуем (auto-join).
     *    [inviteUserIds] — список выбранных в picker'е участников. null = всем.
     *  - existingCallId != null → ПРИГЛАШЁННЫЙ, показываем pre-join UI и
     *    ждём accept(). decline() закрывает экран без API-вызовов.
     */
    fun enter(
        chatId: String,
        isVideo: Boolean,
        existingCallId: String? = null,
        inviteUserIds: List<String>? = null,
    ) {
        if (existingCallId != null) {
            // Pre-join: загружаем title и аватар чата для отображения, ничего
            // не делаем на сервере пока юзер не нажал «Принять».
            viewModelScope.launch {
                val chat = runCatching {
                    chatRepository.observeChats().first().firstOrNull { it.id == chatId }
                }.getOrNull()
                _uiState.value = _uiState.value.copy(
                    awaitingDecision = true,
                    callId = existingCallId,
                    isVideoCall = isVideo,
                    chatTitle = chat?.title.orEmpty(),
                    chatAvatarUrl = chat?.avatarUrl,
                )
            }
        } else {
            // Прямой старт от инициатора
            this.inviteUserIds = inviteUserIds
            viewModelScope.launch { performJoinOrStart(chatId, isVideo, null) }
        }
    }

    /** Принять входящий звонок — запускаем join + локальные треки. */
    fun accept() {
        val state = _uiState.value
        if (!state.awaitingDecision) return
        val callId = state.callId ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(awaitingDecision = false)
            performJoinOrStart(chatId = "", isVideo = state.isVideoCall, existingCallId = callId)
        }
    }

    /** Отклонить входящий звонок — просто закрыть экран, ничего не дёргая на сервере. */
    fun decline() {
        _uiState.value = _uiState.value.copy(awaitingDecision = false, ended = true, state = GroupCallState.ENDED)
    }

    private suspend fun performJoinOrStart(chatId: String, isVideo: Boolean, existingCallId: String?) {
        _uiState.value = _uiState.value.copy(state = GroupCallState.CONNECTING, isVideoCall = isVideo)

        val dto: GroupCallDto = try {
            if (existingCallId != null) {
                chatRepository.joinGroupCall(existingCallId).getOrThrow()
            } else {
                chatRepository.startGroupCall(chatId, isVideo, inviteUserIds).getOrThrow()
            }
        } catch (e: Exception) {
            // Раньше тут сразу выставляли ended=true, и экран звонка моментально
            // закрывался — у юзера это выглядело как «мелькнула звонилка». Теперь
            // показываем ошибку, экран сам не закрываем — даём пользователю
            // прочитать что произошло и тапнуть «Завершить» вручную.
            Timber.e(e, "GroupCallViewModel: enter failed")
            _uiState.value = _uiState.value.copy(
                error = e.message ?: "Не удалось подключиться к звонку",
                state = GroupCallState.ENDED,
            )
            return
        }

        val callId = dto.id
        val effectiveIsVideo = dto.type == "VIDEO"
        _uiState.value = _uiState.value.copy(
            callId = callId,
            isVideoCall = effectiveIsVideo,
            isCameraOn = effectiveIsVideo,
        )

        val myId = authRepository.currentUser.first()?.id ?: ""
        groupCallManager.start(callId, effectiveIsVideo, myId)

        val otherActive = dto.participants
            .filter { it.leftAt == null && it.userId != myId }
            .map { it.userId }
        otherActive.forEach { peerId ->
            groupCallManager.connectToPeer(peerId)
        }

        viewModelScope.launch {
            groupCallManager.state.collect { st ->
                _uiState.value = _uiState.value.copy(state = st)
                if (st == GroupCallState.IDLE && _uiState.value.callId != null) {
                    _uiState.value = _uiState.value.copy(ended = true)
                }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                groupCallManager.participants,
                groupCallManager.localVideo,
                groupCallManager.speakingUserIds,
            ) { peers, local, speaking -> Triple(peers, local, speaking) }
                .collect { (peers, local, speaking) ->
                    rebuildTiles(myId, peers, local, speaking)
                }
        }
    }

    private suspend fun rebuildTiles(
        myId: String,
        peers: Map<String, GroupCallManager.PeerState>,
        localVideo: VideoTrack?,
        speakingIds: Set<String>,
    ) {
        val myUser = userCache[myId] ?: userDao.getById(myId)?.toDomain()?.also { userCache[myId] = it }

        val tiles = mutableListOf<GroupCallTile>()
        // Свой тайл всегда первый
        tiles.add(
            GroupCallTile(
                userId = myId,
                displayName = myUser?.displayName.orEmpty().ifEmpty { "Я" },
                avatarUrl = myUser?.avatarUrl,
                isLocal = true,
                videoTrack = if (_uiState.value.isCameraOn) localVideo else null,
                isMuted = _uiState.value.isMuted,
            ),
        )
        // Тайлы остальных — в порядке появления peer'ов
        peers.values.forEach { peer ->
            val u = userCache[peer.userId] ?: userDao.getById(peer.userId)?.toDomain()?.also {
                userCache[peer.userId] = it
            }
            tiles.add(
                GroupCallTile(
                    userId = peer.userId,
                    displayName = u?.displayName.orEmpty().ifEmpty { "..." },
                    avatarUrl = u?.avatarUrl,
                    isLocal = false,
                    videoTrack = peer.remoteVideo,
                    isConnecting = peer.remoteVideo == null && _uiState.value.isVideoCall,
                    isSpeaking = peer.userId in speakingIds,
                ),
            )
        }
        _uiState.value = _uiState.value.copy(tiles = tiles)
    }

    fun toggleMute() {
        val muted = groupCallManager.toggleMute()
        _uiState.value = _uiState.value.copy(isMuted = muted)
    }

    fun toggleCamera() {
        val on = groupCallManager.toggleCamera()
        _uiState.value = _uiState.value.copy(isCameraOn = on)
    }

    fun switchCamera() = groupCallManager.switchCamera()

    /** Покинуть звонок и завершить экран. */
    fun leave() {
        val callId = _uiState.value.callId
        viewModelScope.launch {
            if (callId != null) {
                runCatching { chatRepository.leaveGroupCall(callId) }
            }
            groupCallManager.leave()
            _uiState.value = _uiState.value.copy(ended = true, state = GroupCallState.ENDED)
        }
    }

    override fun onCleared() {
        // Если ViewModel ушёл из памяти без явного leave — закрываем сессию.
        // Серверу о выходе сообщит фоновый /leave (best-effort).
        groupCallManager.leave()
        super.onCleared()
    }
}
