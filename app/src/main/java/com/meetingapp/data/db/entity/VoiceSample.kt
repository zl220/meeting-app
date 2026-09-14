package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A short (2–10s) named voice reference clip for a participant, used as a
 * `known_speaker_references` input to the diarization model so that person's
 * speech comes back tagged with their real name instead of an anonymous code.
 *
 * The voice library grows naturally: when the user names a previously-anonymous
 * speaker, ALL that speaker's captured clips are promoted into VoiceSamples. A
 * participant can have MULTIPLE samples (non-unique participantId); the highest
 * [qualityScore] one is chosen as the reference, so a poor clip never displaces a good one.
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
    indices = [Index(value = ["participantId"])]
)
data class VoiceSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val filePath: String,
    val durationMs: Long,
    // 0..1 reference quality (duration + energy); the best-scored sample per participant is used.
    val qualityScore: Double = 0.0,
    val capturedAt: Long = System.currentTimeMillis()
)
