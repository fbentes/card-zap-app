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

    // Helper to search for an existing com.google account on the device
    fun getGoogleAccount(context: Context): Pair<String?, String?> {
        if (!hasContactsPermissions(context)) return Pair(null, null)
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME
        )
        try {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                projection,
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf("com.google"),
                null
            )?.use { cursor ->
                val typeCol = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val nameCol = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                while (cursor.moveToNext()) {
                    val type = if (typeCol >= 0) cursor.getString(typeCol) else null
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (!type.isNullOrBlank() && !name.isNullOrBlank()) {
                        return Pair(type, name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking com.google account from RawContacts: ${e.message}")
        }
        
        try {
            val accounts = android.accounts.AccountManager.get(context).getAccountsByType("com.google")
            if (accounts.isNotEmpty()) {
                return Pair("com.google", accounts[0].name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AccountManager lookup failed or missing permissions: ${e.message}")
        }

        return Pair(null, null)
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
        useWhatsAppBusiness: Boolean = false,
        instagramFollowed: Boolean = false
    ): Long? {
        val resolver = context.contentResolver
        val ops = ArrayList<ContentProviderOperation>()

        val googleAcc = getGoogleAccount(context)

        // 1. Create a Raw Contact
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, googleAcc.first)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, googleAcc.second)
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
            if (instagramFollowed) {
                append("InstagramFollowed: true\n")
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

    // Fetch all contacts from Android native phonebook
    fun fetchSystemContacts(context: Context): List<ContactEntity> {
        val contactList = ArrayList<ContactEntity>()
        val resolver = context.contentResolver

        if (!hasContactsPermissions(context)) {
            Log.w(TAG, "Missing contacts permissions, cannot fetch system contacts")
            return emptyList()
        }

        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA15
        )

        val cursor = try {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Data.RAW_CONTACT_ID} ASC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query ContactsContract.Data: ${e.message}", e)
            null
        }

        class TempContact(val rawId: Long) {
            var name = ""
            var primaryPhone = ""
            var secondaryPhone = ""
            var address = ""
            var imageBase64 = ""
            var instagram = ""
            var observations = ""
            var useWhatsAppBusiness = false
            var rawNote = ""
        }

        val tempContacts = LinkedHashMap<Long, TempContact>()

        cursor?.use { c ->
            val rawIdCol = c.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeCol = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val data1Col = c.getColumnIndex(ContactsContract.Data.DATA1)
            val data2Col = c.getColumnIndex(ContactsContract.Data.DATA2)
            val blobCol = c.getColumnIndex(ContactsContract.Data.DATA15)

            while (c.moveToNext()) {
                val rawId = c.getLong(rawIdCol)
                val mimetype = c.getString(mimeCol) ?: continue

                val tc = tempContacts.getOrPut(rawId) { TempContact(rawId) }

                when (mimetype) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        val n = c.getString(data1Col)
                        if (!n.isNullOrBlank()) {
                            tc.name = n
                        }
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val phoneNum = c.getString(data1Col)
                        if (!phoneNum.isNullOrBlank()) {
                            val type = c.getInt(data2Col)
                            if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE || tc.primaryPhone.isEmpty()) {
                                if (tc.primaryPhone.isEmpty()) {
                                    tc.primaryPhone = phoneNum
                                } else {
                                    tc.secondaryPhone = phoneNum
                                }
                            } else {
                                tc.secondaryPhone = phoneNum
                            }
                        }
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val addr = c.getString(data1Col)
                        if (!addr.isNullOrBlank()) {
                            tc.address = addr
                        }
                    }
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> {
                        val photoBytes = c.getBlob(blobCol)
                        if (photoBytes != null && photoBytes.isNotEmpty()) {
                            tc.imageBase64 = Base64.encodeToString(photoBytes, Base64.DEFAULT)
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val note = c.getString(data1Col)
                        if (!note.isNullOrBlank()) {
                            tc.rawNote = note
                        }
                    }
                }
            }
        }

        for ((rawId, tc) in tempContacts) {
            // ONLY include contacts that contain our CardZap marker (MARKER_TAG) in the notes
            if (!tc.rawNote.contains(MARKER_TAG)) {
                continue
            }

            if (tc.name.isBlank() && tc.primaryPhone.isBlank() && tc.secondaryPhone.isBlank()) {
                continue
            }

            var finalInstagram = ""
            var finalUseWhatsAppBusiness = false
            var finalObservations = ""
            var finalInstagramFollowed = false

            if (tc.rawNote.contains(MARKER_TAG)) {
                val rawLines = tc.rawNote.lines()
                val cleanObs = StringBuilder()

                for (line in rawLines) {
                    val trimLine = line.trim()
                    if (trimLine == MARKER_TAG) continue
                    if (trimLine.startsWith("Instagram:")) {
                        finalInstagram = trimLine.substringAfter("Instagram:").trim()
                    } else if (trimLine.startsWith("PreferWhatsAppBusiness:")) {
                        finalUseWhatsAppBusiness = trimLine.substringAfter("PreferWhatsAppBusiness:").trim().toBoolean()
                    } else if (trimLine.startsWith("InstagramFollowed:")) {
                        finalInstagramFollowed = trimLine.substringAfter("InstagramFollowed:").trim().toBoolean()
                    } else if (trimLine.startsWith("Observations:")) {
                        cleanObs.append(trimLine.substringAfter("Observations:").trim()).append("\n")
                    } else if (trimLine.isNotEmpty()) {
                        cleanObs.append(trimLine).append("\n")
                    }
                }
                finalObservations = cleanObs.toString().trim()
            } else {
                finalObservations = tc.rawNote.trim()
            }

            contactList.add(
                ContactEntity(
                    id = rawId.toInt(),
                    name = tc.name.ifBlank { "Sem Nome" },
                    primaryPhone = tc.primaryPhone,
                    secondaryPhone = tc.secondaryPhone,
                    address = tc.address,
                    observations = finalObservations,
                    imageBase64 = tc.imageBase64,
                    createdAt = System.currentTimeMillis(),
                    instagram = finalInstagram,
                    useWhatsAppBusiness = finalUseWhatsAppBusiness,
                    instagramFollowed = finalInstagramFollowed
                )
            )
        }

        return contactList
    }

    // Update a contact directly in the Android system's native phonebook under the same RAW_CONTACT_ID
    fun updateSystemContact(
        context: Context,
        rawContactId: Int,
        name: String,
        primaryPhone: String,
        secondaryPhone: String,
        address: String,
        instagram: String,
        observations: String,
        imageBase64: String,
        useWhatsAppBusiness: Boolean = false,
        instagramFollowed: Boolean = false
    ): Boolean {
        if (!hasContactsPermissions(context)) return false
        val resolver = context.contentResolver
        val ops = ArrayList<ContentProviderOperation>()

        // 1. Delete all existing Data rows for this RAW_CONTACT_ID
        ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
            .build())

        // 2. Insert Name
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        // 3. Insert Primary Phone
        if (primaryPhone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, primaryPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }

        // 4. Insert Secondary Phone
        if (secondaryPhone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, secondaryPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                .build())
        }

        // 5. Insert Postal Address
        if (address.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
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
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, decodedBytes)
                    .build())
            } catch (e: Exception) {
                Log.e(TAG, "Error adding photo inside update, base64 might be bad: ${e.message}", e)
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
            if (instagramFollowed) {
                append("InstagramFollowed: true\n")
            }
            if (observations.isNotBlank()) {
                append("Observations: $observations\n")
            }
        }

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, noteContent)
            .build())

        return try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update system contact $rawContactId: ${e.message}", e)
            false
        }
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
