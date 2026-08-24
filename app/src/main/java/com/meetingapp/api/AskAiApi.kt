package com.meetingapp.api

data class AiRequest(
    val meetingContext: String,
    val question: String,
    val aiName: String = "AI",
    val rolePrompt: String? = null,
    val participantNames: List<String> = emptyList(),
    val remainingMinutes: Int? = null
)

interface AskAiApi {
    suspend fun ask(request: AiRequest): String
}
