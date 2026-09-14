package com.meetingapp.viewmodel

import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.PendingVoiceSample
import com.meetingapp.data.db.entity.VoiceSample
import com.meetingapp.repository.DiarizationRepository
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.ParticipantRepository
import com.meetingapp.repository.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** One named participant's stored voice samples (UI-facing). */
data class NamedVoiceRow(
    val participantId: Long,
    val participantName: String,
    val samples: List<VoiceSample>
)

/** One still-anonymous captured speaker (meeting + 发言人X) awaiting a name (UI-facing). */
data class PendingVoiceRow(
    val meetingId: Long,
    val meetingTitle: String,
    val speakerLabel: String,
    val clips: List<PendingVoiceSample>
)

data class VoiceLibraryUiState(
    val named: List<NamedVoiceRow> = emptyList(),
    val pending: List<PendingVoiceRow> = emptyList(),
    val namedCount: Int = 0,
    val pendingCount: Int = 0,
    val playingPath: String? = null,
    val message: String? = null
)

/**
 * Voice-library management for the settings screen: view how many voice samples are stored,
 * play a clip to hear who it is, annotate an unnamed speaker (promoting their clips into a
 * participant's library), and manually delete samples without waiting for the retention sweep.
 */
@HiltViewModel
class VoiceLibraryViewModel @Inject constructor(
    private val diarizationRepo: DiarizationRepository,
    private val meetingRepo: MeetingRepository,
    private val participantRepo: ParticipantRepository,
    private val transcriptionRepo: TranscriptionRepository
) : ViewModel() {

    val uiState = MutableStateFlow(VoiceLibraryUiState())

    /** The global contact list, offered as annotation targets. */
    val participants: StateFlow<List<Participant>> = participantRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var player: MediaPlayer? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snap = diarizationRepo.voiceLibrarySnapshot()
            val named = snap.named.map {
                NamedVoiceRow(it.participantId, it.participantName, it.samples)
            }
            // Resolve a friendly meeting title for each pending group.
            val titleCache = mutableMapOf<Long, String>()
            val pending = snap.pending.map { g ->
                val title = titleCache.getOrPut(g.meetingId) {
                    meetingRepo.getById(g.meetingId)?.title ?: "会议 ${g.meetingId}"
                }
                PendingVoiceRow(g.meetingId, title, g.speakerLabel, g.clips)
            }
            uiState.update {
                it.copy(
                    named = named,
                    pending = pending,
                    namedCount = snap.namedSampleCount,
                    pendingCount = snap.pendingSampleCount
                )
            }
        }
    }

    /** Play (or stop, if already playing) a voice clip so the user can hear who it is. */
    fun togglePlay(filePath: String) {
        if (uiState.value.playingPath == filePath) {
            stopPlayback()
            return
        }
        stopPlayback()
        val file = File(filePath)
        if (!file.exists()) {
            uiState.update { it.copy(message = "音频文件已丢失") }
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepare()
                start()
            }
            uiState.update { it.copy(playingPath = filePath) }
        }.onFailure {
            Log.e("VoiceLibraryVM", "play failed for $filePath", it)
            stopPlayback()
            uiState.update { it.copy(message = "播放失败") }
        }
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        if (uiState.value.playingPath != null) uiState.update { it.copy(playingPath = null) }
    }

    /**
     * Annotate an unnamed speaker: promote all their captured clips into [name]'s voice library
     * and relabel that speaker's segments in the source meeting, mirroring the review-screen flow.
     */
    fun annotatePending(row: PendingVoiceRow, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        stopPlayback()
        viewModelScope.launch {
            runCatching {
                val participant = meetingRepo.resolveOrCreateParticipant(
                    row.meetingId, trimmed, row.speakerLabel
                )
                transcriptionRepo.assignSpeakerName(row.meetingId, row.speakerLabel, trimmed)
                diarizationRepo.promoteSpeakerToVoiceLibrary(
                    row.meetingId, row.speakerLabel, participant.id
                )
            }.onSuccess {
                uiState.update { it.copy(message = "已将「${row.speakerLabel}」标注为 $trimmed") }
                refresh()
            }.onFailure { err ->
                Log.e("VoiceLibraryVM", "annotate failed", err)
                uiState.update { it.copy(message = "标注失败：${err.message}") }
            }
        }
    }

    fun deleteNamedSample(sampleId: Long) {
        stopPlayback()
        viewModelScope.launch {
            diarizationRepo.deleteVoiceSample(sampleId)
            refresh()
        }
    }

    fun deletePendingSample(sampleId: Long) {
        stopPlayback()
        viewModelScope.launch {
            diarizationRepo.deletePendingSample(sampleId)
            refresh()
        }
    }

    fun clearMessage() = uiState.update { it.copy(message = null) }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
