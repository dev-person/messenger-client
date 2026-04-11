package com.secure.messenger.utils

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File

/**
 * Простой плеер для голосовых сообщений. Принимает байты, сохраняет во временный
 * файл и воспроизводит через [MediaPlayer]. Позиция воспроизведения отдаётся
 * через колбэк [onProgress] с шагом 100 мс.
 *
 * Жизненный цикл: вызывайте [release] когда экран уничтожается.
 */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tmpFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTicker: Runnable? = null

    /**
     * Воспроизводит аудио из байтов.
     * @param onProgress колбэк (positionMs, durationMs) — обновляется каждые ~100 мс
     */
    fun play(audioBytes: ByteArray, onProgress: (Int, Int) -> Unit) {
        // Если был остановлен на паузе — продолжить, не перечитывая файл
        player?.let { existing ->
            if (!existing.isPlaying) {
                existing.start()
                startProgressTicker(existing, onProgress)
                return
            }
        }
        runCatching {
            release()
            val file = File(context.cacheDir, "voice_play_${System.currentTimeMillis()}.m4a")
            file.writeBytes(audioBytes)
            tmpFile = file

            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    onProgress(duration, duration)
                    stopProgressTicker()
                }
                start()
            }
            player = mp
            startProgressTicker(mp, onProgress)
        }.onFailure {
            Timber.e(it, "VoicePlayer.play failed")
            release()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        stopProgressTicker()
    }

    fun release() {
        stopProgressTicker()
        runCatching { player?.release() }
        player = null
        tmpFile?.delete()
        tmpFile = null
    }

    private fun startProgressTicker(mp: MediaPlayer, onProgress: (Int, Int) -> Unit) {
        stopProgressTicker()
        val ticker = object : Runnable {
            override fun run() {
                runCatching {
                    if (mp.isPlaying) {
                        onProgress(mp.currentPosition, mp.duration)
                        mainHandler.postDelayed(this, 100)
                    }
                }
            }
        }
        progressTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { mainHandler.removeCallbacks(it) }
        progressTicker = null
    }
}
