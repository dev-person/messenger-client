package com.secure.messenger.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.secure.messenger.BuildConfig
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.api.UpdateInfoDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
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
    private val api: MessengerApi,
    private val okHttpClient: OkHttpClient,
) {
    private val _updateAvailable = MutableStateFlow<UpdateInfoDto?>(null)
    val updateAvailable = _updateAvailable.asStateFlow()

    /**
     * Проверяет наличие обновления на сервере.
     * Возвращает [UpdateInfoDto] если есть более новая версия, иначе null.
     * Также обновляет StateFlow для подписчиков.
     */
    suspend fun checkForUpdate(): UpdateInfoDto? = withContext(Dispatchers.IO) {
        runCatching {
            val info = api.getUpdateInfo().data ?: return@withContext null
            val currentCode = BuildConfig.VERSION_CODE
            if (info.versionCode > currentCode && !info.downloadUrl.isNullOrBlank()) {
                _updateAvailable.value = info
                info
            } else null
        }.onFailure { Timber.e(it, "Ошибка проверки обновления") }
            .getOrNull()
    }

    /**
     * Скачивает APK по указанному URL в кеш и запускает установку.
     */
    suspend fun downloadAndInstall(downloadUrl: String, activityContext: Context) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "update.apk")

                // Скачиваем APK
                val request = Request.Builder().url(downloadUrl).build()
                val response = okHttpClient.newCall(request).execute()
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Timber.e("UpdateManager: пустое тело ответа при скачивании APK")
                    return@withContext
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
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка скачивания обновления")
                withContext(Dispatchers.Main) {
                    Toast.makeText(activityContext, "Ошибка скачивания: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
