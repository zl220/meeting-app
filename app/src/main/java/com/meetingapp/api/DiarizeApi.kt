package com.meetingapp.api

import java.io.File

/** A voice reference for a known participant: their name + a 2–10s sample clip. */
data class KnownSpeaker(val name: String, val sample: File)

/**
 * One diarized speech turn.
 * [knownName] is a real participant name when a known reference matched, else null.
 * [rawCode] is the model's raw speaker code for the turn (e.g. "A"/"B") — stable WITHIN a
 * window only, used to group a window's anonymous turns; null if the model gave none.
 */
data class SpeakerTurn(
    val knownName: String?,
    val rawCode: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

interface DiarizeApi {
    /**
     * Diarize one audio window. [windowStartMs] is the window's offset from meeting start,
     * used to translate the model's window-relative seconds into meeting-absolute ms.
     * [knownSpeakers] is capped at 4 by the caller (OpenAI limit).
     */
    suspend fun diarize(
        audioFile: File,
        windowStartMs: Long,
        knownSpeakers: List<KnownSpeaker>
    ): List<SpeakerTurn>
}
