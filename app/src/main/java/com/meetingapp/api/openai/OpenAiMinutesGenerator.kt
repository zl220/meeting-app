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
你是一个专业会议记录助手。根据下方对话记录生成结构化纪要，使用 Markdown 格式。

$ATTRIBUTION_RULES

纪要结构（按话题组织，而非强行按人分）：
1. **讨论主题**
2. **主要观点与讨论**——按议题归纳要点；只有在能确信的情况下才署名，否则用「有观点认为…」「有人提出…」等不指名的表述
3. **达成的共识**
4. **待办事项**——如能确信负责人则注明，否则写「待指派」

其他要求：
- 清理口语词（嗯、那个、这个、然后等）
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

    /**
     * Incrementally revise an existing draft (R10 / T1). Sends the current draft plus
     * ONLY the new transcript since the last refresh, asking the model to merge new
     * content into the existing minutes rather than rewrite from scratch. Cheaper and
     * more stable than re-sending the whole transcript. Returns the revised minutes.
     */
    suspend fun revise(
        title: String,
        agenda: String?,
        currentDraft: String,
        newSegments: List<Segment>
    ): String {
        if (newSegments.isEmpty()) return currentDraft
        val newTranscript = buildTranscript(newSegments)
        val agendaNote = if (!agenda.isNullOrBlank()) "议程：$agenda\n\n" else ""

        val systemPrompt = """
你是一个专业会议记录助手，正在会议进行中实时维护一份会议纪要。
下面给你「当前纪要」和「新增的对话记录」。请把新增内容合并进当前纪要，输出**完整的更新后纪要**。

$ATTRIBUTION_RULES

要求：
- 在现有纪要基础上追加/修订，不要推翻已有结构，保持稳定
- 保持原有 Markdown 结构：讨论主题、主要观点与讨论、达成的共识、待办事项；AI 发言归入「AI 参与意见」
- 清理口语词（嗯、那个、这个、然后等）
- 不要捏造内容，只提炼实际出现的信息
- 直接输出更新后的纪要全文，不要解释你做了什么
        """.trimIndent()

        val userContent = buildString {
            append("# 会议：$title\n\n")
            append(agendaNote)
            append("## 当前纪要\n\n")
            append(currentDraft.ifBlank { "（暂无，请根据新增对话首次生成）" })
            append("\n\n## 新增对话记录\n\n")
            append(newTranscript)
        }

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
        return response.choices.firstOrNull()?.message?.content?.trim()?.ifBlank { currentDraft }
            ?: currentDraft
    }

    /**
     * Build the transcript for the prompt. Speech with no assigned real name is labeled
     * "发言人未知" so the model can't mistake the generic "发言" tag for a person. Lines
     * with a real speaker name (manually assigned) keep it.
     */
    private fun buildTranscript(segments: List<Segment>): String =
        segments.joinToString("\n") { seg ->
            val speaker = when {
                seg.isAi -> "【AI】"
                seg.speakerName != null -> seg.speakerName
                else -> "发言人未知"
            }
            "$speaker：${seg.text}"
        }

    companion object {
        // Shared rules that stop the model from inventing who-said-what when the transcript
        // has no reliable speaker attribution (whisper-1 does no diarization).
        private val ATTRIBUTION_RULES = """
关于发言人归属（重要，避免出错）：
- 语音无法自动区分说话人。标为「发言人未知」的内容，说话人不明，**严禁猜测或臆断是谁说的**。
- 只有以下情况才可将观点归于某人：(1) 该发言已标注了具体人名；(2) 发言内容本身明确表明了身份（如「我是张三…」「作为财务，我认为…」）。
- 其余一律不点名，用「有观点认为…」「有人提出…」「会上讨论到…」等中性表述。
- 宁可不标明是谁说的，也不要错误归属。
        """.trimIndent()
    }
}
