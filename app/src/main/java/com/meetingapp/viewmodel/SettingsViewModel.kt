package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.repository.ParticipantRepository
import com.meetingapp.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val aiWakeName: String = "小谈",
    val saved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val participantRepo: ParticipantRepository
) : ViewModel() {

    val uiState = MutableStateFlow(SettingsUiState())

    /** The global contact list, reused across meetings. */
    val participants: StateFlow<List<Participant>> = participantRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val key = settingsRepo.apiKey.first()
            val name = settingsRepo.aiWakeName.first()
            uiState.update { it.copy(apiKey = key, aiWakeName = name) }
        }
    }

    fun updateApiKey(v: String) = uiState.update { it.copy(apiKey = v, saved = false) }
    fun updateAiWakeName(v: String) = uiState.update { it.copy(aiWakeName = v, saved = false) }

    fun save() {
        viewModelScope.launch {
            settingsRepo.setApiKey(uiState.value.apiKey)
            settingsRepo.setAiWakeName(uiState.value.aiWakeName)
            uiState.update { it.copy(saved = true) }
        }
    }

    fun addParticipant(name: String, email: String) {
        if (name.isBlank()) return
        viewModelScope.launch { participantRepo.save(Participant(name = name.trim(), email = email.trim())) }
    }

    fun deleteParticipant(participant: Participant) {
        viewModelScope.launch { participantRepo.delete(participant) }
    }
}
