package com.secure.messenger.data.repository

import android.content.Context
import android.provider.ContactsContract
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.model.User
import com.secure.messenger.domain.repository.ContactRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

/**
 * Данные телефонного контакта (имя + номер) до проверки регистрации в мессенджере.
 */
private data class DeviceContact(val name: String, val phone: String)

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
        val deviceContacts = readDeviceContacts()
        val phones = deviceContacts.map { it.phone }

        if (phones.isEmpty()) return@runCatching emptyList()

        // Спрашиваем сервер, какие номера зарегистрированы
        val registeredUsers = runCatching {
            api.lookupPhones(mapOf("phones" to phones)).data.orEmpty()
        }.getOrElse { e ->
            Timber.e(e, "lookupPhones failed")
            emptyList()
        }

        // Сохраняем зарегистрированных пользователей в локальную БД как контакты
        for (dto in registeredUsers) {
            val lastSeen = runCatching {
                java.time.Instant.parse(dto.lastSeen).toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())

            userDao.upsert(UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = lastSeen, publicKey = dto.publicKey, isContact = true,
            ))
        }

        val registeredPhoneSet = registeredUsers.associateBy { it.phone }
        // Дополнительная карта: номер с + → без +, и наоборот, для нечёткого совпадения
        val registeredPhoneNormalized = registeredUsers.associateBy { normalizePhone(it.phone) }

        // Собираем общий список: зарегистрированные + незарегистрированные
        val deviceNameByPhone = deviceContacts.associateBy({ it.phone }, { it.name })

        deviceContacts.map { dc ->
            val registered = registeredPhoneSet[dc.phone]
                ?: registeredPhoneNormalized[normalizePhone(dc.phone)]

            Contact(
                id = registered?.id ?: dc.phone,
                userId = registered?.id,
                displayName = registered?.displayName?.takeIf { it.isNotBlank() }
                    ?: dc.name.takeIf { it.isNotBlank() }
                    ?: dc.phone,
                phone = dc.phone,
                avatarUrl = registered?.avatarUrl,
                isRegistered = registered != null,
            )
        }
    }

    /**
     * Читает контакты с устройства — имя + номер телефона.
     * Использует NORMALIZED_NUMBER с фолбеком на NUMBER.
     */
    private fun readDeviceContacts(): List<DeviceContact> {
        val contacts = mutableMapOf<String, DeviceContact>() // phone → contact (дедупликация)
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        ) ?: return emptyList()

        cursor.use {
            val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val normalizedCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                // NORMALIZED_NUMBER — стандартизированный E.164, но может быть null
                val phone = it.getString(normalizedCol)?.trim()
                    ?: it.getString(numberCol)?.replace("[^+\\d]".toRegex(), "")?.trim()
                    ?: continue

                if (phone.isEmpty()) continue

                val name = it.getString(nameCol)?.trim() ?: ""
                // Сохраняем первое найденное имя для этого номера
                contacts.putIfAbsent(phone, DeviceContact(name, phone))
            }
        }
        return contacts.values.toList()
    }

    /** Нормализует номер: убирает всё кроме цифр, заменяет 8... на +7... (Россия) */
    private fun normalizePhone(phone: String): String {
        val digits = phone.replace("[^\\d]".toRegex(), "")
        return when {
            digits.startsWith("8") && digits.length == 11 -> "+7${digits.substring(1)}"
            digits.startsWith("7") && digits.length == 11 -> "+7${digits.substring(1)}"
            !phone.startsWith("+") -> "+$digits"
            else -> "+$digits"
        }
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
        val users = api.searchUsers(query.trimStart('@')).data.orEmpty()

        for (dto in users) {
            val existing = userDao.getById(dto.id)
            val lastSeen = runCatching {
                java.time.Instant.parse(dto.lastSeen).toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())

            userDao.upsert(UserEntity(
                id = dto.id, phone = dto.phone, username = dto.username,
                displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                bio = dto.bio, isOnline = dto.isOnline,
                lastSeen = lastSeen, publicKey = dto.publicKey,
                isContact = existing?.isContact ?: false,
            ))
        }

        users.map { it.toDomain() }
    }

    override fun buildInviteLink(phone: String): String =
        "https://securemessenger.app/invite?ref=$phone"
}
