package com.meetingapp.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingapp.api.AiRequest
import com.meetingapp.api.AskAiApi
import com.meetingapp.api.openai.OpenAiTtsPlayer
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Participant
import com.meetingapp.data.db.entity.Segment
import com.meetingapp.repository.MeetingRepository
import com.meetingapp.repository.TranscriptionRepository
import com.meetingapp.service.ChunkFile
import com.meetingapp.service.RecordingService
import com.meetingapp.service.WakeWordDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AiState { IDLE, THINKING, SPEAKING }

data class ActiveMeetingUiState(
    val meeting: Meeting? = null,
    val participants: List<Participant> = emptyList(),
    val elapsedMs: Long = 0L,
    val aiState: AiState = AiState.IDLE,
    val pendingAiQuery: String? = null,
    val lastAiSegmentId: Long? = null,
    val error: String? = null
)

@HiltViewModel
class ActiveMeetingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meetingRepo: MeetingRepository,
    private val transcriptionRepo: TranscriptionRepository,
    private val askAiApi: AskAiApi,
    private val ttsPlayer: OpenAiTtsPlayer
) : ViewModel() {

    val uiState = MutableStateFlow(ActiveMeetingUiState())

    private val _meetingId = MutableStateFlow(-1L)
    private var meetingId: Long = -1

    // Stable flow: survives load() calls without recreating collectAsState()
    val segments: StateFlow<List<Segment>> = _meetingId
        .flatMapLatest { id ->
            if (id < 0) flowOf(emptyList()) else transcriptionRepo.getSegments(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var timerJob: Job? = null
    private var recordingService: RecordingService? = null
    private var aiJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            recordingService?.startRecording()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
        }
    }

    fun load(id: Long) {
        meetingId = id
        _meetingId.value = id
        viewModelScope.launch {
            val meeting = meetingRepo.getById(id) ?: return@launch
            val participants = meetingRepo.getParticipants(id)
            uiState.update { it.copy(meeting = meeting, participants = participants) }
        }
        // Wake-word detection: watch newest non-AI segment text
        viewModelScope.launch {
            segments
                .map { it.lastOrNull { seg -> !seg.isAi }?.text }
                .distinctUntilChanged()
                .collect { latestText ->
                    if (latestText == null) return@collect
                    val result = WakeWordDetector.detect(latestText) ?: return@collect
                    if (uiState.value.aiState == AiState.IDLE) {
                        askAi(result.query)
                    }
                }
        }
    }

    fun startMeeting() {
        viewModelScope.launch { meetingRepo.setStarted(meetingId) }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_MEETING_ID, meetingId)
        }
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startTimer()
    }

    fun stopMeeting() {
        timerJob?.cancel()
        val finalChunk = recordingService?.stopRecording()
        viewModelScope.launch {
            finalChunk?.let { processChunk(it) }
            meetingRepo.setFinished(meetingId)
        }
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
    }

    fun assignSpeakerName(label: String, name: String) {
        viewModelScope.launch {
            transcriptionRepo.assignSpeakerName(meetingId, label, name)
        }
    }

    fun askAi(question: String, rolePrompt: String? = null) {
        if (uiState.value.aiState != AiState.IDLE) return
        viewModelScope.launch {
            recordingService?.flushCurrentChunk()?.let { processChunk(it) }
        }
        uiState.update { it.copy(aiState = AiState.THINKING, pendingAiQuery = question) }
        aiJob = viewModelScope.launch {
            delay(2500)
            if (uiState.value.aiState != AiState.THINKING) return@launch
            runAiResponse(question, rolePrompt)
        }
    }

    fun cancelPendingAi() {
        aiJob?.cancel()
        ttsPlayer.interrupt()
        val lastId = uiState.value.lastAiSegmentId
        if (lastId != null) {
            viewModelScope.launch { transcriptionRepo.deleteSegment(lastId) }
        }
        uiState.update { it.copy(aiState = AiState.IDLE, pendingAiQuery = null, lastAiSegmentId = null) }
    }

    private suspend fun runAiResponse(question: String, rolePrompt: String?) {
        val state = uiState.value
        val meeting = state.meeting ?: return
        val names = state.participants.map { it.name }
        val remaining = ((meeting.estimatedDurationMinutes * 60_000L - state.elapsedMs)
            .coerceAtLeast(0) / 60_000).toInt()

        val response = try {
            askAiApi.ask(
                AiRequest(
                    meetingContext = buildContext(),
                    question = question,
                    rolePrompt = rolePrompt,
                    participantNames = names,
                    remainingMinutes = remaining
                )
            )
        } catch (e: Exception) {
            uiState.update { it.copy(aiState = AiState.IDLE, error = e.message) }
            return
        }

        val segId = transcriptionRepo.insertAiSegment(
            Segment(
                meetingId = meetingId,
                startMs = System.currentTimeMillis(),
                endMs = System.currentTimeMillis(),
                speakerLabel = "AI",
                speakerName = "AI",
                text = response,
                isAi = true
            )
        )
        uiState.update { it.copy(aiState = AiState.SPEAKING, lastAiSegmentId = segId) }
        try { ttsPlayer.speak(response) } catch (_: Exception) {}
        uiState.update { it.copy(aiState = AiState.IDLE, pendingAiQuery = null, lastAiSegmentId = null) }
    }

    private fun buildContext(): String =
        segments.value.takeLast(60).joinToString("\n") { seg ->
            val name = if (seg.isAi) "AI" else (seg.speakerName ?: seg.speakerLabel)
            "$name：${seg.text}"
        }

    private suspend fun processChunk(chunk: ChunkFile) {
        val state = uiState.value
        val meeting = state.meeting ?: return
        transcriptionRepo.processChunk(
            meetingId = meetingId,
            chunk = chunk,
            keywords = state.participants.map { it.name },
            prompt = meeting.agenda ?: meeting.title,
            languages = listOf("zh")
        )
    }

    private fun startTimer() {
        val startMs = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                uiState.update { it.copy(elapsedMs = System.currentTimeMillis() - startMs) }
            }
        }
    }

    fun checkWakeWord(text: String): WakeWordDetector.WakeResult? =
        WakeWordDetector.detect(text)

    override fun onCleared() {
        ttsPlayer.interrupt()
        super.onCleared()
    }
}
