package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val primaryPhone: String,
    val secondaryPhone: String,
    val address: String,
    val observations: String,
    val imageBase64: String, // Storing base64 JPEG of the business card image
    val createdAt: Long = System.currentTimeMillis(),
    val instagram: String = ""
)
