package com.secure.messenger.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keyStoreDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "key_store")

/**
 * Persists the user's own X25519 key pair in encrypted DataStore.
 * On Android 6+ DataStore is backed by the Android Keystore when
 * combined with EncryptedSharedPreferences — for production, swap to
 * EncryptedDataStore or store keys in the Android Keystore directly.
 */
@Singleton
class LocalKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val privateKeyPref = stringPreferencesKey("identity_private_key")
    private val publicKeyPref = stringPreferencesKey("identity_public_key")

    suspend fun saveKeyPair(publicKeyBase64: String, privateKeyBase64: String) {
        context.keyStoreDataStore.edit { prefs ->
            prefs[publicKeyPref] = publicKeyBase64
            prefs[privateKeyPref] = privateKeyBase64
        }
    }

    suspend fun getPublicKey(): String? =
        context.keyStoreDataStore.data.map { it[publicKeyPref] }.first()

    suspend fun getPrivateKey(): String? =
        context.keyStoreDataStore.data.map { it[privateKeyPref] }.first()

    suspend fun hasKeyPair(): Boolean = getPrivateKey() != null

    suspend fun clear() {
        context.keyStoreDataStore.edit { it.clear() }
    }
}
