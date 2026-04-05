package com.secure.messenger.service

import android.util.Base64
import com.secure.messenger.data.local.dao.ChatDao
import com.secure.messenger.data.local.dao.MessageDao
import com.secure.messenger.data.local.dao.UserDao
import com.secure.messenger.data.local.entities.ChatEntity
import com.secure.messenger.data.local.entities.MessageEntity
import com.secure.messenger.data.local.entities.UserEntity
import com.secure.messenger.data.remote.api.MessengerApi
import com.secure.messenger.data.remote.websocket.SignalingMessage
import com.secure.messenger.domain.model.MessageStatus
import com.secure.messenger.domain.model.MessageType
import com.secure.messenger.utils.CryptoManager
import com.secure.messenger.utils.LocalKeyStore
import com.squareup.moshi.Moshi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Handles incoming real-time chat messages delivered over WebSocket.
 *
 * Responsibilities:
 *  1. Parse the raw WS JSON envelope.
 *  2. Ensure the sender's user record is in the local DB (fetches from server if missing).
 *  3. Ensure the chat entity is in the local DB (creates minimal entry if missing).
 *  4. Decrypt the message using X25519 ECDH + AES-256-GCM.
 *  5. Persist the decrypted message to Room.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val api: MessengerApi,
    private val cryptoManager: CryptoManager,
    private val localKeyStore: LocalKeyStore,
    private val moshi: Moshi,
) {
    private val adapter = moshi.adapter(SignalingMessage::class.java)

    suspend fun handle(json: String) {
        try {
            val msg = adapter.fromJson(json) ?: return
            val payload = msg.payload ?: return

            val chatId = payload["chatId"] as? String ?: return
            // REST delivery uses "id"; client WS delivery uses "messageId"
            val messageId = (payload["id"] ?: payload["messageId"]) as? String ?: return
            val encryptedContent = payload["encryptedContent"] as? String ?: return
            val senderId = payload["senderId"] as? String ?: return
            val timestampStr = payload["timestamp"] as? String

            // Skip duplicates (e.g. WS + REST both deliver the same message)
            if (messageDao.getById(messageId) != null) return

            // Ensure sender exists locally with their public key for decryption
            val sender = userDao.getById(senderId) ?: run {
                val dto = api.getUserById(senderId).data ?: run {
                    Timber.w("IncomingMessageHandler: sender $senderId not found on server")
                    return
                }
                UserEntity(
                    id = dto.id, phone = dto.phone, username = dto.username,
                    displayName = dto.displayName, avatarUrl = dto.avatarUrl,
                    bio = dto.bio, isOnline = dto.isOnline,
                    lastSeen = Instant.parse(dto.lastSeen).toEpochMilli(),
                    publicKey = dto.publicKey, isContact = false,
                ).also { userDao.upsert(it) }
            }

            // Ensure chat exists locally so the UI can display it
            if (chatDao.getById(chatId) == null) {
                chatDao.upsert(ChatEntity(
                    id = chatId, type = "DIRECT",
                    title = sender.displayName,
                    avatarUrl = sender.avatarUrl,
                    unreadCount = 1,
                    isPinned = false, isMuted = false,
                    updatedAt = System.currentTimeMillis(),
                    otherUserId = senderId,
                ))
            }

            // Decrypt
            val myPrivateKeyBase64 = localKeyStore.getPrivateKey() ?: run {
                Timber.w("IncomingMessageHandler: no local private key")
                return
            }
            val myPrivateKeyBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
            val theirPublicKeyBytes = Base64.decode(sender.publicKey, Base64.NO_WRAP)
            val sharedSecret = cryptoManager.computeSharedSecret(myPrivateKeyBytes, theirPublicKeyBytes)
            val decryptedContent = cryptoManager.decryptMessage(encryptedContent, sharedSecret, messageId)
                ?: "[Не удалось расшифровать]"

            val timestamp = if (timestampStr != null) {
                try { Instant.parse(timestampStr).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
            } else System.currentTimeMillis()

            // Persist
            messageDao.upsert(MessageEntity(
                id = messageId, chatId = chatId, senderId = senderId,
                encryptedContent = encryptedContent, decryptedContent = decryptedContent,
                type = MessageType.TEXT.name, status = MessageStatus.DELIVERED.name,
                timestamp = timestamp,
                replyToId = null, mediaUrl = null, isEdited = false,
            ))

        } catch (e: Exception) {
            Timber.e(e, "IncomingMessageHandler: failed to process message")
        }
    }
}
