package com.secure.messenger.utils

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
    fun encryptMessage(
        plaintext: String,
        sharedSecret: ByteArray,
        messageId: String,
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
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext for storage
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypts a message encrypted by [encryptMessage].
     * Returns null if decryption fails (tampered data / wrong key).
     */
    fun decryptMessage(
        encryptedBase64: String,
        sharedSecret: ByteArray,
        messageId: String,
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
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
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
