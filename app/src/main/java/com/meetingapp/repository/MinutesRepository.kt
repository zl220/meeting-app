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
