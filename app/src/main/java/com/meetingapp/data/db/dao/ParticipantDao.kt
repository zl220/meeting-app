package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Participant
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantDao {
    @Query("SELECT * FROM participants ORDER BY lastUsedAt DESC")
    fun getAll(): Flow<List<Participant>>

    @Query("SELECT * FROM participants WHERE id = :id")
    suspend fun getById(id: Long): Participant?

    @Upsert
    suspend fun upsert(participant: Participant): Long

    @Delete
    suspend fun delete(participant: Participant)

    @Query("UPDATE participants SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touchLastUsed(id: Long, ts: Long = System.currentTimeMillis())
}
