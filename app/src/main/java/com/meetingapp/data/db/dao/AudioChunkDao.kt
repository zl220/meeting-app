package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.AudioChunk

@Dao
interface AudioChunkDao {
    @Query("SELECT * FROM audio_chunks WHERE meetingId = :meetingId ORDER BY startMs ASC")
    suspend fun getByMeeting(meetingId: Long): List<AudioChunk>

    @Query("SELECT * FROM audio_chunks WHERE meetingId = :meetingId AND transcribed = 0 ORDER BY startMs ASC")
    suspend fun getPendingByMeeting(meetingId: Long): List<AudioChunk>

    @Insert
    suspend fun insert(chunk: AudioChunk): Long

    @Query("UPDATE audio_chunks SET transcribed = 1 WHERE id = :id")
    suspend fun markTranscribed(id: Long)

    @Query("DELETE FROM audio_chunks WHERE meetingId IN (SELECT id FROM meetings WHERE startedAt < :cutoffMs)")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
