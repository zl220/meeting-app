package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_chunks",
    foreignKeys = [
        ForeignKey(
            entity = Meeting::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudioChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val filePath: String,
    val startMs: Long,
    val endMs: Long,
    val transcribed: Boolean = false
)
