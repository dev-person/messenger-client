package com.secure.messenger.di

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

/** Provides the current auth token synchronously (for OkHttp interceptor). */
@Singleton
class AuthTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "encrypted_auth"
        private const val KEY_TOKEN = "auth_token"
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

        // Миграция из старого DataStore (auth.preferences_pb)
        if (encPrefs.getString(KEY_TOKEN, null) == null) {
            try {
                val legacyFile = context.filesDir.resolve("datastore/auth.preferences_pb")
                if (legacyFile.exists()) {
                    val ds = PreferenceDataStoreFactory.create { legacyFile }
                    val oldToken = runBlocking { ds.data.first()[stringPreferencesKey(KEY_TOKEN)] }
                    if (oldToken != null) {
                        encPrefs.edit().putString(KEY_TOKEN, oldToken).commit()
                        Timber.d("AuthTokenProvider: migrated token from DataStore")
                    }
                    legacyFile.delete()
                }
            } catch (e: Exception) {
                Timber.w(e, "AuthTokenProvider: DataStore migration failed")
            }
        }

        encPrefs
    }

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun hasToken(): Boolean = token != null
}
