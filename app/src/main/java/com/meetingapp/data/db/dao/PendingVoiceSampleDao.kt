package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.PendingVoiceSample

@Dao
interface PendingVoiceSampleDao {
    @Insert
    suspend fun insert(sample: PendingVoiceSample): Long

    /** All captured clips for one anonymous speaker in a meeting (best first). */
    @Query(
        """
        SELECT * FROM pending_voice_samples
        WHERE meetingId = :meetingId AND speakerLabel = :label
        ORDER BY qualityScore DESC
        """
    )
    suspend fun getForSpeaker(meetingId: Long, label: String): List<PendingVoiceSample>

    /** Distinct anonymous labels still awaiting a name in this meeting. */
    @Query(
        "SELECT DISTINCT speakerLabel FROM pending_voice_samples WHERE meetingId = :meetingId ORDER BY speakerLabel"
    )
    suspend fun getPendingLabels(meetingId: Long): List<String>

    /** All captured-but-unnamed clips, newest first (for the settings voice library view). */
    @Query("SELECT * FROM pending_voice_samples ORDER BY meetingId, speakerLabel, qualityScore DESC")
    suspend fun getAll(): List<PendingVoiceSample>

    @Query("SELECT * FROM pending_voice_samples WHERE id = :id")
    suspend fun getById(id: Long): PendingVoiceSample?

    @Query("DELETE FROM pending_voice_samples WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM pending_voice_samples WHERE capturedAt < :cutoffMs")
    suspend fun getOlderThan(cutoffMs: Long): List<PendingVoiceSample>

    @Query("DELETE FROM pending_voice_samples WHERE meetingId = :meetingId AND speakerLabel = :label")
    suspend fun deleteForSpeaker(meetingId: Long, label: String)

    @Query("DELETE FROM pending_voice_samples WHERE capturedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
