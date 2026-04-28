package com.secure.messenger.utils

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Криптография для групповых чатов (Sender Keys, 1.0.68).
 *
 * Алгоритм (упрощённый Signal-like, без rachet'а внутри epoch):
 *
 *   1. Каждый участник генерирует свой [senderKey] — 32 случайных байта.
 *      Ключ используется для шифрования ВСЕХ его сообщений в текущем epoch'е.
 *
 *   2. Владелец шифрует свой senderKey для каждого получателя отдельно:
 *        sharedSecret = X25519(myPrivate, recipientPublic)
 *        wrapKey      = HKDF(sharedSecret, salt=chatId, info="group-sk-wrap-v1")
 *        encrypted    = AES-GCM(wrapKey, iv=random(12), plaintext=senderKey)
 *      Результат (iv || ct || tag, base64) улетает на сервер и достаётся
 *      получателю через REST/WS.
 *
 *   3. Получатель разворачивает обратной операцией, обменивая ролями
 *      public/private ключи, и кладёт расшифрованный senderKey себе в БД
 *      (GroupSenderKeyEntity).
 *
 *   4. Шифрование сообщения:
 *        messageKey = HKDF(senderKey, salt=messageId, info="group-msg-v1")
 *        ct         = AES-GCM(messageKey, iv=random(12), plaintext=msg)
 *      На проводе — base64(iv || ct || tag).
 *
 *   5. Дешифровка — зеркально: получатель берёт senderKey отправителя из
 *      локальной БД (по chatId/ownerId/epoch), деривит messageKey, AES-GCM
 *      с указанным iv.
 *
 * Используем те же примитивы что [CryptoManager] — BouncyCastle X25519 +
 * HKDF-SHA256 + AES-256-GCM — специально, чтобы не разводить параллельные
 * крипто-стеки. Разница только в salt/info-строках, чтобы выводы KDF для
 * групповых сообщений и direct-сообщений не пересекались.
 */
@Singleton
class GroupCryptoManager @Inject constructor() {
    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val KEY_LENGTH_BYTES = 32

        private const val HKDF_INFO_WRAP = "grizzly-group-sk-wrap-v1"
        private const val HKDF_INFO_MSG = "grizzly-group-msg-v1"
    }

    private val secureRandom = SecureRandom()

    /** 32 случайных байта — новый sender key. */
    fun generateSenderKey(): ByteArray =
        ByteArray(KEY_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    // ── Wrap / unwrap sender key между участниками группы ─────────────────

    /**
     * Оборачивает [senderKey] для одного получателя. Возвращает
     * base64(iv || ciphertext || gcmTag) для отправки на сервер.
     *
     * [chatId] служит частью соли HKDF — без него один и тот же вывод KDF
     * был бы у всех чатов между одной парой ключей, что облегчало бы
     * атаку по анализу метаданных.
     */
    fun wrapSenderKey(
        senderKey: ByteArray,
        recipientPublicKey: ByteArray,
        myPrivateKey: ByteArray,
        chatId: String,
    ): String {
        val wrapKey = deriveWrapKey(myPrivateKey, recipientPublicKey, chatId)
        return aesGcmEncrypt(senderKey, wrapKey)
    }

    /**
     * Обратная операция. Принимает base64-строку, возвращает 32-байтный
     * senderKey; null если дешифровка не удалась (повреждение, чужой ключ).
     */
    fun unwrapSenderKey(
        encryptedBase64: String,
        ownerPublicKey: ByteArray,
        myPrivateKey: ByteArray,
        chatId: String,
    ): ByteArray? = runCatching {
        val wrapKey = deriveWrapKey(myPrivateKey, ownerPublicKey, chatId)
        aesGcmDecrypt(encryptedBase64, wrapKey)
    }.getOrNull()

    private fun deriveWrapKey(
        myPrivateKey: ByteArray,
        theirPublicKey: ByteArray,
        chatId: String,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPrivateKey))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKey), shared, 0)
        return hkdf(
            inputKeyMaterial = shared,
            salt = chatId.toByteArray(Charsets.UTF_8),
            info = HKDF_INFO_WRAP.toByteArray(Charsets.UTF_8),
            outputLength = KEY_LENGTH_BYTES,
        )
    }

    // ── Шифрование / дешифровка сообщений ─────────────────────────────────

    /**
     * Шифрует [plaintext] под [senderKey], используя [messageId] как соль
     * для деривации messageKey — это защищает от повторного использования
     * одного iv+key pair на разных сообщениях.
     */
    fun encryptGroupMessage(
        plaintext: String,
        senderKey: ByteArray,
        messageId: String,
        aad: ByteArray? = null,
    ): String {
        val messageKey = hkdf(
            inputKeyMaterial = senderKey,
            salt = messageId.toByteArray(Charsets.UTF_8),
            info = HKDF_INFO_MSG.toByteArray(Charsets.UTF_8),
            outputLength = KEY_LENGTH_BYTES,
        )
        return aesGcmEncrypt(plaintext.toByteArray(Charsets.UTF_8), messageKey, aad)
    }

    /**
     * null если дешифровка не удалась. Если [aad] задан — пробуем сначала
     * с ним, потом без (fallback на pre-AAD сообщения). См. CryptoManager.
     */
    fun decryptGroupMessage(
        encryptedBase64: String,
        senderKey: ByteArray,
        messageId: String,
        aad: ByteArray? = null,
    ): String? {
        if (aad != null && aad.isNotEmpty()) {
            decryptGroupInternal(encryptedBase64, senderKey, messageId, aad)?.let { return it }
        }
        return decryptGroupInternal(encryptedBase64, senderKey, messageId, null)
    }

    private fun decryptGroupInternal(
        encryptedBase64: String,
        senderKey: ByteArray,
        messageId: String,
        aad: ByteArray?,
    ): String? = runCatching {
        val messageKey = hkdf(
            inputKeyMaterial = senderKey,
            salt = messageId.toByteArray(Charsets.UTF_8),
            info = HKDF_INFO_MSG.toByteArray(Charsets.UTF_8),
            outputLength = KEY_LENGTH_BYTES,
        )
        String(aesGcmDecrypt(encryptedBase64, messageKey, aad), Charsets.UTF_8)
    }.onFailure { e ->
        timber.log.Timber.v(
            "decryptGroupMessage: failed for msg=$messageId aad=${aad != null}: ${e.javaClass.simpleName}",
        )
    }.getOrNull()

    // ── Внутренности ──────────────────────────────────────────────────────

    private fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, aad: ByteArray? = null): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(encryptedBase64: String, key: ByteArray, aad: ByteArray? = null): ByteArray {
        val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val ct = data.copyOfRange(IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    private fun hkdf(
        inputKeyMaterial: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(outputLength)
        generator.generateBytes(output, 0, outputLength)
        return output
    }
}
