package com.meetingapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A voice clip captured in-meeting for an anonymous diarized speaker that has NOT been named
 * yet. Unlike [VoiceSample] these carry no participant — they're grouped by (meetingId,
 * speakerLabel), the per-window anonymous label like "发言人A".
 *
 * Persisted (not just left on disk) so the user can name them up to
 * [com.meetingapp.util.Constants.PENDING_VOICE_RETENTION_DAYS] days later; unnamed samples past
 * that window are swept. Naming a speaker promotes ALL their pending clips into [VoiceSample]s.
 */
@Entity(
    tableName = "pending_voice_samples",
    foreignKeys = [
        ForeignKey(
            entity = Meeting::class,
            parentColumns = ["id"],
            childColumns = ["meetingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["meetingId", "speakerLabel"])]
)
data class PendingVoiceSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    val speakerLabel: String,
    val filePath: String,
    val durationMs: Long,
    // 0..1 reference quality (duration + energy); higher is a better diarization reference.
    val qualityScore: Double,
    val capturedAt: Long = System.currentTimeMillis()
)
