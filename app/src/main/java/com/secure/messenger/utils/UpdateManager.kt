package com.secure.messenger.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.remote.api.UpdateInfoDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер обновлений приложения.
 *
 * Проверяет наличие новой версии на сервере, скачивает APK
 * и запускает системный установщик.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    // Отдельный клиент без авторизации — работает до входа в аккаунт
    private val publicClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _updateAvailable = MutableStateFlow<UpdateInfoDto?>(null)
    val updateAvailable = _updateAvailable.asStateFlow()

    /**
     * Проверяет наличие обновления на сервере.
     * Использует публичный HTTP-клиент (без токена) — работает на экране авторизации.
     * Возвращает [UpdateInfoDto] если есть более новая версия, иначе null.
     */
    suspend fun checkForUpdate(): UpdateInfoDto? = withContext(Dispatchers.IO) {
        runCatching {
            // Передаём текущую версию — сервер вернёт кумулятивный changelog
            // со всех версий, что вышли после установленной.
            val url = BuildConfig.API_BASE_URL + "app/update?currentVersion=${BuildConfig.VERSION_CODE}"
            val request = Request.Builder().url(url).build()
            val response = publicClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@withContext null
            val versionCode = data.optInt("versionCode", -1)
            if (versionCode < 0) return@withContext null
            val downloadUrl = data.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: return@withContext null
            val info = UpdateInfoDto(
                versionCode = versionCode,
                versionName = data.optString("versionName").takeIf { it.isNotBlank() },
                downloadUrl = downloadUrl,
                changelog = data.optString("changelog").takeIf { it.isNotBlank() },
            )
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                _updateAvailable.value = info
                info
            } else null
        }.onFailure { Timber.e(it, "Ошибка проверки обновления") }
            .getOrNull()
    }

    /**
     * Скачивает APK с прогрессом и запускает установку.
     *
     * Корутинно отменяемая: при cancel() coroutine — частично скачанный файл
     * удаляется. Прогресс репортится через [onProgress] не чаще ~10 раз/сек,
     * чтобы UI не моргал.
     *
     * @param onProgress (downloaded, total) — total = -1 если сервер не вернул Content-Length
     */
    suspend fun downloadAndInstall(
        downloadUrl: String,
        activityContext: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        dir.mkdirs()
        val file = File(dir, "update.apk")
        if (file.exists()) file.delete()

        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Пустое тело ответа")
            val total = body.contentLength()

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    onProgress(0L, total)
                    while (true) {
                        ensureActive() // Кооперативная отмена
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 100) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }

            // Запускаем установку через FileProvider
            withContext(Dispatchers.Main) {
                try {
                    val uri = FileProvider.getUriForFile(
                        activityContext,
                        "${activityContext.packageName}.fileprovider",
                        file,
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    activityContext.startActivity(intent)
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось запустить установщик")
                    Toast.makeText(activityContext, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
                    throw e
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Пользователь отменил — чистим частично скачанный APK
            runCatching { file.delete() }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка скачивания обновления")
            runCatching { file.delete() }
            withContext(Dispatchers.Main) {
                Toast.makeText(activityContext, "Ошибка скачивания: ${e.message}", Toast.LENGTH_LONG).show()
            }
            throw e
        }
    }
}
