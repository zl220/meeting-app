package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Minutes
import kotlinx.coroutines.flow.Flow

@Dao
interface MinutesDao {
    @Query("SELECT * FROM minutes WHERE meetingId = :meetingId ORDER BY generatedAt DESC LIMIT 1")
    fun getLatest(meetingId: Long): Flow<Minutes?>

    @Query("SELECT * FROM minutes WHERE id = :id")
    suspend fun getById(id: Long): Minutes?

    @Insert
    suspend fun insert(minutes: Minutes): Long

    @Update
    suspend fun update(minutes: Minutes)

    @Query("UPDATE minutes SET savedToDrive = 1 WHERE id = :id")
    suspend fun markSavedToDrive(id: Long)

    @Query("UPDATE minutes SET emailSent = 1 WHERE id = :id")
    suspend fun markEmailSent(id: Long)

    @Query("UPDATE minutes SET content = :content, isEdited = 1 WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)
}
