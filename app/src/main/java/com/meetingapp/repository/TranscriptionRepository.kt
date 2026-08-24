package com.meetingapp.repository

import android.util.Log
import com.meetingapp.api.TranscribeApi
import com.meetingapp.data.db.dao.AudioChunkDao
import com.meetingapp.data.db.dao.SegmentDao
import com.meetingapp.data.db.entity.AudioChunk
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.service.ChunkFile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionRepository @Inject constructor(
    private val segmentDao: SegmentDao,
    private val chunkDao: AudioChunkDao,
    private val transcribeApi: TranscribeApi
) {
    fun getSegments(meetingId: Long): Flow<List<Segment>> =
        segmentDao.getByMeeting(meetingId)

    suspend fun processChunk(
        meetingId: Long,
        chunk: ChunkFile,
        keywords: List<String>,
        prompt: String,
        languages: List<String>
    ) {
        val chunkId = chunkDao.insert(
            AudioChunk(
                meetingId = meetingId,
                filePath = chunk.file.absolutePath,
                startMs = chunk.startMs,
                endMs = chunk.endMs
            )
        )
        try {
            val segments = transcribeApi.transcribe(
                audioFile = chunk.file,
                meetingId = meetingId,
                chunkStartMs = chunk.startMs,
                keywords = keywords,
                prompt = prompt,
                languages = languages
            )
            val filtered = segments.filter { !isWhisperHallucination(it.text) }
            Log.d("TranscriptionRepo", "Transcribed ${filtered.size}/${segments.size} segment(s) from ${chunk.file.name}")
            filtered.forEach { segmentDao.insert(it) }
            chunkDao.markTranscribed(chunkId)
        } catch (e: Exception) {
            Log.e("TranscriptionRepo", "transcribe failed for ${chunk.file.name}", e)
        }
    }

    private fun isWhisperHallucination(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return true
        // Known Whisper hallucination patterns on silence/noise
        val hallucinations = listOf(
            "请不吝点赞", "订阅", "转发", "打赏",
            "thank you for watching", "thanks for watching",
            "please subscribe", "字幕由", "翻译",
            "MBC", "KBS", "字幕组"
        )
        return hallucinations.any { t.contains(it, ignoreCase = true) }
    }

    suspend fun insertAiSegment(segment: Segment) = segmentDao.insert(segment)

    suspend fun deleteSegment(id: Long) = segmentDao.deleteById(id)

    suspend fun assignSpeakerName(meetingId: Long, label: String, name: String) =
        segmentDao.assignName(meetingId, label, name)

    suspend fun getAllSegmentsOnce(meetingId: Long): List<Segment> =
        segmentDao.getByMeetingOnce(meetingId)
}
