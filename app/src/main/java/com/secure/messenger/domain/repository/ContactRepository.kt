package com.secure.messenger.domain.repository

import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.model.User
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>

    /** Reads device address book and checks which numbers are registered users. */
    suspend fun syncPhoneContacts(): Result<List<Contact>>

    suspend fun addContact(userId: String): Result<Contact>
    suspend fun removeContact(userId: String): Result<Unit>
    suspend fun searchUsers(query: String): Result<List<User>>

    /** Generate an invite link / SMS for a non-registered contact. */
    fun buildInviteLink(phone: String): String
}
