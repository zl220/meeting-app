package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MeetingStatus { IDLE, RECORDING, FINISHED }

@Entity(tableName = "meetings")
data class Meeting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val agenda: String? = null,
    val location: String? = null,
    val estimatedDurationMinutes: Int,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val status: MeetingStatus = MeetingStatus.IDLE,
    /** Absolute path to the full continuous meeting recording (M4A/AAC), set when the meeting ends. */
    val audioFilePath: String? = null
)
