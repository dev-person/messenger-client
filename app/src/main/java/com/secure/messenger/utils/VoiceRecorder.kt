package com.secure.messenger.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

/**
 * Простая обёртка над [MediaRecorder] для записи коротких голосовых сообщений в AAC.
 *
 * Во время записи периодически (~80 мс) сэмплирует амплитуду через
 * [MediaRecorder.getMaxAmplitude] — это даёт нам waveform для отображения
 * в UI плеера (бары вместо обычного прогресс-бара).
 */
class VoiceRecorder(private val context: Context) {

    /** Результат записи: байты, длительность и амплитуды для waveform-а. */
    data class RecordedVoice(
        val bytes: ByteArray,
        val durationSeconds: Int,
        val waveform: IntArray,
    )

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val amplitudes = mutableListOf<Int>()
    private var sampleTicker: Runnable? = null

    /**
     * «Живые» амплитуды для отрисовки waveform-анимации в UI прямо во время
     * записи. Обновляется тем же тикером что и [amplitudes] (раз в 50 мс),
     * но как иммутабельный snapshot — Compose может подписаться через collectAsState.
     */
    private val _liveAmplitudes = MutableStateFlow(IntArray(0))
    val liveAmplitudes: StateFlow<IntArray> = _liveAmplitudes.asStateFlow()

    /** Начало записи. Возвращает true если успешно. */
    fun start(): Boolean {
        return runCatching {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            amplitudes.clear()
            _liveAmplitudes.value = IntArray(0)

            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Невысокий битрейт — сообщения мелкие, проходят через encryptedContent
            mr.setAudioEncodingBitRate(32_000)
            mr.setAudioSamplingRate(22_050)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            startedAtMs = System.currentTimeMillis()
            startAmplitudeSampling()
            true
        }.onFailure {
            Timber.e(it, "VoiceRecorder.start failed")
            cleanup()
        }.getOrDefault(false)
    }

    /**
     * Остановить запись и вернуть результат с amplitudes для waveform-а.
     * Файл удаляется после чтения. Возвращает null при ошибке.
     */
    fun stop(): RecordedVoice? {
        val mr = recorder ?: return null
        val file = outputFile ?: return null
        val durationMs = System.currentTimeMillis() - startedAtMs
        stopAmplitudeSampling()
        return runCatching {
            mr.stop()
            mr.release()
            recorder = null
            val bytes = file.readBytes()
            file.delete()
            outputFile = null
            RecordedVoice(
                bytes = bytes,
                durationSeconds = (durationMs / 1000).toInt().coerceAtLeast(1),
                waveform = amplitudes.toIntArray(),
            )
        }.onFailure {
            Timber.e(it, "VoiceRecorder.stop failed")
            cleanup()
        }.getOrNull()
    }

    /** Отменить запись без сохранения. */
    fun cancel() {
        cleanup()
    }

    private fun cleanup() {
        stopAmplitudeSampling()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
        amplitudes.clear()
    }

    /**
     * Запуск тикера, читающего getMaxAmplitude каждые 50 мс.
     * Первый вызов всегда возвращает 0 (доку MediaRecorder), поэтому стартуем
     * с небольшой задержкой и пропускаем нулевой первый замер.
     */
    private fun startAmplitudeSampling() {
        stopAmplitudeSampling()
        // Первый «холостой» вызов maxAmplitude чтобы сбросить начальное состояние
        runCatching { recorder?.maxAmplitude }
        val ticker = object : Runnable {
            override fun run() {
                val mr = recorder ?: return
                runCatching {
                    val amp = mr.maxAmplitude
                    if (amp > 0 || amplitudes.isNotEmpty()) {
                        amplitudes.add(amp)
                        // Публикуем snapshot — Compose-индикатор записи
                        // переподписывается и перерисовывает волну на каждом тике.
                        _liveAmplitudes.value = amplitudes.toIntArray()
                    }
                }
                mainHandler.postDelayed(this, 50)
            }
        }
        sampleTicker = ticker
        // Стартуем через 50 мс — даём микрофону прогреться, чтобы первый замер был ненулевой
        mainHandler.postDelayed(ticker, 50)
    }

    private fun stopAmplitudeSampling() {
        sampleTicker?.let { mainHandler.removeCallbacks(it) }
        sampleTicker = null
    }
}
