package com.secure.messenger.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.secure.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Централизованный плеер коротких системных звуков приложения.
 *
 * Сейчас поддерживает два звука:
 *  - **message sent** — короткий "tick" при успешной отправке сообщения.
 *    Используется [SoundPool] (быстрое воспроизведение, низкая латентность).
 *  - **outgoing call ringback** — гудок исходящего вызова, повторяется в цикле
 *    с задержкой 3 секунды между повторами. Используется [MediaPlayer] +
 *    собственная корутина, потому что нужно точно ловить onCompletion и ставить
 *    паузу между повторами.
 *
 * Singleton — чтобы один и тот же экземпляр SoundPool существовал на всю
 * жизнь приложения и был готов мгновенно проигрывать звук без задержки.
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // Атрибуты для коротких UI-звуков (отправка сообщения)
    private val uiAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .build()

    // SoundPool для коротких one-shot звуков. SoundPool лучше MediaPlayer для
    // мелких эффектов: декодирование происходит один раз при load(), потом
    // play() вызывается мгновенно без I/O.
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(uiAttributes)
        .build()

    private var messageSentSoundId: Int = 0
    private var messageSentLoaded: Boolean = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == messageSentSoundId) {
                messageSentLoaded = true
            }
        }
        runCatching {
            messageSentSoundId = soundPool.load(context, R.raw.message_sent, 1)
        }.onFailure { Timber.e(it, "SoundManager: failed to load message_sent") }
    }

    /** Проигрывает короткий звук успешной отправки сообщения. */
    fun playMessageSent() {
        if (!messageSentLoaded) return
        runCatching {
            soundPool.play(messageSentSoundId, 0.7f, 0.7f, 1, 0, 1f)
        }.onFailure { Timber.e(it, "SoundManager: failed to play message_sent") }
    }

    // ── Outgoing call ringback ────────────────────────────────────────────────

    /**
     * Своя scope для ringback-цикла. Не привязана к ViewModel-у, потому что
     * SoundManager — singleton и переживает все CallViewModel-ы.
     */
    private val ringbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ringbackJob: Job? = null
    private var ringbackPlayer: MediaPlayer? = null

    /**
     * Запускает гудок исходящего вызова: один проигрыш, потом 3 сек паузы,
     * потом снова. Идемпотентно — повторный вызов ничего не делает если
     * уже играет.
     */
    fun startOutgoingRingback() {
        if (ringbackJob?.isActive == true) return
        ringbackJob = ringbackScope.launch {
            while (isActive) {
                val mp = runCatching {
                    MediaPlayer.create(context, R.raw.outgoing_call)?.apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                                .build()
                        )
                        setVolume(0.8f, 0.8f)
                    }
                }.getOrNull()

                if (mp == null) {
                    Timber.w("SoundManager: MediaPlayer.create(outgoing_call) returned null")
                    return@launch
                }
                ringbackPlayer = mp

                val durationMs = mp.duration.toLong().coerceAtLeast(800)
                runCatching { mp.start() }
                    .onFailure { Timber.e(it, "SoundManager: ringback start failed") }

                delay(durationMs)
                runCatching {
                    mp.stop()
                    mp.release()
                }
                ringbackPlayer = null

                if (!isActive) break
                delay(RINGBACK_GAP_MS)
            }
        }
    }

    /** Останавливает гудок исходящего вызова. */
    fun stopOutgoingRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        runCatching {
            ringbackPlayer?.stop()
            ringbackPlayer?.release()
        }
        ringbackPlayer = null
    }

    /** Освобождает ресурсы — вызывать при logout / завершении приложения. */
    fun release() {
        stopOutgoingRingback()
        runCatching { soundPool.release() }
        ringbackScope.cancel()
    }

    private companion object {
        const val RINGBACK_GAP_MS = 3_000L
    }
}
