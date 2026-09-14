package com.meetingapp.api.openai

import com.google.gson.annotations.SerializedName

// --- Transcription ---

data class TranscriptionResponse(
    val text: String,
    val words: List<TranscriptionWord>? = null,
    val segments: List<TranscriptionSegment>? = null
)

data class TranscriptionWord(
    val word: String,
    val start: Double,
    val end: Double,
    val speaker: String? = null
)

data class TranscriptionSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null
)

// --- Diarization (gpt-4o-transcribe-diarize, response_format=diarized_json) ---
// Returns per-turn segments carrying a `speaker` (real name when a known reference matched,
// otherwise an anonymous code like "A"/"B"), plus start/end seconds and the turn text.

data class DiarizedResponse(
    val text: String? = null,
    val segments: List<DiarizedSegment>? = null
)

data class DiarizedSegment(
    val speaker: String? = null,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = ""
)

// --- Chat ---

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 800,
    val temperature: Double = 0.7
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<ChatChoice>
)

data class ChatChoice(
    val message: ChatMessage
)

// --- TTS ---

data class TtsRequest(
    val model: String,
    val input: String,
    val voice: String,
    @SerializedName("response_format") val responseFormat: String = "mp3",
    val speed: Double = 1.0
)
