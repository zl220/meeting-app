package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.repository.MeetingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeetingListViewModel @Inject constructor(
    private val meetingRepo: MeetingRepository
) : ViewModel() {

    val meetings: StateFlow<List<Meeting>> = meetingRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteMeeting(meeting: Meeting) {
        viewModelScope.launch {
            meetingRepo.delete(meeting)
        }
    }
}
