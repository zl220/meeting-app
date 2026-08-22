package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "meeting_participants",
    primaryKeys = ["meetingId", "participantId"],
    foreignKeys = [
        ForeignKey(
            entity = Meeting::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Participant::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MeetingParticipant(
    val meetingId: Long,
    val participantId: Long,
    val speakerLabel: String? = null
)
