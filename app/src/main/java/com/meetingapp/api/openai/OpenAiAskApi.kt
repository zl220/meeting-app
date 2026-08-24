package com.meetingapp.api.openai

import com.meetingapp.api.AiRequest
import com.meetingapp.api.AskAiApi
import com.meetingapp.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named

class OpenAiAskApi @Inject constructor(
    private val service: OpenAiService,
    @Named("openai_api_key_flow") private val apiKeyFlow: Flow<String>
) : AskAiApi {

    override suspend fun ask(request: AiRequest): String {
        val apiKey = apiKeyFlow.first()
        val systemPrompt = buildSystemPrompt(request)
        val userContent = buildUserContent(request)

        val chatRequest = ChatRequest(
            model = Constants.MODEL_CHAT,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userContent)
            )
        )

        val response = service.chat("Bearer $apiKey", chatRequest)

        return response.choices.firstOrNull()?.message?.content?.trim() ?: ""
    }

    private fun buildSystemPrompt(req: AiRequest): String {
        val names = if (req.participantNames.isNotEmpty()) {
            "与会者：${req.participantNames.joinToString("、")}。"
        } else ""

        val timeNote = if (req.remainingMinutes != null && req.remainingMinutes <= 10) {
            "距会议结束还剩约 ${req.remainingMinutes} 分钟，请只说结论。"
        } else ""

        val roleNote = if (req.rolePrompt != null) "请从${req.rolePrompt}角度发言。" else ""

        return """
你是这场会议的 AI 参与者，你的名字叫"${req.aiName}"。你的发言会被朗读出来，所有人都会听到。
$names
规则：
- 两三句话说完，不要冗长
- 有观点，不和稀泥
- 只用上面列出的与会者名字指代在场的人，绝对不要编造或使用任何其他名字
- 不知道就说不知道
- 只回应被问的问题，不要顺带总结全场
$timeNote
$roleNote
        """.trimIndent()
    }

    private fun buildUserContent(req: AiRequest): String {
        return buildString {
            append("## 当前会议记录\n")
            append(req.meetingContext)
            append("\n\n## 问题\n")
            append(req.question)
        }
    }
}
