package com.secure.messenger.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "auth")

/** Provides the current auth token synchronously (for OkHttp interceptor). */
@Singleton
class AuthTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tokenKey = stringPreferencesKey("auth_token")

    val token: String?
        get() = runBlocking {
            context.authDataStore.data.map { it[tokenKey] }.first()
        }

    suspend fun saveToken(token: String) {
        context.authDataStore.edit { it[tokenKey] = token }
    }

    suspend fun clearToken() {
        context.authDataStore.edit { it.remove(tokenKey) }
    }

    fun hasToken(): Boolean = token != null
}
