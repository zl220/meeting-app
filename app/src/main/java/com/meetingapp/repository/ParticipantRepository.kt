package com.meetingapp.repository

import com.meetingapp.data.db.dao.ParticipantDao
import com.meetingapp.data.db.entity.Participant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParticipantRepository @Inject constructor(
    private val dao: ParticipantDao
) {
    fun getAll(): Flow<List<Participant>> = dao.getAll()

    suspend fun save(participant: Participant): Long = dao.upsert(participant)

    suspend fun delete(participant: Participant) = dao.delete(participant)

    suspend fun touchLastUsed(id: Long) = dao.touchLastUsed(id)
}
