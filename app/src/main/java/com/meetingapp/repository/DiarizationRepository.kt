package com.meetingapp.repository

import android.content.Context
import android.util.Log
import com.meetingapp.api.DiarizeApi
import com.meetingapp.api.KnownSpeaker
import com.meetingapp.api.SpeakerTurn
import com.meetingapp.data.db.dao.PendingVoiceSampleDao
import com.meetingapp.data.db.dao.SegmentDao
import com.meetingapp.data.db.dao.VoiceSampleDao
import com.meetingapp.data.db.entity.PendingVoiceSample
import com.meetingapp.data.db.entity.VoiceSample
import com.meetingapp.service.WavClip
import com.meetingapp.service.WindowFile
import com.meetingapp.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A participant's stored voice samples, for the settings voice-library view. */
data class NamedVoiceGroup(
    val participantId: Long,
    val participantName: String,
    val samples: List<VoiceSample>
)

/** A still-anonymous captured speaker (meeting + 发言人X) and its clips, awaiting a name. */
data class PendingVoiceGroup(
    val meetingId: Long,
    val speakerLabel: String,
    val clips: List<PendingVoiceSample>
)

/** The whole voice library at a glance: named participants + unnamed captured speakers. */
data class VoiceLibrarySnapshot(
    val named: List<NamedVoiceGroup>,
    val pending: List<PendingVoiceGroup>
) {
    val namedSampleCount: Int get() = named.sumOf { it.samples.size }
    val pendingSampleCount: Int get() = pending.sumOf { it.clips.size }
}

/**
 * In-meeting incremental diarization (see plan). For each ~5-minute window:
 *  1. build ≤4 known-voice references from participants who have a stored [VoiceSample]
 *     (best-scored clip per person),
 *  2. run gpt-4o-transcribe-diarize on the window,
 *  3. FUSE the result onto the live whisper segments — backfilling speakerName/speakerLabel by
 *     time overlap WITHOUT touching text, so the on-screen transcript never jumps,
 *  4. capture scored clips for each anonymous speaker into the pending voice table so the user
 *     can name them later (kept up to the retention window) and grow the voice library.
 *
 * Best-effort throughout: any failure logs and returns; the live transcript is unaffected.
 */
@Singleton
class DiarizationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diarizeApi: DiarizeApi,
    private val segmentDao: SegmentDao,
    private val voiceSampleDao: VoiceSampleDao,
    private val pendingVoiceSampleDao: PendingVoiceSampleDao,
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
     * Pick up to 4 attendees who have a stored voice sample, contributing each person's
     * best-scored clip. Ordered by that clip's quality so the strongest references win the
     * limited 4 slots (degrade path for meetings with >4 known people).
     */
    private suspend fun buildKnownSpeakers(meetingId: Long): List<KnownSpeaker> {
        val attendeeIds = meetingRepo.getParticipants(meetingId).map { it.id }.toSet()
        if (attendeeIds.isEmpty()) return emptyList()
        return voiceSampleDao.getBestPerParticipant()
            .filter { it.participant.id in attendeeIds }
            .sortedByDescending { it.sample.qualityScore }
            .map { KnownSpeaker(it.participant.name, File(it.sample.filePath)) }
            .filter { it.sample.exists() }
            .take(Constants.DIARIZE_MAX_KNOWN_SPEAKERS)
    }

    /**
     * Backfill each diarized turn onto the overlapping live segments and, for anonymous
     * speakers, capture a scored clip into the pending voice table for later naming.
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
     * Capture a scored voice clip for an anonymous speaker turn into the pending table. Multiple
     * clips per (meeting, label) accumulate so the best one can later be promoted; a small cap
     * per speaker avoids unbounded growth on a long meeting.
     */
    private suspend fun capturePendingClip(
        meetingId: Long,
        window: WindowFile,
        turn: SpeakerTurn,
        label: String
    ) {
        val existing = pendingVoiceSampleDao.getForSpeaker(meetingId, label)
        if (existing.size >= MAX_PENDING_PER_SPEAKER) return

        val dir = File(context.filesDir, Constants.PENDING_VOICE_SAMPLE_DIR).also { it.mkdirs() }
        val safe = label.replace(Regex("[^\\p{L}\\p{N}]"), "_")
        val dest = File(dir, "pending_${meetingId}_${safe}_${window.startMs + turn.startMs}.wav")

        val fromMs = (turn.startMs - window.startMs).coerceAtLeast(0)
        val toMs = (turn.endMs - window.startMs).coerceAtLeast(fromMs)
        val clip = WavClip.extract(window.file, fromMs, toMs, dest) ?: return
        pendingVoiceSampleDao.insert(
            PendingVoiceSample(
                meetingId = meetingId,
                speakerLabel = label,
                filePath = dest.absolutePath,
                durationMs = clip.durationMs,
                qualityScore = clip.qualityScore
            )
        )
    }

    /**
     * Promote a named speaker's captured clips into the participant's voice library. All pending
     * clips for (meetingId, label) become [VoiceSample]s; the best-scored one is what future
     * meetings will use as the reference. Clears the pending rows. Returns the number promoted.
     */
    suspend fun promoteSpeakerToVoiceLibrary(meetingId: Long, label: String, participantId: Long): Int {
        val pending = pendingVoiceSampleDao.getForSpeaker(meetingId, label)
        if (pending.isEmpty()) return 0
        val samplesDir = File(context.filesDir, Constants.VOICE_SAMPLE_DIR).also { it.mkdirs() }
        var promoted = 0
        pending.forEach { p ->
            val src = File(p.filePath)
            if (!src.exists()) return@forEach
            val dest = File(samplesDir, "voice_${participantId}_${p.id}.wav")
            runCatching {
                src.copyTo(dest, overwrite = true)
                voiceSampleDao.insert(
                    VoiceSample(
                        participantId = participantId,
                        filePath = dest.absolutePath,
                        durationMs = p.durationMs,
                        qualityScore = p.qualityScore
                    )
                )
                promoted++
            }.onFailure { Log.e("DiarizationRepo", "promote clip ${p.id} failed", it) }
        }
        // Clear pending rows + their files now that they're promoted.
        pending.forEach { runCatching { File(it.filePath).delete() } }
        pendingVoiceSampleDao.deleteForSpeaker(meetingId, label)
        Log.d("DiarizationRepo", "Promoted $promoted clip(s) for '$label' → participant $participantId")
        return promoted
    }

    /** Anonymous speaker labels (发言人A/…) still awaiting a name in this meeting. */
    suspend fun pendingAnonLabels(meetingId: Long): List<String> =
        pendingVoiceSampleDao.getPendingLabels(meetingId)

    // ---- Voice library management (Settings screen) ----

    /**
     * Snapshot of the whole voice library for the settings view: every named participant's
     * samples (grouped) and every still-anonymous captured clip (grouped by meeting+label).
     */
    suspend fun voiceLibrarySnapshot(): VoiceLibrarySnapshot {
        val named = voiceSampleDao.getAllWithParticipant()
            .groupBy { it.participant.id }
            .map { (_, rows) ->
                NamedVoiceGroup(
                    participantId = rows.first().participant.id,
                    participantName = rows.first().participant.name,
                    samples = rows.map { it.sample }
                )
            }
            .sortedBy { it.participantName }
        val pending = pendingVoiceSampleDao.getAll()
            .groupBy { it.meetingId to it.speakerLabel }
            .map { (key, clips) ->
                PendingVoiceGroup(
                    meetingId = key.first,
                    speakerLabel = key.second,
                    clips = clips
                )
            }
            .sortedWith(compareBy({ it.meetingId }, { it.speakerLabel }))
        return VoiceLibrarySnapshot(named = named, pending = pending)
    }

    /** Delete a single named voice sample (row + on-disk file). */
    suspend fun deleteVoiceSample(sampleId: Long) {
        val sample = voiceSampleDao.getById(sampleId) ?: return
        runCatching { File(sample.filePath).delete() }
        voiceSampleDao.deleteById(sampleId)
        Log.d("DiarizationRepo", "Deleted voice sample $sampleId")
    }

    /** Delete a single still-anonymous captured clip (row + on-disk file). */
    suspend fun deletePendingSample(sampleId: Long) {
        val sample = pendingVoiceSampleDao.getById(sampleId) ?: return
        runCatching { File(sample.filePath).delete() }
        pendingVoiceSampleDao.deleteById(sampleId)
        Log.d("DiarizationRepo", "Deleted pending voice sample $sampleId")
    }

    /**
     * Delete unnamed pending voice clips older than the retention window (files + rows). Named
     * [VoiceSample]s are never touched. Called from the periodic audio sweep.
     */
    suspend fun sweepExpiredPending(cutoffMs: Long) {
        val expired = pendingVoiceSampleDao.getOlderThan(cutoffMs)
        if (expired.isEmpty()) return
        expired.forEach { runCatching { File(it.filePath).delete() } }
        pendingVoiceSampleDao.deleteOlderThan(cutoffMs)
        Log.d("DiarizationRepo", "Swept ${expired.size} expired pending voice sample(s)")
    }

    private companion object {
        // Cap clips kept per anonymous speaker per meeting; the best is promoted on naming.
        const val MAX_PENDING_PER_SPEAKER = 5
    }
}
