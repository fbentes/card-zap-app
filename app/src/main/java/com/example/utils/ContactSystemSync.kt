package com.example.utils

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.ContactEntity

object ContactSystemSync {

    private const val TAG = "ContactSystemSync"
    const val MARKER_TAG = "#CardZap"

    // Check if the app has both read and write contact permissions
    fun hasContactsPermissions(context: Context): Boolean {
        val readPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val writePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return readPerm && writePerm
    }

    // Insert a contact directly into the Android system's native phonebook
    fun insertSystemContact(
        context: Context,
        name: String,
        primaryPhone: String,
        secondaryPhone: String,
        address: String,
        instagram: String,
        observations: String,
        imageBase64: String,
        useWhatsAppBusiness: Boolean = false
    ): Long? {
        val resolver = context.contentResolver
        val ops = ArrayList<ContentProviderOperation>()

        // 1. Create a Raw Contact
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        // 2. Insert Name
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        // 3. Insert Primary Phone
        if (primaryPhone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, primaryPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }

        // 4. Insert Secondary Phone
        if (secondaryPhone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, secondaryPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                .build())
        }

        // 5. Insert Postal Address
        if (address.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, address)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
                .build())
        }

        // 6. Insert Photo
        if (imageBase64.isNotBlank()) {
            try {
                val decodedBytes = Base64.decode(imageBase64, Base64.DEFAULT)
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, decodedBytes)
                    .build())
            } catch (e: Exception) {
                Log.e(TAG, "Error adding photo, base64 might be bad: ${e.message}", e)
            }
        }

        // 7. Store Extra metadata and the #CardZap signature mark in the Note field
        val noteContent = buildString {
            append("$MARKER_TAG\n")
            if (instagram.isNotBlank()) {
                append("Instagram: $instagram\n")
            }
            if (useWhatsAppBusiness) {
                append("PreferWhatsAppBusiness: true\n")
            }
            if (observations.isNotBlank()) {
                append("Observations: $observations\n")
            }
        }

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, noteContent)
            .build())

        return try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            if (results.isNotEmpty() && results[0].uri != null) {
                results[0].uri?.lastPathSegment?.toLongOrNull()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert system contact: ${e.message}", e)
            null
        }
    }

    // Fetch all contacts from Android native phonebook matching our MARKER_TAG (#CardZap)
    fun fetchSystemContacts(context: Context): List<ContactEntity> {
        val contactList = ArrayList<ContactEntity>()
        val resolver = context.contentResolver

        if (!hasContactsPermissions(context)) {
            Log.w(TAG, "Missing contacts permissions, cannot fetch system contacts")
            return emptyList()
        }

        // Search for Notes containing #CardZap
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Note.NOTE
            ),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Note.NOTE} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "%$MARKER_TAG%"),
            null
        )

        val matchingRawIds = HashSet<Long>()
        val notesMap = HashMap<Long, String>()

        cursor?.use {
            val rawIdCol = it.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            val noteCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
            while (it.moveToNext()) {
                val rawId = it.getLong(rawIdCol)
                val note = it.getString(noteCol) ?: ""
                if (note.contains(MARKER_TAG)) {
                    matchingRawIds.add(rawId)
                    notesMap[rawId] = note
                }
            }
        }

        if (matchingRawIds.isEmpty()) {
            return emptyList()
        }

        // Fetch details for each identified RawContact
        for (rawId in matchingRawIds) {
            val dataCursor = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.Photo.PHOTO
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawId.toString()),
                null
            )

            var name = ""
            var primaryPhone = ""
            var secondaryPhone = ""
            var address = ""
            var imageBase64 = ""
            var instagram = ""
            var observations = ""
            var useWhatsAppBusiness = false

            // Parse Note metadata
            val rawNote = notesMap[rawId] ?: ""
            val rawLines = rawNote.lines()
            val cleanObs = StringBuilder()
            for (line in rawLines) {
                val trimLine = line.trim()
                if (trimLine == MARKER_TAG) continue
                if (trimLine.startsWith("Instagram:")) {
                    instagram = trimLine.substringAfter("Instagram:").trim()
                } else if (trimLine.startsWith("PreferWhatsAppBusiness:")) {
                    useWhatsAppBusiness = trimLine.substringAfter("PreferWhatsAppBusiness:").trim().toBoolean()
                } else if (trimLine.startsWith("Observations:")) {
                    cleanObs.append(trimLine.substringAfter("Observations:").trim()).append("\n")
                } else if (trimLine.isNotEmpty()) {
                    cleanObs.append(trimLine).append("\n")
                }
            }
            observations = cleanObs.toString().trim()

            dataCursor?.use { dc ->
                val mimeCol = dc.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val nameCol = dc.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
                val phoneCol = dc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val phoneTypeCol = dc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val addressCol = dc.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                val photoCol = dc.getColumnIndex(ContactsContract.CommonDataKinds.Photo.PHOTO)

                while (dc.moveToNext()) {
                    val mimetype = dc.getString(mimeCol)
                    when (mimetype) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            name = dc.getString(nameCol) ?: ""
                        }
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val phoneNum = dc.getString(phoneCol) ?: ""
                            val type = dc.getInt(phoneTypeCol)
                            if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE || primaryPhone.isEmpty()) {
                                if (primaryPhone.isEmpty()) {
                                    primaryPhone = phoneNum
                                } else {
                                    secondaryPhone = phoneNum
                                }
                            } else {
                                secondaryPhone = phoneNum
                            }
                        }
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                            address = dc.getString(addressCol) ?: ""
                        }
                        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> {
                            val photoBytes = dc.getBlob(photoCol)
                            if (photoBytes != null && photoBytes.isNotEmpty()) {
                                imageBase64 = Base64.encodeToString(photoBytes, Base64.DEFAULT)
                            }
                        }
                    }
                }
            }

            contactList.add(
                ContactEntity(
                    id = rawId.toInt(), // raw id as the unique identifier for UI flow
                    name = name.ifBlank { "Sem Nome" },
                    primaryPhone = primaryPhone,
                    secondaryPhone = secondaryPhone,
                    address = address,
                    observations = observations,
                    imageBase64 = imageBase64,
                    createdAt = System.currentTimeMillis(),
                    instagram = instagram,
                    useWhatsAppBusiness = useWhatsAppBusiness
                )
            )
        }

        return contactList
    }

    // Delete a contact from Android system native contacts
    fun deleteSystemContact(context: Context, rawContactId: Int): Boolean {
        if (!hasContactsPermissions(context)) return false
        val resolver = context.contentResolver
        return try {
            val rows = resolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString())
            )
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting system contact $rawContactId: ${e.message}", e)
            false
        }
    }

    // Check if a phone number exists in native system contacts with our marker
    fun systemContactExists(context: Context, phone: String, name: String): Boolean {
        if (!hasContactsPermissions(context)) return false
        val resolver = context.contentResolver
        val cleanPhone = phone.replace("\\D".toRegex(), "")

        // We can check if a contact exists in our tagged database
        val systemContacts = fetchSystemContacts(context)
        for (sc in systemContacts) {
            val scCleanPhone = sc.primaryPhone.replace("\\D".toRegex(), "")
            if (scCleanPhone.isNotEmpty() && cleanPhone.isNotEmpty() &&
                (scCleanPhone == cleanPhone || scCleanPhone.endsWith(cleanPhone) || cleanPhone.endsWith(scCleanPhone))
            ) {
                return true
            }
            if (sc.name.equals(name, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // One-time migration of any existing SQLite/Room contacts to Android's Contacts
    fun migrateRoomContactsToSystem(context: Context, roomContacts: List<ContactEntity>) {
        if (!hasContactsPermissions(context)) {
            Log.w(TAG, "Cannot migrate contacts yet: missing permissions")
            return
        }
        Log.i(TAG, "Starting migration of ${roomContacts.size} room contacts to Android contacts")
        for (rc in roomContacts) {
            if (!systemContactExists(context, rc.primaryPhone, rc.name)) {
                Log.i(TAG, "Migrating contact to phonebook: ${rc.name}")
                insertSystemContact(
                    context = context,
                    name = rc.name,
                    primaryPhone = rc.primaryPhone,
                    secondaryPhone = rc.secondaryPhone,
                    address = rc.address,
                    instagram = rc.instagram,
                    observations = rc.observations,
                    imageBase64 = rc.imageBase64,
                    useWhatsAppBusiness = rc.useWhatsAppBusiness
                )
            } else {
                Log.i(TAG, "Contact already exists in system contacts, skipping: ${rc.name}")
            }
        }
        Log.i(TAG, "Migration finished!")
    }
}
