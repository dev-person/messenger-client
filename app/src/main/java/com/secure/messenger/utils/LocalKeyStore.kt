package com.secure.messenger.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's own X25519 key pair in EncryptedSharedPreferences,
 * backed by the Android Keystore via AES-256-GCM MasterKey.
 *
 * При первом запуске после обновления мигрирует ключи из старого DataStore.
 */
@Singleton
class LocalKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "encrypted_key_store"
        private const val KEY_PRIVATE = "identity_private_key"
        private const val KEY_PUBLIC = "identity_public_key"
        private const val KEY_FROM_PASSWORD = "key_from_password"
        private const val KEY_OWNER_ID = "key_owner_user_id"
        /**
         * Список legacy приватных ключей X25519 в base64, разделённых `;`.
         * Каждый ключ тут был когда-то «текущим» у этого пользователя —
         * сохраняется для расшифровки старых сообщений после смены ключа
         * (установка/смена пароля шифрования).
         */
        private const val KEY_LEGACY_PRIVATE_KEYS = "legacy_private_keys"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val encPrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        // Миграция из старого DataStore → EncryptedSharedPreferences (одноразово)
        if (encPrefs.getString(KEY_PRIVATE, null) == null) {
            migrateFromDataStore(encPrefs)
        }

        encPrefs
    }

    private fun migrateFromDataStore(target: SharedPreferences) {
        try {
            val legacyFile = context.filesDir.resolve("datastore/key_store.preferences_pb")
            if (!legacyFile.exists()) return

            val ds = PreferenceDataStoreFactory.create {
                legacyFile
            }

            val privKeyPref = stringPreferencesKey(KEY_PRIVATE)
            val pubKeyPref = stringPreferencesKey(KEY_PUBLIC)

            val (privKey, pubKey) = runBlocking {
                val data = ds.data.first()
                data[privKeyPref] to data[pubKeyPref]
            }

            if (privKey != null && pubKey != null) {
                target.edit()
                    .putString(KEY_PRIVATE, privKey)
                    .putString(KEY_PUBLIC, pubKey)
                    .commit()
                Timber.d("LocalKeyStore: migrated keys from DataStore to EncryptedSharedPreferences")
            }

            legacyFile.delete()
        } catch (e: Exception) {
            Timber.w(e, "LocalKeyStore: DataStore migration failed")
        }
    }

    fun saveKeyPair(publicKeyBase64: String, privateKeyBase64: String, fromPassword: Boolean = false) {
        prefs.edit()
            .putString(KEY_PUBLIC, publicKeyBase64)
            .putString(KEY_PRIVATE, privateKeyBase64)
            .putBoolean(KEY_FROM_PASSWORD, fromPassword)
            .apply()
    }

    /** Привязывает ключ к конкретному пользователю. */
    fun setOwner(userId: String) {
        prefs.edit().putString(KEY_OWNER_ID, userId).apply()
    }

    /** true если ключ принадлежит указанному пользователю. */
    fun isOwner(userId: String): Boolean = prefs.getString(KEY_OWNER_ID, null) == userId

    /** true если ключ сгенерирован из пароля (детерминированный), false если случайный. */
    fun isKeyFromPassword(): Boolean = prefs.getBoolean(KEY_FROM_PASSWORD, false)

    fun getPublicKey(): String? = prefs.getString(KEY_PUBLIC, null)

    fun getPrivateKey(): String? = prefs.getString(KEY_PRIVATE, null)

    fun hasKeyPair(): Boolean = getPrivateKey() != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    // ── Legacy keys ──────────────────────────────────────────────────────────

    /**
     * Возвращает список legacy приватных ключей (base64, без padding).
     * Дубликаты отфильтрованы. Используется для попыток расшифровки старых
     * сообщений после смены ключа.
     */
    fun getLegacyPrivateKeys(): List<String> {
        val raw = prefs.getString(KEY_LEGACY_PRIVATE_KEYS, null) ?: return emptyList()
        return raw.split(';').filter { it.isNotEmpty() }.distinct()
    }

    /** Перезаписывает список legacy ключей целиком. */
    fun setLegacyPrivateKeys(keys: List<String>) {
        val normalized = keys.distinct().filter { it.isNotEmpty() }
        prefs.edit()
            .putString(KEY_LEGACY_PRIVATE_KEYS, normalized.joinToString(";"))
            .apply()
    }

    /**
     * Добавляет новый legacy-ключ к существующему списку. Идемпотентно —
     * повторное добавление того же ключа не дублирует запись.
     */
    fun addLegacyPrivateKey(privateKeyBase64: String) {
        if (privateKeyBase64.isEmpty()) return
        val current = getLegacyPrivateKeys().toMutableSet()
        current.add(privateKeyBase64)
        setLegacyPrivateKeys(current.toList())
    }
}
