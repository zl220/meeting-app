package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Minutes
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.repository.DiarizationRepository
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.MinutesRepository
import com.meetingapp.repository.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MinutesReviewUiState(
    val meeting: Meeting? = null,
    val participants: List<Participant> = emptyList(),
    val minutes: Minutes? = null,
    val editedContent: String = "",
    // Anonymous diarized speakers (发言人A/B/…) with a captured clip, awaiting a name.
    // Naming one adds their voice to the library so they auto-identify next time.
    val unnamedSpeakerLabels: List<String> = emptyList(),
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val savedToDrive: Boolean = false,
    val driveUrl: String? = null,
    val emailSent: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MinutesReviewViewModel @Inject constructor(
    private val meetingRepo: MeetingRepository,
    private val minutesRepo: MinutesRepository,
    private val transcriptionRepo: TranscriptionRepository,
    private val diarizationRepo: DiarizationRepository
) : ViewModel() {

    val uiState = MutableStateFlow(MinutesReviewUiState())
    private var meetingId: Long = -1

    fun load(id: Long) {
        meetingId = id
        viewModelScope.launch {
            val meeting = meetingRepo.getById(id) ?: return@launch
            val participants = meetingRepo.getParticipants(id)
            val segments = transcriptionRepo.getAllSegmentsOnce(id)

            uiState.update {
                it.copy(
                    meeting = meeting,
                    participants = participants,
                    unnamedSpeakerLabels = diarizationRepo.pendingAnonLabels(id)
                )
            }
            generateMinutes(meeting, segments)
        }
    }

    private suspend fun generateMinutes(meeting: Meeting, segments: List<Segment>) {
        // Already finalized (e.g. re-opening a finished meeting): show it, don't regenerate.
        val existing = minutesRepo.getFinalized(meeting.id)
        if (existing != null) {
            uiState.update {
                it.copy(isGenerating = false, minutes = existing, editedContent = existing.content)
            }
            return
        }

        // Show the rolling draft instantly (if any) so the user isn't staring at a spinner
        // while the full finalize runs. The draft is unedited, so it's safe to replace.
        val draft = minutesRepo.getDraft(meeting.id)
        if (draft != null && draft.content.isNotBlank()) {
            uiState.update {
                it.copy(isGenerating = true, minutes = draft, editedContent = draft.content)
            }
        } else {
            uiState.update { it.copy(isGenerating = true, error = null) }
        }

        val minutes = try {
            // T3 (R10): first finalize — regenerate from the full transcript, clear the draft.
            minutesRepo.finalize(meeting, segments)
        } catch (e: Exception) {
            // Finalize failed: keep showing the draft (if we had one) so the user still sees
            // something usable, and surface the error non-fatally.
            uiState.update {
                it.copy(isGenerating = false, error = "精修纪要失败，显示的是实时草稿：${e.message}")
            }
            return
        }
        // Only overwrite the editor if the user hasn't started editing the draft in the meantime.
        uiState.update { state ->
            val userEdited = draft != null && state.editedContent != draft.content
            state.copy(
                isGenerating = false,
                minutes = minutes,
                editedContent = if (userEdited) state.editedContent else minutes.content
            )
        }
    }

    /**
     * Name a previously-anonymous diarized speaker (发言人A/…). Resolves/creates the participant,
     * relabels all their segments to the real name, and promotes their captured clip into the
     * voice library so they auto-identify in future meetings. Removes the label from the pending
     * list; the minutes text itself is left to the user's existing {{name:}} edits.
     */
    fun assignSpeakerToLabel(label: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val participant = meetingRepo.resolveOrCreateParticipant(meetingId, trimmed, label)
                transcriptionRepo.assignSpeakerName(meetingId, label, trimmed)
                diarizationRepo.promoteAnonClipToSample(meetingId, label, participant.id)
                uiState.update { st ->
                    st.copy(
                        unnamedSpeakerLabels = st.unnamedSpeakerLabels.filterNot { it == label },
                        participants = if (st.participants.any { it.id == participant.id }) st.participants
                                       else st.participants + participant
                    )
                }
            } catch (e: Exception) {
                uiState.update { it.copy(error = "标注发言人失败：${e.message}") }
            }
        }
    }

    fun updateContent(content: String) = uiState.update { it.copy(editedContent = content) }

    /** Update the minutes text AND persist immediately (used when assigning speakers). */
    fun updateAndPersist(content: String) {
        uiState.update { it.copy(editedContent = content) }
        val id = uiState.value.minutes?.id ?: return
        viewModelScope.launch { minutesRepo.updateContent(id, content) }
    }

    fun saveToDrive() {
        val state = uiState.value
        val meeting = state.meeting ?: return
        val minutes = state.minutes ?: return
        viewModelScope.launch {
            uiState.update { it.copy(isSaving = true, error = null) }
            val finalContent = state.editedContent
            if (finalContent != minutes.content) minutesRepo.updateContent(minutes.id, finalContent)
            try {
                val url = minutesRepo.saveToDrive(meeting, minutes.copy(content = finalContent))
                uiState.update { it.copy(savedToDrive = true, driveUrl = url) }
            } catch (e: Exception) {
                uiState.update { it.copy(error = "Drive 保存失败：${e.message}") }
            } finally {
                uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun sendEmail() {
        val state = uiState.value
        val meeting = state.meeting ?: return
        val minutes = state.minutes ?: return
        viewModelScope.launch {
            val finalContent = state.editedContent
            if (finalContent != minutes.content) minutesRepo.updateContent(minutes.id, finalContent)
            try {
                minutesRepo.sendEmail(
                    meeting,
                    minutes.copy(content = finalContent),
                    state.participants
                )
                uiState.update { it.copy(emailSent = true) }
            } catch (e: Exception) {
                uiState.update { it.copy(error = "发邮件失败：${e.message}") }
            }
        }
    }

    fun clearError() = uiState.update { it.copy(error = null) }
}
