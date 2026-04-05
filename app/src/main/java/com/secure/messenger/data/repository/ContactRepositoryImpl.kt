package com.secure.messenger.data.repository

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ContactRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MessengerApi,
    private val userDao: UserDao,
) : ContactRepository {

    override fun observeContacts(): Flow<List<Contact>> =
        userDao.observeContacts().map { users ->
            users.map { u ->
                Contact(
                    id = u.id, userId = u.id, displayName = u.displayName,
                    phone = u.phone, avatarUrl = u.avatarUrl, isRegistered = true,
                )
            }
        }

    override suspend fun syncPhoneContacts(): Result<List<Contact>> = runCatching {
        val devicePhones = readDevicePhoneNumbers()

        // Ask server which of these phones are registered
        val registeredUsers = api.lookupPhones(mapOf("phones" to devicePhones))
            .data.orEmpty()

        // Persist registered users in local DB
        // isContact = true: контакты из телефонной книги считаются контактами
        userDao.upsertAll(registeredUsers.map { dto ->
            UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                publicKey = dto.publicKey, isContact = true,
            )
        })

        val registeredPhoneSet = registeredUsers.associate { it.phone to it }

        // Build combined list: registered + unregistered contacts
        devicePhones.map { phone ->
            val registered = registeredPhoneSet[phone]
            Contact(
                id = registered?.id ?: phone,
                userId = registered?.id,
                displayName = registered?.displayName ?: phone,
                phone = phone,
                avatarUrl = registered?.avatarUrl,
                isRegistered = registered != null,
            )
        }
    }

    /** Reads all phone numbers from the device address book. */
    private fun readDevicePhoneNumbers(): List<String> {
        val phones = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER),
            null, null, null,
        ) ?: return phones

        cursor.use {
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (it.moveToNext()) {
                val number = it.getString(numberCol)?.trim()
                if (!number.isNullOrEmpty()) phones.add(number)
            }
        }
        return phones.distinct()
    }

    override suspend fun addContact(userId: String): Result<Contact> = runCatching {
        api.addContact(userId)
        val user = userDao.getById(userId) ?: error("User not found locally")
        userDao.upsert(user.copy(isContact = true))
        Contact(id = userId, userId = userId, displayName = user.displayName,
            phone = user.phone, avatarUrl = user.avatarUrl, isRegistered = true)
    }

    override suspend fun removeContact(userId: String): Result<Unit> = runCatching {
        api.removeContact(userId)
        val user = userDao.getById(userId) ?: return@runCatching
        userDao.upsert(user.copy(isContact = false))
    }

    override suspend fun searchUsers(query: String): Result<List<User>> = runCatching {
        // Пользователи могут вводить @username — убираем @ перед отправкой на сервер,
        // так как в БД usernames хранятся без префикса.
        val users = api.searchUsers(query.trimStart('@')).data.orEmpty()

        // Кешируем в локальный DB — нужно для последующего шифрования сообщений.
        // Сохраняем isContact из существующей записи, чтобы не потерять флаг контакта.
        for (dto in users) {
            val existing = userDao.getById(dto.id)
            userDao.upsert(UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                publicKey = dto.publicKey, isContact = existing?.isContact ?: false,
            ))
        }

        users.map { it.toDomain() }
    }

    override fun buildInviteLink(phone: String): String =
        "https://securemessenger.app/invite?ref=$phone"
}
