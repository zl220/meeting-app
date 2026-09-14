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
    /**
     * The single best-quality sample per participant, paired with the participant. Used to build
     * the ≤4 known-speaker references — a participant with several clips contributes only their
     * strongest one, so a weak clip never displaces a good reference.
     */
    @Transaction
    @Query(
        """
        SELECT vs.* FROM voice_samples vs
        WHERE vs.id = (
            SELECT id FROM voice_samples
            WHERE participantId = vs.participantId
            ORDER BY qualityScore DESC, capturedAt DESC
            LIMIT 1
        )
        """
    )
    suspend fun getBestPerParticipant(): List<NamedVoiceSample>

    @Query("SELECT * FROM voice_samples WHERE participantId = :participantId ORDER BY qualityScore DESC")
    suspend fun getForParticipant(participantId: Long): List<VoiceSample>

    /** Every stored sample paired with its participant (for the settings voice library view). */
    @Transaction
    @Query("SELECT * FROM voice_samples ORDER BY participantId, qualityScore DESC")
    suspend fun getAllWithParticipant(): List<NamedVoiceSample>

    @Query("SELECT * FROM voice_samples WHERE id = :id")
    suspend fun getById(id: Long): VoiceSample?

    @Insert
    suspend fun insert(sample: VoiceSample): Long

    @Query("DELETE FROM voice_samples WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM voice_samples WHERE participantId = :participantId")
    suspend fun deleteForParticipant(participantId: Long)
}
