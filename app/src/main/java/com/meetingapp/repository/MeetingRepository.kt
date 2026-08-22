package com.meetingapp.repository

import com.meetingapp.data.db.dao.MeetingDao
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingParticipant
import com.meetingapp.data.db.entity.Participant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeetingRepository @Inject constructor(
    private val dao: MeetingDao
) {
    fun getAll(): Flow<List<Meeting>> = dao.getAll()

    suspend fun getById(id: Long): Meeting? = dao.getById(id)

    suspend fun create(meeting: Meeting): Long = dao.insert(meeting)

    suspend fun update(meeting: Meeting) = dao.update(meeting)

    suspend fun setStarted(id: Long) = dao.setStarted(id, startedAt = System.currentTimeMillis())

    suspend fun setFinished(id: Long) = dao.setFinished(id, endedAt = System.currentTimeMillis())

    suspend fun setParticipants(meetingId: Long, participantIds: List<Long>) {
        dao.clearParticipantLinks(meetingId)
        participantIds.forEach { pid ->
            dao.insertParticipantLink(MeetingParticipant(meetingId, pid))
        }
    }

    suspend fun getParticipants(meetingId: Long): List<Participant> =
        dao.getParticipants(meetingId)

    suspend fun assignSpeakerLabel(meetingId: Long, participantId: Long, label: String) =
        dao.updateSpeakerLabel(meetingId, participantId, label)
}
