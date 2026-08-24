package com.meetingapp.repository

import com.meetingapp.api.SaveMinutesApi
import com.meetingapp.api.openai.OpenAiMinutesGenerator
import com.meetingapp.data.db.dao.MinutesDao
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Minutes
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.Segment
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MinutesRepository @Inject constructor(
    private val dao: MinutesDao,
    private val generator: OpenAiMinutesGenerator,
    private val saveApi: SaveMinutesApi
) {
    fun getLatest(meetingId: Long): Flow<Minutes?> = dao.getLatest(meetingId)

    suspend fun generate(meeting: Meeting, segments: List<Segment>): Minutes {
        val content = generator.generate(meeting.title, meeting.agenda, segments)
        val minutes = Minutes(meetingId = meeting.id, content = content)
        val id = dao.insert(minutes)
        return minutes.copy(id = id)
    }

    /** Current live draft for a meeting, or null if none yet (R10). */
    suspend fun getDraft(meetingId: Long): Minutes? = dao.getDraft(meetingId)

    /** Existing finalized minutes for a meeting, or null if never finalized (R10). */
    suspend fun getFinalized(meetingId: Long): Minutes? = dao.getFinalized(meetingId)

    /**
     * Incrementally revise the rolling draft with new transcript (R10 / T1 & T2).
     * Reuses a single draft row per meeting instead of inserting new rows.
     * Returns the updated draft.
     */
    suspend fun refreshDraft(meeting: Meeting, newSegments: List<Segment>): Minutes {
        val existing = dao.getDraft(meeting.id)
        val revised = generator.revise(
            title = meeting.title,
            agenda = meeting.agenda,
            currentDraft = existing?.content.orEmpty(),
            newSegments = newSegments
        )
        return if (existing == null) {
            val draft = Minutes(meetingId = meeting.id, content = revised, isDraft = true)
            val id = dao.insert(draft)
            draft.copy(id = id)
        } else {
            dao.updateContent(existing.id, revised)
            existing.copy(content = revised)
        }
    }

    /**
     * Produce the final minutes when the meeting ends (R10 / T3). Regenerates from the
     * full transcript for best quality, then clears the draft flag so the finalized
     * row wins in [getLatest].
     */
    suspend fun finalize(meeting: Meeting, allSegments: List<Segment>): Minutes {
        val content = generator.generate(meeting.title, meeting.agenda, allSegments)
        dao.clearDraftFlag(meeting.id)
        val minutes = Minutes(meetingId = meeting.id, content = content, isDraft = false)
        val id = dao.insert(minutes)
        return minutes.copy(id = id)
    }

    suspend fun updateContent(id: Long, content: String) = dao.updateContent(id, content)

    suspend fun saveToDrive(meeting: Meeting, minutes: Minutes): String {
        val driveUrl = saveApi.saveToDrive(meeting, minutes)
        dao.markSavedToDrive(minutes.id)
        return driveUrl
    }

    suspend fun sendEmail(meeting: Meeting, minutes: Minutes, participants: List<Participant>) {
        saveApi.sendEmail(meeting, minutes, participants)
        dao.markEmailSent(minutes.id)
    }
}
