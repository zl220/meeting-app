package com.meetingapp.api.openai

import android.util.Log
import com.meetingapp.api.DiarizeApi
import com.meetingapp.api.KnownSpeaker
import com.meetingapp.api.SpeakerTurn
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

class OpenAiDiarizeApi @Inject constructor(
    private val service: OpenAiService,
    @Named("openai_api_key_flow") private val apiKeyFlow: Flow<String>
) : DiarizeApi {

    override suspend fun diarize(
        audioFile: File,
        windowStartMs: Long,
        knownSpeakers: List<KnownSpeaker>
    ): List<SpeakerTurn> {
        val filePart = MultipartBody.Part.createFormData(
            "file", audioFile.name,
            audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        )
        val modelBody = Constants.MODEL_TRANSCRIBE_DIARIZE.toRequestBody("text/plain".toMediaTypeOrNull())
        val formatBody = "diarized_json".toRequestBody("text/plain".toMediaTypeOrNull())
        // Diarization models require a chunking_strategy; "auto" lets the server segment the audio.
        val chunkingBody = "auto".toRequestBody("text/plain".toMediaTypeOrNull())

        // Hard cap at 4 known references (OpenAI limit); names and refs are positional pairs.
        val refs = knownSpeakers.take(Constants.DIARIZE_MAX_KNOWN_SPEAKERS)
        val nameParts = refs.map {
            MultipartBody.Part.createFormData("known_speaker_names[]", it.name)
        }
        val refParts = refs.map {
            MultipartBody.Part.createFormData(
                "known_speaker_references[]", it.sample.name,
                it.sample.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
        }

        val apiKey = apiKeyFlow.first()
        Log.d(
            "DiarizeApi",
            "Diarizing ${audioFile.name} (${audioFile.length()} bytes) with ${refs.size} known voice(s)"
        )

        val response = service.diarize(
            auth = "Bearer $apiKey",
            file = filePart,
            model = modelBody,
            responseFormat = formatBody,
            chunkingStrategy = chunkingBody,
            knownSpeakerNames = nameParts,
            knownSpeakerRefs = refParts
        )

        val knownNames = refs.map { it.name }.toSet()
        return (response.segments ?: emptyList())
            .filter { it.text.isNotBlank() }
            .map { seg ->
                // A speaker equal to one of our known names is a real identity; anything else is
                // an anonymous code (kept as rawCode) that does NOT carry meaning across windows.
                val knownName = seg.speaker?.takeIf { it in knownNames }
                SpeakerTurn(
                    knownName = knownName,
                    rawCode = if (knownName == null) seg.speaker else null,
                    startMs = windowStartMs + (seg.start * 1000).toLong(),
                    endMs = windowStartMs + (seg.end * 1000).toLong(),
                    text = seg.text.trim()
                )
            }
    }
}
