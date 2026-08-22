package com.meetingapp.api

import com.meetingapp.data.db.entity.Segment

interface TranscribeApi {
    suspend fun transcribe(
        audioFile: java.io.File,
        meetingId: Long,
        chunkStartMs: Long,
        keywords: List<String>,
        prompt: String,
        languages: List<String>
    ): List<Segment>
}
