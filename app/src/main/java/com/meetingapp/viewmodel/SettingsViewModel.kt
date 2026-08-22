package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    val uiState = MutableStateFlow(SettingsUiState())

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
}
