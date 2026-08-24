package com.meetingapp.data.db.dao

import androidx.room.*
import com.meetingapp.data.db.entity.Minutes
import kotlinx.coroutines.flow.Flow

@Dao
interface MinutesDao {
    @Query("SELECT * FROM minutes WHERE meetingId = :meetingId ORDER BY generatedAt DESC LIMIT 1")
    fun getLatest(meetingId: Long): Flow<Minutes?>

    /** The live rolling draft for this meeting, if one exists (R10). */
    @Query("SELECT * FROM minutes WHERE meetingId = :meetingId AND isDraft = 1 ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getDraft(meetingId: Long): Minutes?

    /** Clear the draft flag on all rows for a meeting (called when finalizing). */
    @Query("UPDATE minutes SET isDraft = 0 WHERE meetingId = :meetingId")
    suspend fun clearDraftFlag(meetingId: Long)

    @Query("SELECT * FROM minutes WHERE id = :id")
    suspend fun getById(id: Long): Minutes?

    /** Latest finalized (non-draft) minutes for a meeting, if already generated. */
    @Query("SELECT * FROM minutes WHERE meetingId = :meetingId AND isDraft = 0 ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getFinalized(meetingId: Long): Minutes?

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
