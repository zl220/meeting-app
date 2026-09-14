package com.meetingapp.repository

import android.content.Context
import android.util.Log
import com.meetingapp.api.DiarizeApi
import com.meetingapp.api.KnownSpeaker
import com.meetingapp.api.SpeakerTurn
import com.meetingapp.data.db.dao.SegmentDao
import com.meetingapp.data.db.dao.VoiceSampleDao
import com.meetingapp.data.db.entity.VoiceSample
import com.meetingapp.service.WavClip
import com.meetingapp.service.WindowFile
import com.meetingapp.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-meeting incremental diarization (see plan). For each ~5-minute window:
 *  1. build ≤4 known-voice references from participants who have a stored [VoiceSample],
 *  2. run gpt-4o-transcribe-diarize on the window,
 *  3. FUSE the result onto the live whisper segments — backfilling speakerName/speakerLabel by
 *     time overlap WITHOUT touching text, so the on-screen transcript never jumps,
 *  4. stash a short clip per anonymous speaker so the user can name them after the meeting and
 *     grow the voice library.
 *
 * Best-effort throughout: any failure logs and returns; the live transcript is unaffected and
 * the post-meeting finalize re-runs on the full recording as a backstop.
 */
@Singleton
class DiarizationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diarizeApi: DiarizeApi,
    private val segmentDao: SegmentDao,
    private val voiceSampleDao: VoiceSampleDao,
    private val meetingRepo: MeetingRepository
) {
    /** Diarize one window and fuse the result onto the meeting's live segments. */
    suspend fun processWindow(meetingId: Long, window: WindowFile) {
        try {
            val knownSpeakers = buildKnownSpeakers(meetingId)
            val turns = diarizeApi.diarize(
                audioFile = window.file,
                windowStartMs = window.startMs,
                knownSpeakers = knownSpeakers
            )
            if (turns.isEmpty()) {
                Log.d("DiarizationRepo", "No speaker turns for ${window.file.name}")
                return
            }
            fuse(meetingId, window, turns)
        } catch (e: Exception) {
            Log.e("DiarizationRepo", "diarize failed for ${window.file.name}", e)
        } finally {
            // Window WAV is only needed for this one diarization + clip extraction.
            runCatching { window.file.delete() }
        }
    }

    /**
     * Pick up to 4 participants who both attend this meeting and have a stored voice sample,
     * most-recently-refreshed first (proxy for "high-frequency / most relevant"). This is the
     * degrade path for meetings with >4 known people: only these get auto-named this run.
     */
    private suspend fun buildKnownSpeakers(meetingId: Long): List<KnownSpeaker> {
        val attendeeIds = meetingRepo.getParticipants(meetingId).map { it.id }.toSet()
        if (attendeeIds.isEmpty()) return emptyList()
        return voiceSampleDao.getAllNamed()                       // newest-first
            .filter { it.participant.id in attendeeIds }
            .map { KnownSpeaker(it.participant.name, File(it.sample.filePath)) }
            .filter { it.sample.exists() }
            .take(Constants.DIARIZE_MAX_KNOWN_SPEAKERS)
    }

    /**
     * Backfill each diarized turn onto the overlapping live segments and, for anonymous
     * speakers, capture a representative clip for later naming.
     */
    private suspend fun fuse(meetingId: Long, window: WindowFile, turns: List<SpeakerTurn>) {
        // Anonymous speakers within THIS window get stable "发言人A/B/…" labels, grouped by the
        // model's raw per-window code. These do NOT correspond to anonymous labels in other
        // windows (documented limitation) — only known (named) speakers carry across windows.
        val anonCodeToLabel = LinkedHashMap<String, String>()

        turns.forEach { turn ->
            val label: String
            val name: String?
            if (turn.knownName != null) {
                label = turn.knownName          // real name matched a known reference
                name = turn.knownName
            } else {
                // Group by raw code so the same anonymous speaker in this window shares one label.
                val code = turn.rawCode ?: "?"
                label = anonCodeToLabel.getOrPut(code) {
                    Constants.SPEAKER_LABEL_ANON_PREFIX + ('A' + anonCodeToLabel.size)
                }
                name = null
            }
            segmentDao.backfillSpeakerByTime(
                meetingId = meetingId,
                startMs = turn.startMs,
                endMs = turn.endMs,
                label = label,
                name = name
            )
            if (name == null) {
                capturePendingClip(meetingId, window, turn, label)
            }
        }
        Log.d(
            "DiarizationRepo",
            "Fused ${turns.size} turn(s); ${anonCodeToLabel.size} anonymous speaker(s) in window"
        )
    }

    /**
     * Save one 2–10s clip per anonymous label per meeting (first occurrence wins) so the user
     * can name it in review. Kept out of the DB (no participant yet) as a plain file; promoted
     * to a [VoiceSample] by [promoteAnonClipToSample] when named.
     */
    private fun capturePendingClip(
        meetingId: Long,
        window: WindowFile,
        turn: SpeakerTurn,
        label: String
    ) {
        val dest = pendingClipFile(meetingId, label)
        if (dest.exists()) return   // already have a sample for this anon speaker
        dest.parentFile?.mkdirs()
        // Convert meeting-absolute turn ms back to window-file-relative ms.
        val fromMs = (turn.startMs - window.startMs).coerceAtLeast(0)
        val toMs = (turn.endMs - window.startMs).coerceAtLeast(fromMs)
        WavClip.extract(window.file, fromMs, toMs, dest)
    }

    /**
     * Promote a captured anonymous clip into a named [VoiceSample] when the user assigns a name
     * after the meeting. No-op if there's no pending clip for that label. Returns true on success.
     */
    suspend fun promoteAnonClipToSample(meetingId: Long, label: String, participantId: Long): Boolean {
        val pending = pendingClipFile(meetingId, label)
        if (!pending.exists()) return false
        val samplesDir = File(context.filesDir, Constants.VOICE_SAMPLE_DIR).also { it.mkdirs() }
        val dest = File(samplesDir, "voice_${participantId}.wav")
        return try {
            pending.copyTo(dest, overwrite = true)
            val durMs = wavDurationMs(dest)
            voiceSampleDao.upsert(
                VoiceSample(participantId = participantId, filePath = dest.absolutePath, durationMs = durMs)
            )
            true
        } catch (e: Exception) {
            Log.e("DiarizationRepo", "promote clip failed", e)
            false
        }
    }

    /**
     * Anonymous speaker labels (发言人A/B/…) captured for this meeting that still await a name.
     * Surfaced in the review screen so the user can name them and grow the voice library.
     */
    fun pendingAnonLabels(meetingId: Long): List<String> {
        val dir = File(context.filesDir, "audio/$meetingId")
        val files = dir.listFiles { f -> f.name.startsWith("pending_voice_") } ?: return emptyList()
        val prefix = Constants.SPEAKER_LABEL_ANON_PREFIX
        return files.mapNotNull { f ->
            // pending_voice_发言人A.wav → 发言人A (sanitizer keeps letters/digits intact)
            f.name.removePrefix("pending_voice_").removeSuffix(".wav")
                .takeIf { it.startsWith(prefix) }
        }.sorted()
    }

    private fun pendingClipFile(meetingId: Long, label: String): File {
        // Sanitize the label for a filename (anon labels like "发言人A" are safe, but be defensive).
        val safe = label.replace(Regex("[^\\p{L}\\p{N}]"), "_")
        return File(context.filesDir, "audio/$meetingId/pending_voice_$safe.wav")
    }

    private fun wavDurationMs(file: File): Long {
        val pcmBytes = (file.length() - 44).coerceAtLeast(0)
        return pcmBytes / (Constants.SAMPLE_RATE_HZ * 2L / 1000)
    }
}
