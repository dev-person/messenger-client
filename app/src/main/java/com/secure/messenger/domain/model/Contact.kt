package com.secure.messenger.domain.model

data class Contact(
    val id: String,
    val userId: String?,        // null if not registered in the app
    val displayName: String,
    val phone: String,
    val avatarUrl: String?,
    val isRegistered: Boolean,  // true if this phone number is a registered user
)
