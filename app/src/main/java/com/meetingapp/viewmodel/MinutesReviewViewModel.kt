package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Minutes
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.MinutesRepository
import com.meetingapp.repository.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeakerMapping(val label: String, val assignedName: String?)

data class MinutesReviewUiState(
    val meeting: Meeting? = null,
    val participants: List<Participant> = emptyList(),
    val minutes: Minutes? = null,
    val editedContent: String = "",
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val savedToDrive: Boolean = false,
    val driveUrl: String? = null,
    val emailSent: Boolean = false,
    val speakerMappings: List<SpeakerMapping> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class MinutesReviewViewModel @Inject constructor(
    private val meetingRepo: MeetingRepository,
    private val minutesRepo: MinutesRepository,
    private val transcriptionRepo: TranscriptionRepository
) : ViewModel() {

    val uiState = MutableStateFlow(MinutesReviewUiState())
    private var meetingId: Long = -1

    fun load(id: Long) {
        meetingId = id
        viewModelScope.launch {
            val meeting = meetingRepo.getById(id) ?: return@launch
            val participants = meetingRepo.getParticipants(id)
            val segments = transcriptionRepo.getAllSegmentsOnce(id)

            val mappings = segments
                .filter { !it.isAi }
                .map { it.speakerLabel }
                .distinct()
                .map { label ->
                    val assigned = segments.firstOrNull { it.speakerLabel == label }?.speakerName
                    SpeakerMapping(label, assigned)
                }

            uiState.update {
                it.copy(meeting = meeting, participants = participants, speakerMappings = mappings)
            }
            generateMinutes(meeting, segments)
        }
    }

    private suspend fun generateMinutes(meeting: Meeting, segments: List<Segment>) {
        uiState.update { it.copy(isGenerating = true, error = null) }
        val minutes = try {
            minutesRepo.generate(meeting, segments)
        } catch (e: Exception) {
            uiState.update { it.copy(isGenerating = false, error = "生成失败：${e.message}") }
            return
        }
        uiState.update {
            it.copy(isGenerating = false, minutes = minutes, editedContent = minutes.content)
        }
    }

    fun updateContent(content: String) = uiState.update { it.copy(editedContent = content) }

    fun assignSpeakerName(label: String, name: String) {
        viewModelScope.launch {
            transcriptionRepo.assignSpeakerName(meetingId, label, name)
            uiState.update { state ->
                state.copy(
                    speakerMappings = state.speakerMappings.map {
                        if (it.label == label) it.copy(assignedName = name) else it
                    }
                )
            }
        }
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
