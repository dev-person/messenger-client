package com.secure.messenger.data.local.entities

import androidx.room.Entity

/**
 * Локальное хранилище расшифрованных sender-ключей группы.
 *
 * Каждая строка — один sender key [ownerId] для группы [chatId] в рамках
 * конкретного [epoch]. У клиента хранится N-1 чужих sender-ключей плюс свой
 * собственный. Свой key тоже пишется сюда, чтобы при отправке не надо было
 * расшифровывать его из серверного хранилища (там он лежит зашифрованный
 * для каждого получателя отдельно).
 *
 * Сами байты [senderKey] — 32-байтный ChaCha key в base64. Хранятся в
 * открытом виде, т.к. локальная БД уже защищена Android'ом; отдельное
 * оборачивание Android Keystore'ом избыточно для 1.0.68.
 */
@Entity(tableName = "group_sender_keys", primaryKeys = ["chatId", "ownerId", "epoch"])
data class GroupSenderKeyEntity(
    val chatId: String,
    val ownerId: String,
    val epoch: Int,
    val senderKey: String,
    val createdAt: Long,
)
