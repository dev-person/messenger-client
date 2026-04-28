package com.secure.messenger.utils

import timber.log.Timber
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end encryption for messages.
 *
 * Protocol:
 *   1. Each user generates an X25519 key pair on first launch (stored in DataStore).
 *   2. Public keys are exchanged via the server (no secrecy needed for public keys).
 *   3. Per-conversation shared secret = X25519(myPrivateKey, theirPublicKey).
 *   4. Actual message key = HKDF(sharedSecret, salt=messageId, info="msg") → 256 bits.
 *   5. Message encrypted with AES-256-GCM (128-bit authentication tag).
 *
 * WebRTC calls use DTLS-SRTP (built into the WebRTC library) — no extra work needed.
 */
@Singleton
class CryptoManager @Inject constructor(
    private val keyStore: LocalKeyStore,
) {
    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12   // 96-bit IV recommended for AES-GCM
        private const val KEY_LENGTH_BYTES = 32  // AES-256
        private const val HKDF_INFO_MESSAGES = "secure-messenger-msg-v1"
        private const val PBKDF2_ITERATIONS = 100_000
    }

    private val secureRandom = SecureRandom()

    // ── Key Generation ────────────────────────────────────────────────────────

    /** Generates a new random X25519 key pair. Returns (publicKeyBytes, privateKeyBytes). */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()
        val public = (keyPair.public as X25519PublicKeyParameters).encoded
        val private = (keyPair.private as X25519PrivateKeyParameters).encoded
        return Pair(public, private)
    }

    /**
     * Derives a deterministic X25519 key pair from phone number + password.
     * Same phone + same password = same key pair on any device.
     *
     * Uses PBKDF2-HMAC-SHA256 (100k iterations) to derive 32 bytes of key material,
     * then uses it as X25519 private key seed. X25519 clamps the private key internally.
     */
    fun deriveKeyPairFromPassword(phone: String, password: String): Pair<ByteArray, ByteArray> {
        val salt = "secure-messenger-key-v1:$phone".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val seed = factory.generateSecret(spec).encoded

        val privateKey = X25519PrivateKeyParameters(seed)
        val publicKey = privateKey.generatePublicKey()
        return Pair(publicKey.encoded, privateKey.encoded)
    }

    /**
     * Выводит отдельный симметричный ключ AES-256 из телефон+пароль для шифрования
     * legacy-blob (старые X25519 приватные ключи) перед загрузкой на сервер.
     * Соль отличается от соли для пары ключей, чтобы не было пересечений
     * (разный KDF output для разных целей, даже при одинаковых входных данных).
     *
     * v1 — PBKDF2-HMAC-SHA256 100k iter. Оставлено для backward-compat с
     * пользователями, которые установили пароль до миграции на Argon2id.
     */
    fun derivePasswordSymmetricKey(phone: String, password: String): ByteArray {
        val salt = "secure-messenger-legacy-v1:$phone".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ── Argon2id (KDF v2) ─────────────────────────────────────────────────────
    //
    // Основная защита от brute-force атак с GPU/ASIC. PBKDF2 на 100k iter ~10 ms
    // на современной видеокарте — слабый пароль за часы. Argon2id memory-hard
    // (64 MiB) — каждая попытка требует 64 МБ памяти, GPU не масштабируется.
    //
    // Параметры (OWASP 2023 для interactive-нагрузки на mobile):
    //   m = 64 MiB, t = 3, p = 1 — около 200-500 ms на современном телефоне.

    private fun argon2idDerive(
        password: String,
        salt: ByteArray,
        outputBytes: Int,
    ): ByteArray {
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id,
        )
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(65_536) // 64 MiB
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(outputBytes)
        generator.generateBytes(password.toCharArray(), out)
        return out
    }

    /** v2: Argon2id-derived X25519 key pair. */
    fun deriveKeyPairFromPasswordV2(phone: String, password: String): Pair<ByteArray, ByteArray> {
        val salt = "secure-messenger-key-v2:$phone".toByteArray(Charsets.UTF_8)
        val seed = argon2idDerive(password, salt, KEY_LENGTH_BYTES)
        val privateKey = X25519PrivateKeyParameters(seed)
        val publicKey = privateKey.generatePublicKey()
        return Pair(publicKey.encoded, privateKey.encoded)
    }

    /** v2: Argon2id-derived symmetric key для legacy-blob. */
    fun derivePasswordSymmetricKeyV2(phone: String, password: String): ByteArray {
        val salt = "secure-messenger-legacy-v2:$phone".toByteArray(Charsets.UTF_8)
        return argon2idDerive(password, salt, KEY_LENGTH_BYTES)
    }

    /**
     * Шифрует произвольные байты AES-256-GCM ключом [key]. Возвращает строку
     * base64( iv || ciphertext || tag ). Используется для упаковки
     * legacy X25519 приватных ключей перед отправкой на сервер.
     */
    fun encryptBytes(data: ByteArray, key: ByteArray): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        val ct = cipher.doFinal(data)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** Расшифровка [encryptedBytes] (base64 iv||ct||tag) ключом [key]. null если неудача. */
    fun decryptBytes(encryptedBase64: String, key: ByteArray): ByteArray? = runCatching {
        val data = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val ct = data.copyOfRange(IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        cipher.doFinal(ct)
    }.getOrNull()

    // ── Shared Secret ─────────────────────────────────────────────────────────

    /**
     * Performs X25519 ECDH and derives a 256-bit shared secret.
     * Both peers derive the same secret without transmitting it.
     */
    fun computeSharedSecret(
        myPrivateKeyBytes: ByteArray,
        theirPublicKeyBytes: ByteArray,
    ): ByteArray {
        val agreement = X25519Agreement()
        val privateKey = X25519PrivateKeyParameters(myPrivateKeyBytes)
        agreement.init(privateKey)

        val publicKey = X25519PublicKeyParameters(theirPublicKeyBytes)
        val rawSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKey, rawSecret, 0)

        // Never use raw DH output directly — derive with HKDF
        return hkdf(
            inputKeyMaterial = rawSecret,
            salt = null,
            info = "secure-messenger-ecdh-v1".toByteArray(),
            outputLength = KEY_LENGTH_BYTES,
        )
    }

    // ── Message Encryption ────────────────────────────────────────────────────

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     *
     * @param sharedSecret  32-byte shared secret from [computeSharedSecret]
     * @param messageId     Unique message ID used as HKDF salt (prevents key reuse)
     * @return  IV (12 bytes) + ciphertext + GCM auth tag (16 bytes), Base64-encoded
     */
    /**
     * Каноническая AAD-строка, привязывающая контекст сообщения к шифротексту.
     * Без AAD сервер мог бы (теоретически) подменить metadata — например, тип
     * сообщения с TEXT на IMAGE, и клиент бы расшифровал что попало как картинку.
     * GCM-tag поймает любое искажение этих полей.
     */
    fun canonicalMessageAad(
        chatId: String,
        senderId: String,
        messageId: String,
        type: String,
    ): ByteArray = "$chatId|$senderId|$messageId|$type".toByteArray(Charsets.UTF_8)

    fun encryptMessage(
        plaintext: String,
        sharedSecret: ByteArray,
        messageId: String,
        aad: ByteArray? = null,
    ): String {
        // Derive a unique key for this specific message
        val messageKey = hkdf(
            inputKeyMaterial = sharedSecret,
            salt = messageId.toByteArray(),
            info = HKDF_INFO_MESSAGES.toByteArray(),
            outputLength = KEY_LENGTH_BYTES,
        )

        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(messageKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        // AAD привязывает контекст (chatId, senderId, type) к шифротексту.
        // Без AAD атакующий с доступом к серверу мог бы подменить metadata
        // (например, тип сообщения) — теперь GCM tag это поймает.
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext for storage
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }

    /**
     * Пытается расшифровать сообщение используя каждый shared-secret
     * по очереди. Возвращает первый успешный результат. Используется когда
     * у пользователя есть несколько приватных ключей (current + legacy), и
     * сообщение могло быть отправлено против любого из них.
     *
     * [aad] — если задан, сначала пробуем с ним; если не подошёл, fallback
     * без AAD (для расшифровки старых сообщений до AAD-эры).
     */
    fun decryptMessageWithAnyKey(
        encryptedBase64: String,
        sharedSecrets: List<ByteArray>,
        messageId: String,
        aad: ByteArray? = null,
    ): String? {
        for (secret in sharedSecrets) {
            val decrypted = decryptMessage(encryptedBase64, secret, messageId, aad)
            if (decrypted != null) return decrypted
        }
        return null
    }

    /**
     * Decrypts a message encrypted by [encryptMessage].
     * Returns null if decryption fails (tampered data / wrong key).
     *
     * Если [aad] задан — сначала пробуем с ним. При неудаче пробуем без AAD —
     * это путь для исторических сообщений, зашифрованных до того как AAD
     * добавили в схему. Стоимость дополнительной попытки — один GCM tag check
     * (микросекунды), потеря сообщений критичнее.
     */
    fun decryptMessage(
        encryptedBase64: String,
        sharedSecret: ByteArray,
        messageId: String,
        aad: ByteArray? = null,
    ): String? {
        if (aad != null && aad.isNotEmpty()) {
            decryptMessageInternal(encryptedBase64, sharedSecret, messageId, aad)?.let { return it }
        }
        // fallback на схему без AAD (или прямой путь, если AAD не задан)
        return decryptMessageInternal(encryptedBase64, sharedSecret, messageId, null)
    }

    private fun decryptMessageInternal(
        encryptedBase64: String,
        sharedSecret: ByteArray,
        messageId: String,
        aad: ByteArray?,
    ): String? = runCatching {
        val messageKey = hkdf(
            inputKeyMaterial = sharedSecret,
            salt = messageId.toByteArray(),
            info = HKDF_INFO_MESSAGES.toByteArray(),
            outputLength = KEY_LENGTH_BYTES,
        )

        val data = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = data.copyOfRange(IV_LENGTH_BYTES, data.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(messageKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.onFailure { e ->
        // GCM tag mismatch ожидаем часто (fallback на legacy keys, на схему
        // без AAD). Логируем как trace.
        Timber.v("decryptMessage: failed for msg=$messageId aad=${aad != null}: ${e.javaClass.simpleName}")
    }.getOrNull()

    // ── HKDF ──────────────────────────────────────────────────────────────────

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
