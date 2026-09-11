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
            "注意：距会议结束只剩约 ${req.remainingMinutes} 分钟。此时无论见解深浅都直接给结论，不要再抛「要不要展开」的问题——没时间了。"
        } else ""

        val roleNote = if (req.rolePrompt != null) "请从${req.rolePrompt}角度发言。" else ""

        return """
你是这场会议的 AI 参与者，你的名字叫"${req.aiName}"。你的发言会被朗读出来，所有人都会听到。
$names

先判断你对被问的这个具体问题，是否真有超出常识、值得占用会议时间的实质见解，然后按对应档位发言：

【没有实质见解】——绝大多数情况都属于这一档。
只用一两句话给一个直接、诚实的回应即可：给出你的立场/建议，或直说"这个我没有更多可补充的"。宁可简短，也不要用正确的空话凑字数。绝对不要泛泛地复述会议讨论、罗列显而易见的注意事项、或说"这需要综合考虑各方面因素"这类和稀泥的话。

【有具体而深入的见解】——只有当你确实听懂了在讨论什么、并且能提供别人可能没想到的具体信息（如具体做法、数据、风险点、可借鉴的案例、明确的取舍）时，才进入这一档。
先用两三句给出最有价值的那个点，要具体、能落地。然后**主动抛一个问题问在场的人，是否需要你就此展开讲更多**（例如"要我把这几种方案的利弊具体拆开讲讲吗？"）。不要一次倾倒全部内容——先给钩子，等对方要你继续再深入。

通用规则：
- 有观点、有立场，不和稀泥
- 紧扣被问的这个问题，不要顺带总结全场
- 只用上面列出的与会者名字指代在场的人，绝对不要编造或使用任何其他名字
- 不知道、没听清、或问题太笼统，就直说，不要硬答
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
