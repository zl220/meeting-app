package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.VoiceSample

/** Row pairing a stored voice sample with its participant (for building known-speaker refs). */
data class NamedVoiceSample(
    @Embedded val sample: VoiceSample,
    @Relation(parentColumn = "participantId", entityColumn = "id")
    val participant: Participant
)

@Dao
interface VoiceSampleDao {
    /** Newest-first so callers can take the top-N most-recently-refreshed voices. */
    @Transaction
    @Query("SELECT * FROM voice_samples ORDER BY capturedAt DESC")
    suspend fun getAllNamed(): List<NamedVoiceSample>

    @Query("SELECT * FROM voice_samples WHERE participantId = :participantId")
    suspend fun getForParticipant(participantId: Long): VoiceSample?

    /** One sample per participant (unique index); replace refreshes the voice. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sample: VoiceSample): Long

    @Query("DELETE FROM voice_samples WHERE participantId = :participantId")
    suspend fun deleteForParticipant(participantId: Long)
}
