package com.meetingapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.MeetingStatus
import com.meetingapp.repository.MeetingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MeetingListViewModel @Inject constructor(
    private val meetingRepo: MeetingRepository
) : ViewModel() {

    val meetings: StateFlow<List<Meeting>> = meetingRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One-shot: id of a just-created quick meeting to navigate straight into. Reset after consuming. */
    val quickMeetingId = MutableStateFlow<Long?>(null)

    fun deleteMeeting(meeting: Meeting) {
        viewModelScope.launch {
            meetingRepo.delete(meeting)
        }
    }

    /**
     * "直接开会": create a meeting with sensible defaults (title = current time, 60-min duration,
     * no agenda/participants) and hand back its id so the caller can jump straight to recording.
     * The user can edit any of this later. No-op if a meeting is already recording.
     */
    fun createQuickMeeting() {
        viewModelScope.launch {
            if (meetings.first().any { it.status == MeetingStatus.RECORDING }) return@launch
            val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
            val id = meetingRepo.create(
                Meeting(title = "会议 $stamp", estimatedDurationMinutes = DEFAULT_QUICK_DURATION_MIN)
            )
            quickMeetingId.update { id }
        }
    }

    fun consumeQuickMeetingId() = quickMeetingId.update { null }

    private companion object {
        const val DEFAULT_QUICK_DURATION_MIN = 60
    }
}
