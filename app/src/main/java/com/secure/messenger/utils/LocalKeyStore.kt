package com.secure.messenger.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's own X25519 key pair in EncryptedSharedPreferences,
 * backed by the Android Keystore via AES-256-GCM MasterKey.
 */
@Singleton
class LocalKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "encrypted_key_store"
        private const val KEY_PRIVATE = "identity_private_key"
        private const val KEY_PUBLIC = "identity_public_key"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveKeyPair(publicKeyBase64: String, privateKeyBase64: String) {
        prefs.edit()
            .putString(KEY_PUBLIC, publicKeyBase64)
            .putString(KEY_PRIVATE, privateKeyBase64)
            .apply()
    }

    fun getPublicKey(): String? = prefs.getString(KEY_PUBLIC, null)

    fun getPrivateKey(): String? = prefs.getString(KEY_PRIVATE, null)

    fun hasKeyPair(): Boolean = getPrivateKey() != null

    fun clear() {
        prefs.edit().clear().apply()
    }
}
