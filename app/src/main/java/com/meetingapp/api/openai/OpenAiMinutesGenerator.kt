package com.meetingapp.api.openai

import com.meetingapp.data.db.entity.Segment
import com.meetingapp.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named

class OpenAiMinutesGenerator @Inject constructor(
    private val service: OpenAiService,
    @Named("openai_api_key_flow") private val apiKeyFlow: Flow<String>
) {
    suspend fun generate(
        title: String,
        agenda: String?,
        segments: List<Segment>
    ): String {
        val transcript = buildTranscript(segments)
        val agendaNote = if (!agenda.isNullOrBlank()) "议程：$agenda\n\n" else ""

        val systemPrompt = """
你是一个专业会议记录助手。根据下方对话记录生成结构化纪要，使用 Markdown 格式，包含以下四个部分：
1. **讨论主题**
2. **每位参会者的核心观点**（按人名列出）
3. **达成的共识**
4. **待办事项与负责人**

要求：
- 清理口语词（嗯、那个、这个、然后等）
- 按话题分段
- AI 发言单独成一块，标题为「AI 参与意见」
- 不要捏造内容，只提炼实际出现的信息
        """.trimIndent()

        val userContent = "# 会议：$title\n\n${agendaNote}## 对话记录\n\n$transcript"

        val request = ChatRequest(
            model = Constants.MODEL_CHAT,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userContent)
            ),
            maxTokens = 2000
        )

        val apiKey = apiKeyFlow.first()
        val response = service.chat("Bearer $apiKey", request)
        return response.choices.firstOrNull()?.message?.content?.trim() ?: ""
    }

    private fun buildTranscript(segments: List<Segment>): String =
        segments.joinToString("\n") { seg ->
            val speaker = if (seg.isAi) "【AI】" else (seg.speakerName ?: seg.speakerLabel)
            "$speaker：${seg.text}"
        }
}
