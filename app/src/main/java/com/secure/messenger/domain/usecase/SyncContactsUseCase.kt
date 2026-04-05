package com.secure.messenger.domain.usecase

import com.secure.messenger.domain.model.Contact
import com.secure.messenger.domain.repository.ContactRepository
import javax.inject.Inject

class SyncContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(): Result<List<Contact>> =
        contactRepository.syncPhoneContacts()
}
