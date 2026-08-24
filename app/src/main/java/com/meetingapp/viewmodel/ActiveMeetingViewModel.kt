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
import com.meetingapp.repository.MinutesRepository
import com.meetingapp.repository.SettingsRepository
import com.meetingapp.repository.TranscriptionRepository
import com.meetingapp.util.Constants
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class AiState { IDLE, THINKING, SPEAKING }

data class ActiveMeetingUiState(
    val meeting: Meeting? = null,
    val participants: List<Participant> = emptyList(),
    val elapsedMs: Long = 0L,
    // True when no OpenAI API key is set — transcription/AI/minutes will all fail silently otherwise.
    val apiKeyMissing: Boolean = false,
    val aiState: AiState = AiState.IDLE,
    val pendingAiQuery: String? = null,
    val lastAiSegmentId: Long? = null,
    // Minutes preview dialog (R10). previewOpen drives visibility; content is null while loading.
    val previewOpen: Boolean = false,
    val previewContent: String? = null,
    val error: String? = null
)

@HiltViewModel
class ActiveMeetingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meetingRepo: MeetingRepository,
    private val transcriptionRepo: TranscriptionRepository,
    private val minutesRepo: MinutesRepository,
    private val settingsRepo: SettingsRepository,
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
    private var amplitudeJob: Job? = null

    // Rolling minutes (R10). Serialize T1/T2 refreshes so they can't overwrite each other.
    private val minutesMutex = kotlinx.coroutines.sync.Mutex()
    private var lastFoldedSegmentId: Long = 0L   // highest segment id already in the draft
    private var lastRefreshAtMs: Long = 0L

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            recordingService?.startRecording()
            // Forward amplitude from service to ViewModel
            amplitudeJob = viewModelScope.launch {
                recordingService?.amplitude?.collect { _amplitude.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            amplitudeJob?.cancel()
            _amplitude.value = 0f
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
                    val wakeName = settingsRepo.aiWakeName.first()
                    val result = WakeWordDetector.detect(latestText, wakeName) ?: return@collect
                    if (uiState.value.aiState == AiState.IDLE) {
                        askAi(result.query)
                    }
                }
        }
        // T1 rolling minutes: refresh the draft when enough new transcript accumulates.
        viewModelScope.launch {
            segments.collect { maybeRefreshRollingMinutes() }
        }
        // Surface a warning if no API key is set — otherwise transcription fails silently.
        viewModelScope.launch {
            settingsRepo.apiKey.collect { key ->
                uiState.update { it.copy(apiKeyMissing = key.isBlank()) }
            }
        }
    }

    /**
     * T1 (R10): if enough new (non-AI) transcript has accumulated since the last fold
     * and the throttle interval has passed, revise the draft. Serialized with T2 via mutex.
     */
    private suspend fun maybeRefreshRollingMinutes() {
        val newChars = segments.value
            .filter { !it.isAi && it.id > lastFoldedSegmentId }
            .sumOf { it.text.length }
        if (newChars < Constants.MINUTES_REFRESH_CHARS) return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAtMs < Constants.MINUTES_REFRESH_MIN_INTERVAL_MS) return
        refreshDraftMinutes()
    }

    /** Fold all segments newer than the cursor into the rolling draft. Safe to call from T1 or T2. */
    private suspend fun refreshDraftMinutes() {
        val meeting = uiState.value.meeting ?: return
        minutesMutex.withLock {
            val newSegments = segments.value.filter { it.id > lastFoldedSegmentId }
            if (newSegments.isEmpty()) return
            try {
                minutesRepo.refreshDraft(meeting, newSegments)
                lastFoldedSegmentId = newSegments.maxOf { it.id }
                lastRefreshAtMs = System.currentTimeMillis()
            } catch (_: Exception) {
                // Best-effort; leave cursor unchanged so we retry on the next trigger.
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

    // Returns only after DB is updated so the caller can navigate immediately.
    suspend fun stopMeetingAndFinish() {
        timerJob?.cancel()
        val finalChunk = recordingService?.stopRecording()
        // Read the full-recording path before unbinding drops the service reference.
        val audioPath = recordingService?.fullAudioFilePath()
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        finalChunk?.let { processChunk(it) }
        meetingRepo.setFinished(meetingId)
        meetingRepo.setAudioFilePath(meetingId, audioPath)
    }

    fun pauseMicForPtt() = recordingService?.pauseForSpeechRecognizer()
    fun resumeMicAfterPtt() = recordingService?.resumeAfterSpeechRecognizer()

    /**
     * Open the minutes preview (R10). Flushes the latest audio and folds any pending
     * transcript into the draft so the preview shows an up-to-date summary, then loads it.
     */
    fun openMinutesPreview() {
        uiState.update { it.copy(previewOpen = true, previewContent = null) }
        viewModelScope.launch {
            recordingService?.flushCurrentChunk()?.let { processChunk(it) }
            refreshDraftMinutes()
            val content = minutesRepo.getDraft(meetingId)?.content
                ?.takeIf { it.isNotBlank() }
                ?: "暂无纪要，等到有一定发言内容后会自动生成。"
            // Ignore if the user already closed the dialog while we were loading.
            if (uiState.value.previewOpen) {
                uiState.update { it.copy(previewContent = content) }
            }
        }
    }

    fun closeMinutesPreview() =
        uiState.update { it.copy(previewOpen = false, previewContent = null) }

    fun assignSpeakerName(label: String, name: String) {
        viewModelScope.launch {
            transcriptionRepo.assignSpeakerName(meetingId, label, name)
        }
    }

    fun askAi(question: String, rolePrompt: String? = null) {
        if (uiState.value.aiState != AiState.IDLE) return
        uiState.update { it.copy(aiState = AiState.THINKING, pendingAiQuery = question) }
        aiJob = viewModelScope.launch {
            // T2 (R10): flush latest audio → transcribe → fold everything into the draft
            // so the AI answers on top of freshly organized full context.
            recordingService?.flushCurrentChunk()?.let { processChunk(it) }
            refreshDraftMinutes()
            delay(2500)
            if (uiState.value.aiState != AiState.THINKING) return@launch
            runAiResponse(question, rolePrompt)
        }
    }

    fun cancelPendingAi() {
        ttsPlayer.interrupt()   // stop playback first, then cancel the coroutine
        aiJob?.cancel()
        recordingService?.resumeAfterSpeechRecognizer()  // ensure mic restarts if TTS was active
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

        val aiName = settingsRepo.aiWakeName.first().ifBlank { "AI" }
        val response = try {
            askAiApi.ask(
                AiRequest(
                    meetingContext = buildContext(meeting.id),
                    question = question,
                    aiName = aiName,
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
        recordingService?.pauseForSpeechRecognizer()
        try { ttsPlayer.speak(response) } catch (_: Exception) {}
        recordingService?.resumeAfterSpeechRecognizer()
        delay(300)
        uiState.update { it.copy(aiState = AiState.IDLE, pendingAiQuery = null, lastAiSegmentId = null) }
    }

    /**
     * Context for an AI answer (R10 / T2): the freshly refreshed minutes draft as the
     * global summary (covers the whole meeting so far), plus the most recent raw lines
     * for detail. Falls back to recent lines only if no draft exists yet.
     */
    private suspend fun buildContext(meetingId: Long): String {
        val recent = segments.value.takeLast(20).joinToString("\n") { seg ->
            val name = if (seg.isAi) "AI" else (seg.speakerName ?: seg.speakerLabel)
            "$name：${seg.text}"
        }
        val draft = minutesRepo.getDraft(meetingId)?.content?.takeIf { it.isNotBlank() }
        return if (draft == null) recent
        else "## 目前会议纪要\n\n$draft\n\n## 最近对话\n\n$recent"
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

    fun checkWakeWord(text: String, wakeName: String): WakeWordDetector.WakeResult? =
        WakeWordDetector.detect(text, wakeName)

    override fun onCleared() {
        ttsPlayer.interrupt()
        super.onCleared()
    }
}
