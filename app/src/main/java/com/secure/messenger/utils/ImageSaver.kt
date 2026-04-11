package com.secure.messenger.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Сохранение байтов картинки в галерею пользователя.
 *
 * На Android Q+ — через MediaStore без runtime permissions.
 * На старших версиях — в публичную папку Pictures (требует
 * WRITE_EXTERNAL_STORAGE на Android < 10, добавлено в манифест).
 */
object ImageSaver {

    private const val FOLDER = "GrizzlyMessenger"

    /**
     * Сохраняет байты как JPEG в галерею. Возвращает true при успехе.
     */
    fun save(context: Context, bytes: ByteArray, mimeType: String = "image/jpeg"): Boolean {
        val ext = if (mimeType.contains("png")) "png" else "jpg"
        val displayName = "grizzly_${System.currentTimeMillis()}.$ext"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bytes, displayName, mimeType)
            } else {
                @Suppress("DEPRECATION")
                saveLegacy(bytes, displayName)
            }
        }.onFailure {
            Timber.e(it, "ImageSaver.save failed")
        }.getOrDefault(false)
    }

    /** Android 10+ — MediaStore API без необходимости в WRITE_EXTERNAL_STORAGE. */
    private fun saveViaMediaStore(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return true
    }

    /** Android < 10 — пишем напрямую в /sdcard/Pictures/GrizzlyMessenger. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(bytes: ByteArray, displayName: String): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, displayName)
        FileOutputStream(file).use { it.write(bytes) }
        return file.exists()
    }
}
