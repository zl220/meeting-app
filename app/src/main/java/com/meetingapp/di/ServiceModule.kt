package com.meetingapp.di

import com.meetingapp.api.TranscribeApi
import com.meetingapp.data.db.dao.AudioChunkDao
import com.meetingapp.data.db.dao.SegmentDao
import com.meetingapp.repository.SettingsRepository
import com.meetingapp.repository.TranscriptionRepository
import com.meetingapp.service.ChunkFile
import com.meetingapp.service.RecordingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideChunkCallback(
        transcriptionRepo: TranscriptionRepository,
        settingsRepo: SettingsRepository,
        meetingRepo: com.meetingapp.repository.MeetingRepository
    ): RecordingService.ChunkCallback = object : RecordingService.ChunkCallback {
        override suspend fun onChunkReady(meetingId: Long, chunk: ChunkFile) {
            val meeting = meetingRepo.getById(meetingId) ?: return
            val participants = meetingRepo.getParticipants(meetingId)
            val keywords = participants.map { it.name }
            val prompt = meeting.agenda ?: meeting.title
            val lang = runBlocking { settingsRepo.preferredLanguage.first() }
            transcriptionRepo.processChunk(
                meetingId = meetingId,
                chunk = chunk,
                keywords = keywords,
                prompt = prompt,
                languages = listOf(lang)
            )
        }
    }
}
