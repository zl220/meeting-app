package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = Meeting::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,
    val speakerName: String? = null,
    val text: String,
    val isAi: Boolean = false
)
