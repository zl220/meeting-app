package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A short (2–10s) voice reference clip for a known participant, used as a
 * `known_speaker_references` input to the diarization model so that person's
 * speech comes back tagged with their real name instead of an anonymous code.
 *
 * The voice library grows naturally: after a meeting, when the user assigns a
 * name to a previously-anonymous speaker, that speaker's representative clip is
 * promoted into a [VoiceSample] for the participant. One sample per participant
 * (the newest wins) keeps the library simple and lets voices refresh over time.
 */
@Entity(
    tableName = "voice_samples",
    foreignKeys = [
        ForeignKey(
            entity = Participant::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["participantId"], unique = true)]
)
data class VoiceSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val filePath: String,
    val durationMs: Long,
    val capturedAt: Long = System.currentTimeMillis()
)
