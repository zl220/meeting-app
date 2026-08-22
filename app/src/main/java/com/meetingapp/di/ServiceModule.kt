package com.meetingapp.di

import android.util.Log
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.SettingsRepository
import com.meetingapp.repository.TranscriptionRepository
import com.meetingapp.service.ChunkFile
import com.meetingapp.service.RecordingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideChunkCallback(
        transcriptionRepo: TranscriptionRepository,
        settingsRepo: SettingsRepository,
        meetingRepo: MeetingRepository
    ): RecordingService.ChunkCallback = object : RecordingService.ChunkCallback {
        override suspend fun onChunkReady(meetingId: Long, chunk: ChunkFile) {
            try {
                val apiKey = settingsRepo.apiKey.first()
                if (apiKey.isBlank()) {
                    Log.w("ChunkCallback", "OpenAI API key not set — skipping transcription")
                    return
                }
                val meeting = meetingRepo.getById(meetingId) ?: return
                val participants = meetingRepo.getParticipants(meetingId)
                val keywords = participants.map { it.name }
                val prompt = meeting.agenda ?: meeting.title
                val lang = settingsRepo.preferredLanguage.first()
                transcriptionRepo.processChunk(
                    meetingId = meetingId,
                    chunk = chunk,
                    keywords = keywords,
                    prompt = prompt,
                    languages = listOf(lang)
                )
            } catch (e: Exception) {
                Log.e("ChunkCallback", "Transcription failed for chunk ${chunk.file.name}", e)
            }
        }
    }
}
