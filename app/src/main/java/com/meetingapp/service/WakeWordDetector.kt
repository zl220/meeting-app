package com.meetingapp.service

import com.meetingapp.util.Constants

object WakeWordDetector {

    private val wakePattern: Regex by lazy {
        // Matches: wake-name at sentence start, followed by pause/comma, then question/imperative
        // Examples: "小谈，你怎么看" / "小谈 请问" / "小谈你觉得"
        val name = Regex.escape(Constants.AI_WAKE_NAME)
        Regex("""(?:^|[。？！\n])$name[，,\s]*(.+)""")
    }

    data class WakeResult(val query: String)

    fun detect(text: String): WakeResult? {
        val trimmed = text.trim()
        val match = wakePattern.find(trimmed) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return null
        return WakeResult(query)
    }

    fun stripWakeWord(text: String): String =
        text.trim().removePrefix(Constants.AI_WAKE_NAME).trimStart('，', ',', ' ', '\t')
}
