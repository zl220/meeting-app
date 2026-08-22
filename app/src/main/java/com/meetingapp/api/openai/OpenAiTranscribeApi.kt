package com.meetingapp.api.openai

import com.meetingapp.api.TranscribeApi
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Named

class OpenAiTranscribeApi @Inject constructor(
    private val service: OpenAiService,
    @Named("openai_api_key_flow") private val apiKeyFlow: Flow<String>
) : TranscribeApi {

    override suspend fun transcribe(
        audioFile: File,
        meetingId: Long,
        chunkStartMs: Long,
        keywords: List<String>,
        prompt: String,
        languages: List<String>
    ): List<Segment> {
        val filePart = MultipartBody.Part.createFormData(
            "file", audioFile.name,
            audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        )
        val modelBody = Constants.MODEL_TRANSCRIBE.toRequestBody("text/plain".toMediaTypeOrNull())
        val formatBody = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())
        val langBody = languages.firstOrNull().orEmpty().toRequestBody("text/plain".toMediaTypeOrNull())
        val promptBody = prompt.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
        val keywordsBody = keywords.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.toRequestBody("text/plain".toMediaTypeOrNull())

        val apiKey = apiKeyFlow.first()
        val response = service.transcribe(
            auth = "Bearer $apiKey",
            file = filePart,
            model = modelBody,
            responseFormat = formatBody,
            language = langBody,
            prompt = promptBody,
            keywords = keywordsBody
        )

        return mapResponseToSegments(response, meetingId, chunkStartMs)
    }

    private fun mapResponseToSegments(
        response: TranscriptionResponse,
        meetingId: Long,
        chunkStartMs: Long
    ): List<Segment> {
        val apiSegments = response.segments
        if (!apiSegments.isNullOrEmpty()) {
            return apiSegments.map { seg ->
                Segment(
                    meetingId = meetingId,
                    startMs = chunkStartMs + (seg.start * 1000).toLong(),
                    endMs = chunkStartMs + (seg.end * 1000).toLong(),
                    speakerLabel = seg.speaker ?: "Speaker 1",
                    text = seg.text.trim()
                )
            }
        }
        // Fallback: single segment if API returns only text
        return listOf(
            Segment(
                meetingId = meetingId,
                startMs = chunkStartMs,
                endMs = chunkStartMs + Constants.CHUNK_DURATION_MS,
                speakerLabel = "Speaker 1",
                text = response.text.trim()
            )
        )
    }
}
