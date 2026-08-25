package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.ParticipantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeetingSetupUiState(
    val editingMeetingId: Long? = null,   // null = creating a new meeting
    val title: String = "",
    val agenda: String = "",
    val location: String = "",
    val durationMinutes: Int = 60,
    val selectedParticipantIds: Set<Long> = emptySet(),
    val isSaving: Boolean = false,
    val savedMeetingId: Long? = null
) {
    val isEditing: Boolean get() = editingMeetingId != null
}

@HiltViewModel
class MeetingSetupViewModel @Inject constructor(
    private val meetingRepo: MeetingRepository,
    private val participantRepo: ParticipantRepository
) : ViewModel() {

    val uiState = MutableStateFlow(MeetingSetupUiState())
    val allParticipants: StateFlow<List<Participant>> = participantRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Load an existing meeting into the form for editing. Called once from the edit screen. */
    fun loadForEdit(meetingId: Long) {
        if (uiState.value.editingMeetingId == meetingId) return
        viewModelScope.launch {
            val meeting = meetingRepo.getById(meetingId) ?: return@launch
            val participantIds = meetingRepo.getParticipants(meetingId).map { it.id }.toSet()
            uiState.update {
                it.copy(
                    editingMeetingId = meetingId,
                    title = meeting.title,
                    agenda = meeting.agenda.orEmpty(),
                    location = meeting.location.orEmpty(),
                    durationMinutes = meeting.estimatedDurationMinutes,
                    selectedParticipantIds = participantIds
                )
            }
        }
    }

    fun updateTitle(v: String) = uiState.update { it.copy(title = v) }
    fun updateAgenda(v: String) = uiState.update { it.copy(agenda = v) }
    fun updateLocation(v: String) = uiState.update { it.copy(location = v) }
    fun updateDuration(mins: Int) = uiState.update { it.copy(durationMinutes = mins) }

    fun toggleParticipant(id: Long) = uiState.update { state ->
        val ids = if (id in state.selectedParticipantIds)
            state.selectedParticipantIds - id
        else
            state.selectedParticipantIds + id
        state.copy(selectedParticipantIds = ids)
    }

    fun saveParticipant(participant: Participant) {
        viewModelScope.launch { participantRepo.save(participant) }
    }

    fun deleteParticipant(participant: Participant) {
        viewModelScope.launch { participantRepo.delete(participant) }
    }

    fun createMeeting() {
        val state = uiState.value
        if (state.title.isBlank()) return
        viewModelScope.launch {
            uiState.update { it.copy(isSaving = true) }
            val id = meetingRepo.create(
                Meeting(
                    title = state.title,
                    agenda = state.agenda.ifBlank { null },
                    location = state.location.ifBlank { null },
                    estimatedDurationMinutes = state.durationMinutes
                )
            )
            meetingRepo.setParticipants(id, state.selectedParticipantIds.toList())
            uiState.update { it.copy(isSaving = false, savedMeetingId = id) }
        }
    }

    /** Save edits to an existing meeting. Preserves status/timestamps/recording path. */
    fun saveEdits() {
        val state = uiState.value
        val id = state.editingMeetingId ?: return
        if (state.title.isBlank()) return
        viewModelScope.launch {
            uiState.update { it.copy(isSaving = true) }
            val existing = meetingRepo.getById(id)
            if (existing != null) {
                meetingRepo.update(
                    existing.copy(
                        title = state.title,
                        agenda = state.agenda.ifBlank { null },
                        location = state.location.ifBlank { null },
                        estimatedDurationMinutes = state.durationMinutes
                    )
                )
                meetingRepo.setParticipants(id, state.selectedParticipantIds.toList())
            }
            uiState.update { it.copy(isSaving = false, savedMeetingId = id) }
        }
    }
}
