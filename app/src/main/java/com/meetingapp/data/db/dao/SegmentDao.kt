package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Segment
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE meetingId = :meetingId ORDER BY startMs ASC")
    fun getByMeeting(meetingId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE meetingId = :meetingId ORDER BY startMs ASC")
    suspend fun getByMeetingOnce(meetingId: Long): List<Segment>

    @Insert
    suspend fun insert(segment: Segment): Long

    @Query("UPDATE segments SET speakerName = :name WHERE speakerLabel = :label AND meetingId = :meetingId")
    suspend fun assignName(meetingId: Long, label: String, name: String)

    @Delete
    suspend fun delete(segment: Segment)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteById(id: Long)
}
