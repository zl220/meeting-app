package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY id DESC")
    fun getAll(): Flow<List<Meeting>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getById(id: Long): Meeting?

    @Insert
    suspend fun insert(meeting: Meeting): Long

    @Update
    suspend fun update(meeting: Meeting)

    @Query("UPDATE meetings SET status = :status, startedAt = :startedAt WHERE id = :id")
    suspend fun setStarted(id: Long, status: MeetingStatus = MeetingStatus.RECORDING, startedAt: Long)

    @Query("UPDATE meetings SET status = :status, endedAt = :endedAt WHERE id = :id")
    suspend fun setFinished(id: Long, status: MeetingStatus = MeetingStatus.FINISHED, endedAt: Long)

    @Delete
    suspend fun delete(meeting: Meeting)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParticipantLink(link: com.meetingapp.data.db.entity.MeetingParticipant)

    @Query("DELETE FROM meeting_participants WHERE meetingId = :meetingId")
    suspend fun clearParticipantLinks(meetingId: Long)

    @Query("""
        SELECT p.* FROM participants p
        INNER JOIN meeting_participants mp ON mp.participantId = p.id
        WHERE mp.meetingId = :meetingId
    """)
    suspend fun getParticipants(meetingId: Long): List<com.meetingapp.data.db.entity.Participant>

    @Query("""
        UPDATE meeting_participants SET speakerLabel = :label
        WHERE meetingId = :meetingId AND participantId = :participantId
    """)
    suspend fun updateSpeakerLabel(meetingId: Long, participantId: Long, label: String)
}
