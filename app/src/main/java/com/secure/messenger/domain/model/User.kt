package com.secure.messenger.domain.model

data class User(
    val id: String,
    val phone: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isOnline: Boolean,
    val lastSeen: Long,
    // Public key for E2E encryption (X25519)
    val publicKey: String,
    val isContact: Boolean = false,
)
