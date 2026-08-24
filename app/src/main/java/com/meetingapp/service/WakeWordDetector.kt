package com.meetingapp.service

object WakeWordDetector {

    data class WakeResult(val query: String)

    fun detect(text: String, wakeName: String): WakeResult? {
        if (wakeName.isBlank()) return null
        val trimmed = text.trim()
        val escaped = Regex.escape(wakeName)
        // Match wake name anywhere in the text, followed by optional pause then the query.
        // E.g. "小娇，你来讲几句" / "小娇你能发表一下看法" / "好的小娇，请问..."
        val pattern = Regex("""$escaped[，,、\s]*(.{2,})""")
        val match = pattern.find(trimmed) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return null
        return WakeResult(query)
    }
}
