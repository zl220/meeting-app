package com.meetingapp.repository

import com.meetingapp.data.db.dao.MeetingDao
import com.meetingapp.data.db.dao.ParticipantDao
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingParticipant
import com.meetingapp.data.db.entity.Participant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeetingRepository @Inject constructor(
    private val dao: MeetingDao,
    private val participantDao: ParticipantDao
) {
    fun getAll(): Flow<List<Meeting>> = dao.getAll()

    suspend fun getById(id: Long): Meeting? = dao.getById(id)

    suspend fun create(meeting: Meeting): Long = dao.insert(meeting)

    suspend fun update(meeting: Meeting) = dao.update(meeting)

    suspend fun delete(meeting: Meeting) = dao.delete(meeting)

    suspend fun setStarted(id: Long) = dao.setStarted(id, startedAt = System.currentTimeMillis())

    suspend fun setFinished(id: Long) = dao.setFinished(id, endedAt = System.currentTimeMillis())

    suspend fun setAudioFilePath(id: Long, path: String?) = dao.setAudioFilePath(id, path)

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

    /**
     * Find an existing participant by exact name, or create one. Used when a user names a
     * previously-anonymous diarized speaker so their voice can be added to the library.
     * Also links them to the meeting and records the speaker label.
     */
    suspend fun resolveOrCreateParticipant(meetingId: Long, name: String, label: String): Participant {
        // Reuse a meeting attendee with this name if present; otherwise create a new participant.
        val fromMeeting = getParticipants(meetingId).firstOrNull { it.name == name }
        val participant = fromMeeting ?: run {
            val id = participantDao.upsert(Participant(name = name, email = ""))
            Participant(id = id, name = name, email = "")
        }
        dao.insertParticipantLink(MeetingParticipant(meetingId, participant.id))
        dao.updateSpeakerLabel(meetingId, participant.id, label)
        participantDao.touchLastUsed(participant.id)
        return participant
    }
}
