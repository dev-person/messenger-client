package com.secure.messenger.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * Room query result for the chat list: каждый чат + последнее сообщение (если есть).
 * Заполняется через LEFT JOIN в ChatDao.observeAllWithLastMessage().
 */
data class ChatWithLastMessage(
    @Embedded val chat: ChatEntity,
    @ColumnInfo(name = "lastMsgId")       val lastMsgId: String?,
    @ColumnInfo(name = "lastMsgSenderId") val lastMsgSenderId: String?,
    @ColumnInfo(name = "lastMsgContent")  val lastMsgContent: String?,
    @ColumnInfo(name = "lastMsgType")     val lastMsgType: String?,
    @ColumnInfo(name = "lastMsgStatus")   val lastMsgStatus: String?,
    @ColumnInfo(name = "lastMsgTimestamp")val lastMsgTimestamp: Long?,
    @ColumnInfo(name = "lastMsgIsEdited") val lastMsgIsEdited: Boolean?,
)
