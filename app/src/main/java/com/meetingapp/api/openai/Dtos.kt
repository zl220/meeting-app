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
