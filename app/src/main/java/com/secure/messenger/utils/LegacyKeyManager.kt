package com.secure.messenger.utils

import android.util.Base64
import com.secure.messenger.domain.repository.AuthRepository
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управляет legacy X25519 приватными ключами пользователя — теми, которые
 * когда-то использовались, но были заменены (например, при установке пароля
 * шифрования случайный ключ был заменён на password-derived).
 *
 * Формат blob-а на сервере — JSON-массив:
 *   [{"enc": "base64(iv||ct||tag)"}, ...]
 *
 * `enc` — AES-256-GCM шифротекст приватного ключа X25519.
 * Ключ шифрования выводится из (телефон + текущий пароль) через PBKDF2.
 * Сервер пароля не знает — blob для него непрозрачен, E2E сохранён.
 *
 * При каждой смене пароля blob ПЕРЕШИФРОВЫВАЕТСЯ новым ключом, чтобы
 * старый пароль не оставался валидным для расшифровки списка.
 */
@Singleton
class LegacyKeyManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val authRepository: AuthRepository,
) {
    /**
     * Первичная установка пароля: текущий случайный privateKey шифруется
     * под новый пароль и загружается на сервер (вместе с уже существующими
     * legacy-ключами, если такие есть).
     *
     * @param currentRandomPrivateKeyBase64  приватный ключ ДО установки пароля
     * @param phone  телефон (часть KDF)
     * @param password  только что установленный пароль
     */
    suspend fun uploadCurrentKeyAsLegacy(
        currentRandomPrivateKeyBase64: String,
        phone: String,
        password: String,
    ) {
        runCatching {
            // Добавляем локально
            localKeyStore.addLegacyPrivateKey(currentRandomPrivateKeyBase64)

            // Собираем список ВСЕХ legacy-ключей (и добавленный только что тоже)
            val allLegacy = localKeyStore.getLegacyPrivateKeys()
            val sym = cryptoManager.derivePasswordSymmetricKey(phone, password)
            val blob = encodeLegacyList(allLegacy, sym)
            authRepository.putLegacyKeys(blob)
        }.onFailure { Timber.e(it, "uploadCurrentKeyAsLegacy failed") }
    }

    /**
     * Логин на новом устройстве: после успешной верификации пароля
     * скачиваем blob с сервера, расшифровываем и сохраняем ключи локально.
     * Они станут fallback-ом для расшифровки старых сообщений.
     */
    suspend fun downloadAndSaveLegacyKeys(phone: String, password: String) {
        runCatching {
            val blob = authRepository.getLegacyKeys().getOrNull() ?: return@runCatching
            if (blob.isBlank()) return@runCatching
            val sym = cryptoManager.derivePasswordSymmetricKey(phone, password)
            val keys = decodeLegacyList(blob, sym)
            if (keys.isNotEmpty()) {
                // Сохраняем локально, объединяя с уже имеющимися
                val existing = localKeyStore.getLegacyPrivateKeys().toMutableSet()
                existing.addAll(keys)
                localKeyStore.setLegacyPrivateKeys(existing.toList())
                Timber.d("Legacy keys: скачано ${keys.size}, всего локально ${existing.size}")
            }
        }.onFailure { Timber.e(it, "downloadAndSaveLegacyKeys failed") }
    }

    /**
     * Смена пароля: blob был зашифрован СТАРЫМ паролем. Пере-шифровываем
     * его новым паролем, добавляя туда же ТЕКУЩИЙ (pre-change) приватный
     * ключ — после смены он тоже станет «устаревшим».
     */
    suspend fun rotateLegacyKeysOnPasswordChange(
        phone: String,
        oldPassword: String,
        newPassword: String,
        currentPrivateKeyBase64: String,
    ) {
        runCatching {
            val oldSym = cryptoManager.derivePasswordSymmetricKey(phone, oldPassword)
            val newSym = cryptoManager.derivePasswordSymmetricKey(phone, newPassword)

            // 1. Скачиваем и расшифровываем существующий blob
            val existingBlob = authRepository.getLegacyKeys().getOrNull() ?: ""
            val existingKeys: List<String> = if (existingBlob.isBlank()) {
                emptyList()
            } else {
                decodeLegacyList(existingBlob, oldSym)
            }

            // 2. Добавляем текущий приватный ключ (он теперь тоже legacy)
            val combined = (existingKeys + currentPrivateKeyBase64).distinct()

            // 3. Сохраняем локально, шифруем новым паролем, льём на сервер
            localKeyStore.setLegacyPrivateKeys(combined)
            val newBlob = encodeLegacyList(combined, newSym)
            authRepository.putLegacyKeys(newBlob)
        }.onFailure { Timber.e(it, "rotateLegacyKeysOnPasswordChange failed") }
    }

    // ── Encode / Decode ──────────────────────────────────────────────────────

    private fun encodeLegacyList(privateKeysBase64: List<String>, symKey: ByteArray): String {
        val arr = JSONArray()
        for (priv in privateKeysBase64.distinct()) {
            val privBytes = Base64.decode(priv, Base64.NO_WRAP)
            val enc = cryptoManager.encryptBytes(privBytes, symKey)
            arr.put(JSONObject().put("enc", enc))
        }
        return arr.toString()
    }

    private fun decodeLegacyList(blob: String, symKey: ByteArray): List<String> {
        return runCatching {
            val arr = JSONArray(blob)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val enc = obj.optString("enc", "")
                if (enc.isEmpty()) continue
                val bytes = cryptoManager.decryptBytes(enc, symKey) ?: continue
                out.add(Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            out
        }.getOrDefault(emptyList())
    }
}
