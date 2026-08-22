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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MinutesReviewUiState(
    val meeting: Meeting? = null,
    val participants: List<Participant> = emptyList(),
    val minutes: Minutes? = null,
    val editedContent: String = "",
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val savedToDrive: Boolean = false,
    val emailSent: Boolean = false,
    val speakerLabels: List<String> = emptyList(),
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

    val latestMinutes: StateFlow<Minutes?> get() = _latestMinutes
    private lateinit var _latestMinutes: StateFlow<Minutes?>

    fun load(id: Long) {
        meetingId = id
        _latestMinutes = minutesRepo.getLatest(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        viewModelScope.launch {
            val meeting = meetingRepo.getById(id) ?: return@launch
            val participants = meetingRepo.getParticipants(id)
            val segments = transcriptionRepo.getAllSegmentsOnce(id)
            val labels = segments.map { it.speakerLabel }.distinct().filter { !it.startsWith("AI") }
            uiState.update {
                it.copy(meeting = meeting, participants = participants, speakerLabels = labels)
            }
            generateMinutes(meeting, segments)
        }
    }

    private suspend fun generateMinutes(meeting: Meeting, segments: List<Segment>) {
        uiState.update { it.copy(isGenerating = true) }
        val minutes = try {
            minutesRepo.generate(meeting, segments)
        } catch (e: Exception) {
            uiState.update { it.copy(isGenerating = false, error = e.message) }
            return
        }
        uiState.update {
            it.copy(isGenerating = false, minutes = minutes, editedContent = minutes.content)
        }
    }

    fun updateContent(content: String) {
        uiState.update { it.copy(editedContent = content) }
    }

    fun assignSpeakerName(label: String, name: String) {
        viewModelScope.launch {
            transcriptionRepo.assignSpeakerName(meetingId, label, name)
        }
    }

    fun saveToDrive() {
        val state = uiState.value
        val meeting = state.meeting ?: return
        val minutes = state.minutes ?: return
        viewModelScope.launch {
            uiState.update { it.copy(isSaving = true) }
            val finalContent = state.editedContent
            if (finalContent != minutes.content) {
                minutesRepo.updateContent(minutes.id, finalContent)
            }
            try {
                minutesRepo.saveToDrive(meeting, minutes.copy(content = finalContent))
                uiState.update { it.copy(savedToDrive = true) }
            } catch (e: Exception) {
                uiState.update { it.copy(error = e.message) }
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
            try {
                minutesRepo.sendEmail(meeting, minutes.copy(content = state.editedContent), state.participants)
                uiState.update { it.copy(emailSent = true) }
            } catch (e: Exception) {
                uiState.update { it.copy(error = e.message) }
            }
        }
    }
}
