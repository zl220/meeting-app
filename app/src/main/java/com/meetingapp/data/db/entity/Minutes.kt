package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "minutes",
    foreignKeys = [
        ForeignKey(
            entity = Meeting::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Minutes(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val generatedAt: Long = System.currentTimeMillis(),
    val content: String,
    val isEdited: Boolean = false,
    val savedToDrive: Boolean = false,
    val emailSent: Boolean = false
)
