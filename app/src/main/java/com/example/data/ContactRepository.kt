package com.example.data

import kotlinx.coroutines.flow.Flow

class ContactRepository(private val contactDao: ContactDao) {
    val allContacts: Flow<List<ContactEntity>> = contactDao.getAllContacts()

    suspend fun getContactById(id: Int): ContactEntity? {
        return contactDao.getContactById(id)
    }

    suspend fun insertContact(contact: ContactEntity): Long {
        return contactDao.insertContact(contact)
    }

    suspend fun updateContact(contact: ContactEntity) {
        contactDao.updateContact(contact)
    }

    suspend fun deleteContact(contact: ContactEntity) {
        contactDao.deleteContact(contact)
    }

    suspend fun deleteContactById(id: Int) {
        contactDao.deleteContactById(id)
    }
}
