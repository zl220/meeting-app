package com.meetingapp.api.openai

import android.util.Log
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

        // Only pass language if non-blank; empty string causes API 400
        val langBody = languages.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.toRequestBody("text/plain".toMediaTypeOrNull())

        // Merge keywords into the prompt so Whisper knows the vocabulary
        val fullPrompt = buildString {
            if (prompt.isNotBlank()) append(prompt)
            if (keywords.isNotEmpty()) {
                if (isNotEmpty()) append("。")
                append(keywords.joinToString("、"))
            }
        }.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())

        val apiKey = apiKeyFlow.first()
        Log.d("TranscribeApi", "Transcribing ${audioFile.name} (${audioFile.length()} bytes), model=${Constants.MODEL_TRANSCRIBE}")

        val response = service.transcribe(
            auth = "Bearer $apiKey",
            file = filePart,
            model = modelBody,
            responseFormat = formatBody,
            language = langBody,
            prompt = fullPrompt
        )

        Log.d("TranscribeApi", "Got response: text=${response.text.take(80)}, segments=${response.segments?.size}")
        return mapResponseToSegments(response, meetingId, chunkStartMs)
    }

    private fun mapResponseToSegments(
        response: TranscriptionResponse,
        meetingId: Long,
        chunkStartMs: Long
    ): List<Segment> {
        val apiSegments = response.segments
        if (!apiSegments.isNullOrEmpty()) {
            // whisper-1 verbose_json: merge all sub-segments into one per chunk
            // (no speaker diarization from whisper-1; use a single label per chunk)
            val fullText = apiSegments.joinToString(" ") { it.text.trim() }.trim()
            if (fullText.isBlank()) return emptyList()
            return listOf(
                Segment(
                    meetingId = meetingId,
                    startMs = chunkStartMs + (apiSegments.first().start * 1000).toLong(),
                    endMs = chunkStartMs + (apiSegments.last().end * 1000).toLong(),
                    speakerLabel = Constants.SPEAKER_LABEL_DEFAULT,
                    text = fullText
                )
            )
        }
        // Fallback: API returned only top-level text
        val text = response.text.trim()
        if (text.isBlank()) return emptyList()
        return listOf(
            Segment(
                meetingId = meetingId,
                startMs = chunkStartMs,
                endMs = chunkStartMs + Constants.CHUNK_DURATION_MS,
                speakerLabel = "Speaker 1",
                text = text
            )
        )
    }
}
