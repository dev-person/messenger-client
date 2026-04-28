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
 * когда-то использовались, но были заменены при смене пароля.
 *
 * Формат blob-а на сервере:
 *   v1 (старый): JSON-массив `[{"enc":"base64(iv||ct||tag)"}, ...]`
 *   v2 (новый):  JSON-объект `{"v":2,"keys":[{"enc":"..."}, ...]}`
 *
 * Версия определяет KDF, которым выводится симметричный ключ для шифрования
 * legacy и identity-keypair'а:
 *   v1 → PBKDF2-HMAC-SHA256 (100k iter)
 *   v2 → Argon2id (m=64MiB, t=3, p=1)
 *
 * Argon2id защищает от GPU brute-force несравнимо лучше — поэтому при ЛЮБОЙ
 * смене пароля автоматически мигрируем на v2. Старые юзеры без смены пароля
 * остаются на v1, но при следующей смене перейдут.
 *
 * Сервер пароля и KDF не знает — blob для него непрозрачен, E2E сохранён.
 */
@Singleton
class LegacyKeyManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val authRepository: AuthRepository,
) {
    /**
     * Версия blob'а на сервере. 1 — PBKDF2 (исторический), 2 — Argon2id (новый).
     * Используется и для ключа shifroвания blob-а, и для derive identity-keypair'а
     * (оба должны идти одной KDF — иначе на новом устройстве не сойдётся keypair).
     */
    enum class KdfVersion(val value: Int) {
        V1_PBKDF2(1),
        V2_ARGON2ID(2);

        companion object {
            fun fromInt(v: Int): KdfVersion = entries.firstOrNull { it.value == v } ?: V1_PBKDF2
        }
    }

    /**
     * Определяет KDF-версию по содержимому blob-а. Пустой/некорректный → V1
     * (это безопасный дефолт для старых клиентов).
     */
    fun detectVersion(blob: String): KdfVersion {
        if (blob.isBlank()) return KdfVersion.V1_PBKDF2
        val trimmed = blob.trimStart()
        if (!trimmed.startsWith("{")) return KdfVersion.V1_PBKDF2
        return runCatching {
            KdfVersion.fromInt(JSONObject(blob).optInt("v", 1))
        }.getOrDefault(KdfVersion.V1_PBKDF2)
    }

    /** Симметричный ключ legacy-blob'а под нужную версию. */
    private fun deriveLegacyKey(version: KdfVersion, phone: String, password: String): ByteArray =
        when (version) {
            KdfVersion.V1_PBKDF2 -> cryptoManager.derivePasswordSymmetricKey(phone, password)
            KdfVersion.V2_ARGON2ID -> cryptoManager.derivePasswordSymmetricKeyV2(phone, password)
        }

    /** Identity X25519 keypair под нужную версию. */
    fun deriveIdentityKeypair(
        version: KdfVersion,
        phone: String,
        password: String,
    ): Pair<ByteArray, ByteArray> = when (version) {
        KdfVersion.V1_PBKDF2 -> cryptoManager.deriveKeyPairFromPassword(phone, password)
        KdfVersion.V2_ARGON2ID -> cryptoManager.deriveKeyPairFromPasswordV2(phone, password)
    }

    /**
     * Первичная установка пароля: текущий случайный privateKey шифруется
     * под новый пароль (V2 — все новые blob-ы пишем в Argon2id) и загружается
     * на сервер. На старых клиентах функция не вызывалась бы — это код для
     * v2-эры.
     */
    suspend fun uploadCurrentKeyAsLegacy(
        currentRandomPrivateKeyBase64: String,
        phone: String,
        password: String,
    ) {
        runCatching {
            localKeyStore.addLegacyPrivateKey(currentRandomPrivateKeyBase64)
            val allLegacy = localKeyStore.getLegacyPrivateKeys()
            val sym = deriveLegacyKey(KdfVersion.V2_ARGON2ID, phone, password)
            val blob = encodeLegacyList(allLegacy, sym, KdfVersion.V2_ARGON2ID)
            authRepository.putLegacyKeys(blob)
        }.onFailure { Timber.e(it, "uploadCurrentKeyAsLegacy failed") }
    }

    /**
     * Логин на новом устройстве: тянем blob с сервера, определяем версию
     * KDF из формата blob-а, расшифровываем и сохраняем legacy-ключи локально.
     * Возвращаем версию — caller использует ту же KDF для derive identity
     * keypair (иначе keypair не сойдётся с тем что на других устройствах).
     */
    suspend fun downloadAndSaveLegacyKeys(
        phone: String,
        password: String,
    ): KdfVersion {
        return runCatching {
            val blob = authRepository.getLegacyKeys().getOrNull() ?: return@runCatching KdfVersion.V1_PBKDF2
            if (blob.isBlank()) return@runCatching KdfVersion.V1_PBKDF2
            val version = detectVersion(blob)
            val sym = deriveLegacyKey(version, phone, password)
            val keys = decodeLegacyList(blob, sym, version)
            if (keys.isNotEmpty()) {
                val existing = localKeyStore.getLegacyPrivateKeys().toMutableSet()
                existing.addAll(keys)
                localKeyStore.setLegacyPrivateKeys(existing.toList())
                Timber.d("Legacy keys: скачано ${keys.size} (v=${version.value}), всего локально ${existing.size}")
            }
            version
        }.onFailure {
            Timber.e(it, "downloadAndSaveLegacyKeys failed")
        }.getOrDefault(KdfVersion.V1_PBKDF2)
    }

    /**
     * Смена пароля: blob был зашифрован старым паролем (под версией X), пере-
     * шифровываем НОВЫМ паролем и ВСЕГДА в формат v2 (Argon2id) — auto-миграция.
     * Добавляем туда же текущий (pre-change) приватный ключ — после смены он
     * тоже становится legacy.
     */
    suspend fun rotateLegacyKeysOnPasswordChange(
        phone: String,
        oldPassword: String,
        newPassword: String,
        currentPrivateKeyBase64: String,
    ) {
        runCatching {
            val existingBlob = authRepository.getLegacyKeys().getOrNull() ?: ""
            val existingKeys: List<String> = if (existingBlob.isBlank()) {
                emptyList()
            } else {
                val oldVersion = detectVersion(existingBlob)
                val oldSym = deriveLegacyKey(oldVersion, phone, oldPassword)
                decodeLegacyList(existingBlob, oldSym, oldVersion)
            }

            val combined = (existingKeys + currentPrivateKeyBase64).distinct()

            // Сохраняем локально и заливаем v2-blob новым паролем
            localKeyStore.setLegacyPrivateKeys(combined)
            val newSym = deriveLegacyKey(KdfVersion.V2_ARGON2ID, phone, newPassword)
            val newBlob = encodeLegacyList(combined, newSym, KdfVersion.V2_ARGON2ID)
            authRepository.putLegacyKeys(newBlob)
        }.onFailure { Timber.e(it, "rotateLegacyKeysOnPasswordChange failed") }
    }

    // ── Encode / Decode ──────────────────────────────────────────────────────

    private fun encodeLegacyList(
        privateKeysBase64: List<String>,
        symKey: ByteArray,
        version: KdfVersion,
    ): String {
        val arr = JSONArray()
        for (priv in privateKeysBase64.distinct()) {
            val privBytes = Base64.decode(priv, Base64.NO_WRAP)
            val enc = cryptoManager.encryptBytes(privBytes, symKey)
            arr.put(JSONObject().put("enc", enc))
        }
        return when (version) {
            KdfVersion.V1_PBKDF2 -> arr.toString() // legacy формат — оставляем как есть
            KdfVersion.V2_ARGON2ID -> JSONObject().put("v", 2).put("keys", arr).toString()
        }
    }

    private fun decodeLegacyList(
        blob: String,
        symKey: ByteArray,
        version: KdfVersion,
    ): List<String> = runCatching {
        val arr = when (version) {
            KdfVersion.V1_PBKDF2 -> JSONArray(blob)
            KdfVersion.V2_ARGON2ID -> JSONObject(blob).getJSONArray("keys")
        }
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
